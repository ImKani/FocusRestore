/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static com.hyperos3.focusrestore.IslandCaptureSession.IdentityAuthorization.*;
import static com.hyperos3.focusrestore.IslandCaptureSession.LockState.*;
import static com.hyperos3.focusrestore.IslandCaptureSession.Submission.*;
import static com.hyperos3.focusrestore.IslandCaptureRecord.State.*;
import static com.hyperos3.focusrestore.IslandCaptureRecord.Reason.LOCK_STATE_UNKNOWN;
import static com.hyperos3.focusrestore.IslandCaptureRecord.Reason.READ_FAILED;
import static com.hyperos3.focusrestore.IslandCaptureRecord.Reason.TYPE_MISMATCH;
import static com.hyperos3.focusrestore.IslandCaptureRecord.Reason.MISSING_FIELD;
import static com.hyperos3.focusrestore.IslandCaptureRecord.Reason.OVERSIZE;

/** C2-3 纯 JVM 测试；用闩锁控制任务交错，不依赖 sleep 或设备状态。 */
public class IslandCaptureSessionTest {
    private static final String PACKAGE = "com.example.capture";

    private static IslandCaptureSession enabled() {
        IslandCaptureSession session = new IslandCaptureSession();
        session.enable(Collections.singleton(PACKAGE));
        session.setLockState(UNLOCKED);
        return session;
    }

    private static IslandCaptureSession.BodyFields fields(String body) {
        return new IslandCaptureSession.BodyFields(body, "", "");
    }

    private static IslandCaptureSession.Submission offer(IslandCaptureSession session,
            IslandCaptureSession.BodySource source) {
        return session.offer(PACKAGE, 0, "notification-key", CONFIRMED, source);
    }

    private static void idle(IslandCaptureSession session) throws InterruptedException {
        assertTrue("capture worker did not become idle", session.awaitIdle(5, TimeUnit.SECONDS));
    }

    private static String repeated(char character, int count) {
        char[] text = new char[count];
        Arrays.fill(text, character);
        return new String(text);
    }

    private static void privateFieldsAbsent(IslandCaptureRecord record) {
        assertNull(record.packageName); assertNull(record.userId);
        assertNull(record.notificationKey); assertNull(record.bodyText);
        assertNull(record.rawParamJSON); assertNull(record.extrasJson);
        assertEquals(0L, record.bodyBytes);
        assertNotNull(record.id); assertFalse(record.id.isEmpty());
        assertTrue(record.capturedAt > 0L);
    }

    /** 即使断言失败也会释放正文回调，避免测试遗留一个阻塞 worker。 */
    private static final class BlockedBody implements IslandCaptureSession.BodySource, AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger reads = new AtomicInteger();
        @Override public IslandCaptureSession.BodyFields read() throws Exception {
            reads.incrementAndGet(); entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test gate timed out");
            return fields("active");
        }
        void awaitEntered() throws InterruptedException {
            assertTrue("worker did not enter controlled body source", entered.await(5, TimeUnit.SECONDS));
        }
        @Override public void close() { release.countDown(); }
    }

    @Test public void defaultDisabledRejectsWithoutReadingOrQueueing() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (IslandCaptureSession session = new IslandCaptureSession()) {
            assertEquals(DISABLED, offer(session, () -> { reads.incrementAndGet(); return fields("body"); }));
            assertEquals(0, reads.get()); assertEquals(0, session.pendingCount());
            assertEquals(0L, session.pendingReservedBodyBytes()); assertTrue(session.records().isEmpty());
            assertEquals(1L, session.counters().rejectedDisabled);
            idle(session);
        }
    }

    @Test public void emptyAllowlistAndUnknownPackagesRejectWithoutReading() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        IslandCaptureSession.BodySource source = () -> { reads.incrementAndGet(); return fields("body"); };
        try (IslandCaptureSession session = new IslandCaptureSession()) {
            session.enable(Collections.emptySet()); session.setLockState(UNLOCKED);
            assertEquals(NOT_ALLOWLISTED, offer(session, source));
            session.enable(Collections.singleton(PACKAGE));
            for (String name : new String[] { null, "", "com.example.other" }) {
                assertEquals(NOT_ALLOWLISTED, session.offer(name, 0, "key", CONFIRMED, source));
            }
            assertEquals(0, reads.get()); assertTrue(session.records().isEmpty());
            assertEquals(4L, session.counters().rejectedNotAllowlisted);
        }
    }

    @Test public void enableTakesAllowlistSnapshotAndNeverNormalizesPackage() throws Exception {
        Set<String> allowlist = new HashSet<>(); allowlist.add(PACKAGE);
        try (IslandCaptureSession session = new IslandCaptureSession()) {
            session.enable(allowlist); session.setLockState(UNLOCKED); allowlist.clear();
            assertEquals(QUEUED, offer(session, () -> fields("allowed")));
            assertEquals(NOT_ALLOWLISTED, session.offer(" " + PACKAGE, 0, "key", CONFIRMED, () -> fields("no")));
            idle(session); assertEquals(1, session.records().size());
            assertEquals("allowed", session.records().get(0).bodyText);
        }
    }

    @Test public void unverifiedAndDeniedAuthorizationPublishOnlyRejectionMetadata() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (IslandCaptureSession session = enabled()) {
            for (IslandCaptureSession.IdentityAuthorization authorization : Arrays.asList(UNVERIFIED, DENIED)) {
                assertEquals(AUTH_UNVERIFIED, session.offer(PACKAGE, 0, "private-key", authorization,
                        () -> { reads.incrementAndGet(); return fields("private-body"); }));
            }
            idle(session); assertEquals(0, reads.get()); assertEquals(2, session.records().size());
            for (IslandCaptureRecord record : session.records()) {
                assertEquals(REJECTED, record.state);
                assertEquals(IslandCaptureRecord.Reason.AUTH_UNVERIFIED, record.reason);
                privateFieldsAbsent(record);
            }
            assertEquals(2L, session.counters().rejectedAuthUnverified);
        }
    }

    @Test public void initialUnknownLockStateProducesBodyFreePlaceholder() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (IslandCaptureSession session = new IslandCaptureSession()) {
            session.enable(Collections.singleton(PACKAGE));
            assertEquals(QUEUED, offer(session, () -> { reads.incrementAndGet(); return fields("body"); }));
            idle(session); assertEquals(0, reads.get());
            IslandCaptureRecord record = session.records().get(0);
            assertEquals(LOCKED_PLACEHOLDER, record.state); assertEquals(LOCK_STATE_UNKNOWN, record.reason);
            privateFieldsAbsent(record); assertEquals(1L, session.counters().lockedPlaceholders);
        }
    }

    @Test public void lockedCaptureKeepsOnlyPlaceholderFields() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (IslandCaptureSession session = enabled()) {
            session.setLockState(LOCKED);
            assertEquals(QUEUED, offer(session, () -> { reads.incrementAndGet(); return fields("body"); }));
            idle(session); assertEquals(0, reads.get());
            IslandCaptureRecord record = session.records().get(0);
            assertEquals(LOCKED_PLACEHOLDER, record.state); assertEquals(IslandCaptureRecord.Reason.LOCKED, record.reason);
            privateFieldsAbsent(record); assertEquals(1L, session.counters().lockedPlaceholders);
        }
    }

    @Test public void lockIsCheckedAgainBeforeQueuedBodyCallback() throws Exception {
        AtomicInteger queuedReads = new AtomicInteger();
        try (IslandCaptureSession session = enabled(); BlockedBody blocker = new BlockedBody()) {
            assertEquals(QUEUED, offer(session, blocker)); blocker.awaitEntered();
            assertEquals(QUEUED, offer(session, () -> { queuedReads.incrementAndGet(); return fields("queued"); }));
            session.setLockState(LOCKED); blocker.close(); idle(session);
            assertEquals(0, queuedReads.get());
            IslandCaptureRecord queued = session.records().get(session.records().size() - 1);
            assertEquals(LOCKED_PLACEHOLDER, queued.state); assertEquals(IslandCaptureRecord.Reason.LOCKED, queued.reason);
            privateFieldsAbsent(queued);
        }
    }

    @Test public void unknownBeforeProcessingAlsoPreventsBodyRead() throws Exception {
        AtomicInteger queuedReads = new AtomicInteger();
        try (IslandCaptureSession session = enabled(); BlockedBody blocker = new BlockedBody()) {
            offer(session, blocker); blocker.awaitEntered();
            offer(session, () -> { queuedReads.incrementAndGet(); return fields("queued"); });
            session.setLockState(UNKNOWN); blocker.close(); idle(session);
            assertEquals(0, queuedReads.get());
            IslandCaptureRecord queued = session.records().get(session.records().size() - 1);
            assertEquals(LOCK_STATE_UNKNOWN, queued.reason); privateFieldsAbsent(queued);
        }
    }

    @Test public void lockedEnqueueCannotBackfillBodyAfterUnlock() throws Exception {
        AtomicInteger queuedReads = new AtomicInteger();
        try (IslandCaptureSession session = enabled(); BlockedBody blocker = new BlockedBody()) {
            offer(session, blocker); blocker.awaitEntered(); session.setLockState(LOCKED);
            offer(session, () -> { queuedReads.incrementAndGet(); return fields("never-read"); });
            session.setLockState(UNLOCKED); blocker.close(); idle(session);
            assertEquals(0, queuedReads.get());
            IslandCaptureRecord queued = session.records().get(session.records().size() - 1);
            assertEquals(LOCKED_PLACEHOLDER, queued.state); assertEquals(IslandCaptureRecord.Reason.LOCKED, queued.reason);
        }
    }

    @Test public void relockDuringActiveReadPublishesPlaceholderWithoutClearingOlderRecord() throws Exception {
        try (IslandCaptureSession session = enabled(); BlockedBody blocker = new BlockedBody()) {
            offer(session, () -> fields("previously-captured")); idle(session);
            IslandCaptureRecord older = session.records().get(0);
            offer(session, blocker); blocker.awaitEntered();
            session.setLockState(LOCKED);
            assertSame(older, session.records().get(0));
            blocker.close(); idle(session);
            assertEquals(2, session.records().size()); assertSame(older, session.records().get(0));
            assertEquals("previously-captured", older.bodyText);
            IslandCaptureRecord current = session.records().get(1);
            assertEquals(LOCKED_PLACEHOLDER, current.state);
            assertEquals(IslandCaptureRecord.Reason.LOCKED, current.reason);
            privateFieldsAbsent(current); assertEquals(1L, session.counters().lockedPlaceholders);
            assertEquals(0L, session.counters().droppedSessionStale);
        }
    }

    @Test public void bodyCallbackRunsOffEntryThreadAndOfferDoesNotWaitForIt() throws Exception {
        AtomicReference<Thread> reader = new AtomicReference<>();
        Thread submitting = Thread.currentThread();
        try (IslandCaptureSession session = enabled(); BlockedBody blocker = new BlockedBody()) {
            assertEquals(QUEUED, offer(session, () -> { reader.set(Thread.currentThread()); return blocker.read(); }));
            blocker.awaitEntered(); assertNotSame(submitting, reader.get());
            // 回调尚未完成，但 offer 已返回；入口未同步执行/等待正文工作。
            assertFalse(session.awaitIdle(0, TimeUnit.NANOSECONDS));
            blocker.close(); idle(session); assertEquals("active", session.records().get(0).bodyText);
        }
    }

    /** 唯一外部读取边界代表底层 IO/网络等重操作；测试不执行真实 IO 或网络。 */
    private static final class EntryBoundaryGuard implements IslandCaptureSession.BodySource, java.io.Serializable {
        private static final long serialVersionUID = 1L;
        final Thread submitting = Thread.currentThread();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger serializations = new AtomicInteger();
        final AtomicInteger stringifications = new AtomicInteger();
        @Override public IslandCaptureSession.BodyFields read() {
            assertNotSame("entry must not invoke an external IO/network read boundary", submitting, Thread.currentThread());
            reads.incrementAndGet();
            return fields("guarded-body");
        }
        private void writeObject(java.io.ObjectOutputStream ignored) {
            serializations.incrementAndGet();
            throw new AssertionError("capture must not serialize the source");
        }
        @Override public String toString() {
            stringifications.incrementAndGet();
            throw new AssertionError("capture must not stringify the source");
        }
    }

    @Test public void entryDoesNotInvokeExternalIoNetworkOrSerializationGuards() throws Exception {
        EntryBoundaryGuard guard = new EntryBoundaryGuard();
        try (IslandCaptureSession session = enabled(); BlockedBody blocker = new BlockedBody()) {
            offer(session, blocker); blocker.awaitEntered();
            assertEquals(QUEUED, offer(session, guard));
            assertEquals(0, guard.reads.get()); assertEquals(0, guard.serializations.get());
            assertEquals(0, guard.stringifications.get());
            blocker.close(); idle(session);
            assertEquals(1, guard.reads.get()); assertEquals(0, guard.serializations.get());
            assertEquals(0, guard.stringifications.get());
            assertEquals("guarded-body", session.records().get(1).bodyText);
        }
    }

    @Test public void readExceptionIsContainedAndNextItemStillCompletes() throws Exception {
        try (IslandCaptureSession session = enabled()) {
            assertEquals(QUEUED, offer(session, () -> { throw new IOException("private exception text"); }));
            assertEquals(QUEUED, offer(session, () -> fields("after-error")));
            idle(session); assertEquals(2, session.records().size());
            IslandCaptureRecord failed = session.records().get(0);
            assertEquals(READ_ERROR, failed.state); assertEquals(READ_FAILED, failed.reason);
            privateFieldsAbsent(failed);
            assertEquals(CAPTURED, session.records().get(1).state);
            assertEquals("after-error", session.records().get(1).bodyText);
        }
    }

    @Test public void nonVmErrorInBodySourceDoesNotKillWorkerOrBlockNextItem() throws Exception {
        try (IslandCaptureSession session = enabled()) {
            assertEquals(QUEUED, offer(session, () -> { throw new AssertionError("private callback failure"); }));
            assertEquals(QUEUED, offer(session, () -> fields("after-assertion")));
            idle(session); assertEquals(2, session.records().size());
            IslandCaptureRecord failure = session.records().get(0);
            assertEquals(READ_ERROR, failure.state); assertEquals(READ_FAILED, failure.reason);
            privateFieldsAbsent(failure);
            assertEquals(CAPTURED, session.records().get(1).state);
            assertEquals("after-assertion", session.records().get(1).bodyText);
        }
    }

    @Test public void missingBodyFieldsAndMissingBodyObjectHaveFixedReason() throws Exception {
        for (IslandCaptureSession.BodyFields content : Arrays.asList(null,
                new IslandCaptureSession.BodyFields(null, "", ""),
                new IslandCaptureSession.BodyFields("", null, ""),
                new IslandCaptureSession.BodyFields("", "", null))) {
            try (IslandCaptureSession session = enabled()) {
                offer(session, () -> content); idle(session);
                IslandCaptureRecord record = session.records().get(0);
                assertEquals(READ_ERROR, record.state); assertEquals(MISSING_FIELD, record.reason);
                privateFieldsAbsent(record);
            }
        }
    }

    @Test public void wrongBodyTypesAreNotCoercedOrStringified() throws Exception {
        AtomicInteger stringifications = new AtomicInteger();
        Object forbidden = new Object() {
            @Override public String toString() { stringifications.incrementAndGet(); return "private"; }
        };
        for (IslandCaptureSession.BodyFields content : Arrays.asList(
                new IslandCaptureSession.BodyFields(forbidden, "", ""),
                new IslandCaptureSession.BodyFields("", 1, ""),
                new IslandCaptureSession.BodyFields("", "", true))) {
            try (IslandCaptureSession session = enabled()) {
                offer(session, () -> content); idle(session);
                IslandCaptureRecord record = session.records().get(0);
                assertEquals(READ_ERROR, record.state); assertEquals(TYPE_MISMATCH, record.reason);
                privateFieldsAbsent(record);
            }
        }
        assertEquals(0, stringifications.get());
    }

    @Test public void missingIdentityMetadataFailsWithoutPublishingBody() throws Exception {
        try (IslandCaptureSession session = enabled()) {
            session.offer(PACKAGE, null, "key", CONFIRMED, () -> fields("body"));
            session.offer(PACKAGE, 0, null, CONFIRMED, () -> fields("body"));
            session.offer(PACKAGE, 0, "key", CONFIRMED, null);
            idle(session); assertEquals(3, session.records().size());
            for (IslandCaptureRecord record : session.records()) {
                assertEquals(READ_ERROR, record.state); assertEquals(MISSING_FIELD, record.reason);
                privateFieldsAbsent(record);
            }
        }
    }

    @Test public void capturedEmptyStringsRemainEmptyWithConfirmedIdentity() throws Exception {
        try (IslandCaptureSession session = enabled()) {
            offer(session, () -> new IslandCaptureSession.BodyFields("", "", "")); idle(session);
            IslandCaptureRecord record = session.records().get(0);
            assertEquals(CAPTURED, record.state); assertNull(record.reason);
            assertEquals(PACKAGE, record.packageName); assertEquals(Integer.valueOf(0), record.userId);
            assertEquals("notification-key", record.notificationKey);
            assertEquals("", record.bodyText); assertEquals("", record.rawParamJSON);
            assertEquals("", record.extrasJson); assertEquals(0L, record.bodyBytes);
        }
    }

    @Test public void exactFieldAndCombinedBodyByteLimitsAreAccepted() throws Exception {
        String exact = repeated('a', 65536);
        try (IslandCaptureSession session = enabled()) {
            offer(session, () -> new IslandCaptureSession.BodyFields(exact, exact, exact)); idle(session);
            IslandCaptureRecord record = session.records().get(0);
            assertEquals(CAPTURED, record.state); assertEquals(196608L, record.bodyBytes);
            assertEquals(exact, record.bodyText); assertEquals(exact, record.rawParamJSON);
            assertEquals(exact, record.extrasJson); assertEquals(196608L, session.completedBodyBytes());
            assertEquals(0L, session.counters().droppedOversize);
        }
    }

    @Test public void eachOversizedFieldRejectsWholeRecordWithoutTruncation() throws Exception {
        String tooLarge = repeated('a', 65537);
        for (IslandCaptureSession.BodyFields content : Arrays.asList(
                new IslandCaptureSession.BodyFields(tooLarge, "keep-no-part", ""),
                new IslandCaptureSession.BodyFields("", tooLarge, "keep-no-part"),
                new IslandCaptureSession.BodyFields("keep-no-part", "", tooLarge))) {
            try (IslandCaptureSession session = enabled()) {
                offer(session, () -> content); idle(session);
                IslandCaptureRecord record = session.records().get(0);
                assertEquals(REJECTED, record.state); assertEquals(OVERSIZE, record.reason);
                privateFieldsAbsent(record); assertEquals(1L, session.counters().droppedOversize);
                assertEquals(0L, session.completedBodyBytes());
            }
        }
    }

    @Test public void combinedBodyOneByteOverCapRejectsWholeRecord() throws Exception {
        String exact = repeated('a', 65536);
        String over = exact + "a";
        assertEquals(196609L, (long) exact.getBytes(StandardCharsets.UTF_8).length
                + exact.getBytes(StandardCharsets.UTF_8).length + over.getBytes(StandardCharsets.UTF_8).length);
        // 总量上限等于三个单字段上限之和，合计超额必然同时触发至少一个字段超额。
        try (IslandCaptureSession session = enabled()) {
            offer(session, () -> new IslandCaptureSession.BodyFields(exact, exact, over)); idle(session);
            IslandCaptureRecord record = session.records().get(0);
            assertEquals(REJECTED, record.state); assertEquals(OVERSIZE, record.reason);
            privateFieldsAbsent(record); assertEquals(1L, session.counters().droppedOversize);
            assertEquals(0L, session.completedBodyBytes());
        }
    }

    @Test public void utf8SupplementaryAndUnpairedSurrogatesMatchJavaByteAccounting() throws Exception {
        String text = "A\uD83D\uDE80\uD800B\uDC00\u754c";
        try (IslandCaptureSession session = enabled()) {
            offer(session, () -> fields(text)); idle(session);
            IslandCaptureRecord record = session.records().get(0);
            assertEquals(CAPTURED, record.state);
            assertEquals(text.getBytes(StandardCharsets.UTF_8).length, record.bodyBytes);
            assertEquals(text, record.bodyText);
        }
    }

    @Test public void utf8BytesRatherThanUtf16LengthDetermineFieldBoundary() throws Exception {
        String exact = repeated('\u754c', 21845) + "a";
        String tooLarge = exact + "a";
        assertEquals(65536, exact.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(65537, tooLarge.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(tooLarge.length() < 65536);
        try (IslandCaptureSession session = enabled()) {
            offer(session, () -> fields(exact)); offer(session, () -> fields(tooLarge)); idle(session);
            assertEquals(CAPTURED, session.records().get(0).state);
            assertEquals(65536L, session.records().get(0).bodyBytes);
            assertEquals(OVERSIZE, session.records().get(1).reason);
            privateFieldsAbsent(session.records().get(1));
        }
    }

    @Test public void metadataStringBytesDoNotConsumeBodyByteQuota() throws Exception {
        String key = repeated('k', 4096);
        try (IslandCaptureSession session = enabled()) {
            session.offer(PACKAGE, 0, key, CONFIRMED, () -> fields("abc")); idle(session);
            IslandCaptureRecord record = session.records().get(0);
            assertEquals(key, record.notificationKey); assertEquals(3L, record.bodyBytes);
            assertEquals(3L, session.completedBodyBytes());
        }
    }

    @Test public void pendingUnknownBodiesReserveWorstCaseAndRejectEleventh() throws Exception {
        AtomicInteger queuedReads = new AtomicInteger();
        try (IslandCaptureSession session = enabled(); BlockedBody blocker = new BlockedBody()) {
            offer(session, blocker); blocker.awaitEntered();
            for (int index = 0; index < 10; index++) {
                assertEquals(QUEUED, offer(session, () -> { queuedReads.incrementAndGet(); return fields(""); }));
            }
            assertEquals(10, session.pendingCount()); assertEquals(1966080L, session.pendingReservedBodyBytes());
            assertEquals(QUEUE_FULL, offer(session, () -> { queuedReads.incrementAndGet(); return fields("discarded"); }));
            assertEquals(10, session.pendingCount()); assertEquals(1L, session.counters().droppedQueueFull);
            assertEquals(0, queuedReads.get()); blocker.close(); idle(session);
            assertEquals(10, queuedReads.get()); assertEquals(11, session.records().size());
        }
    }

    @Test public void pendingMetadataHasIndependentSixtyFourItemLimit() throws Exception {
        AtomicInteger metadataReads = new AtomicInteger();
        try (IslandCaptureSession session = enabled(); BlockedBody blocker = new BlockedBody()) {
            offer(session, blocker); blocker.awaitEntered(); session.setLockState(LOCKED);
            for (int index = 0; index < 64; index++) {
                assertEquals(QUEUED, offer(session, () -> { metadataReads.incrementAndGet(); return fields("no"); }));
            }
            assertEquals(64, session.pendingCount()); assertEquals(0L, session.pendingReservedBodyBytes());
            assertEquals(QUEUE_FULL, offer(session, () -> fields("no")));
            assertEquals(1L, session.counters().droppedQueueFull); assertEquals(0, metadataReads.get());
            blocker.close(); idle(session); assertEquals(0, metadataReads.get());
        }
    }

    @Test public void pendingBodyAndMetadataShareItemCountWithoutExtraBodyReservation() throws Exception {
        try (IslandCaptureSession session = enabled(); BlockedBody blocker = new BlockedBody()) {
            offer(session, blocker); blocker.awaitEntered();
            for (int index = 0; index < 10; index++) assertEquals(QUEUED, offer(session, () -> fields("")));
            session.setLockState(LOCKED);
            for (int index = 0; index < 54; index++) assertEquals(QUEUED, offer(session, () -> fields("never")));
            assertEquals(64, session.pendingCount()); assertEquals(1966080L, session.pendingReservedBodyBytes());
            assertEquals(QUEUE_FULL, offer(session, () -> fields("no")));
            blocker.close(); idle(session);
        }
    }

    @Test public void completedRecordCountRejectsNewRecordWithoutEvictingOld() throws Exception {
        try (IslandCaptureSession session = enabled()) {
            for (int index = 0; index < 64; index++) {
                final String body = "body-" + index;
                assertEquals(QUEUED, offer(session, () -> fields(body))); idle(session);
            }
            List<IslandCaptureRecord> before = session.records();
            assertEquals(64, before.size());
            offer(session, () -> fields("discard-new")); idle(session);
            assertEquals(64, session.records().size());
            for (int index = 0; index < before.size(); index++) assertSame(before.get(index), session.records().get(index));
            assertEquals("body-0", session.records().get(0).bodyText);
            assertEquals(1L, session.counters().droppedCompletedBufferFull);
        }
    }

    @Test public void completedTwoMiBByteLimitIsIndependentOfRecordCount() throws Exception {
        String exactField = repeated('a', 65536);
        try (IslandCaptureSession session = enabled()) {
            for (int index = 0; index < 32; index++) {
                offer(session, () -> fields(exactField)); idle(session);
            }
            assertEquals(32, session.records().size()); assertEquals(2097152L, session.completedBodyBytes());
            String firstId = session.records().get(0).id;
            offer(session, () -> fields("a")); idle(session);
            assertEquals(32, session.records().size()); assertEquals(firstId, session.records().get(0).id);
            assertEquals(2097152L, session.completedBodyBytes());
            assertEquals(1L, session.counters().droppedCompletedBufferFull);
        }
    }

    @Test public void lockedPlaceholderCounterIncludesCompletedBufferRejection() throws Exception {
        try (IslandCaptureSession session = enabled()) {
            session.setLockState(LOCKED);
            for (int index = 0; index < 65; index++) {
                offer(session, () -> fields("never")); idle(session);
            }
            assertEquals(64, session.records().size());
            assertEquals(65L, session.counters().lockedPlaceholders);
            assertEquals(1L, session.counters().droppedCompletedBufferFull);
        }
    }

    @Test public void clearInvalidatesActiveAndQueuedWorkButPreservesExplicitEnable() throws Exception {
        AtomicInteger staleQueuedReads = new AtomicInteger();
        try (IslandCaptureSession session = enabled(); BlockedBody blocker = new BlockedBody()) {
            offer(session, () -> fields("old-completed")); idle(session);
            offer(session, blocker); blocker.awaitEntered();
            offer(session, () -> { staleQueuedReads.incrementAndGet(); return fields("old-queued"); });
            session.clear(); assertTrue(session.records().isEmpty()); assertEquals(0, session.pendingCount());
            assertEquals(0L, session.pendingReservedBodyBytes()); assertEquals(0L, session.completedBodyBytes());
            assertEquals(QUEUED, offer(session, () -> fields("new-generation")));
            blocker.close(); idle(session); assertEquals(0, staleQueuedReads.get());
            assertEquals(1, session.records().size()); assertEquals("new-generation", session.records().get(0).bodyText);
            assertEquals(2L, session.counters().droppedSessionStale);
        }
    }

    @Test public void disableAndInvalidateSessionDiscardOldTasksAndRequireExplicitEnable() throws Exception {
        for (boolean disable : new boolean[] { true, false }) {
            try (IslandCaptureSession session = enabled(); BlockedBody blocker = new BlockedBody()) {
                offer(session, blocker); blocker.awaitEntered(); offer(session, () -> fields("queued"));
                if (disable) session.disable(); else session.invalidateSession();
                assertEquals(DISABLED, offer(session, () -> fields("no")));
                assertEquals(0, session.pendingCount()); assertTrue(session.records().isEmpty());
                blocker.close(); idle(session); assertTrue(session.records().isEmpty());
                assertEquals(2L, session.counters().droppedSessionStale);
            }
        }
    }

    @Test public void explicitEnableStartsNewGenerationAndReplacesAllowlist() throws Exception {
        try (IslandCaptureSession session = enabled(); BlockedBody blocker = new BlockedBody()) {
            offer(session, blocker); blocker.awaitEntered(); offer(session, () -> fields("old-queued"));
            session.enable(Collections.singleton("com.example.new"));
            assertEquals(NOT_ALLOWLISTED, offer(session, () -> fields("old-package")));
            assertEquals(QUEUED, session.offer("com.example.new", 0, "new-key", CONFIRMED, () -> fields("new")));
            blocker.close(); idle(session); assertEquals(1, session.records().size());
            assertEquals("com.example.new", session.records().get(0).packageName);
            assertEquals("new", session.records().get(0).bodyText);
            assertEquals(2L, session.counters().droppedSessionStale);
        }
    }

    @Test public void countersSurviveClearAndSnapshotsDoNotChange() throws Exception {
        try (IslandCaptureSession session = new IslandCaptureSession()) {
            offer(session, () -> fields("never"));
            IslandCaptureSession.Counters before = session.counters();
            assertEquals(1L, before.rejectedDisabled); session.clear();
            offer(session, () -> fields("never"));
            assertEquals(1L, before.rejectedDisabled); assertEquals(2L, session.counters().rejectedDisabled);
        }
    }

    @Test public void recordsSnapshotIsReadOnlyAndDetachedFromLaterClear() throws Exception {
        try (IslandCaptureSession session = enabled()) {
            offer(session, () -> fields("snapshot")); idle(session);
            List<IslandCaptureRecord> snapshot = session.records();
            try { snapshot.clear(); fail("records list must be immutable"); }
            catch (UnsupportedOperationException expected) { }
            session.clear(); assertTrue(session.records().isEmpty());
            assertEquals(1, snapshot.size()); assertEquals("snapshot", snapshot.get(0).bodyText);
        }
    }

    @Test public void concurrentOffersSafelyEnforceSharedMetadataQueueCapacity() throws Exception {
        ExecutorService producers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger(); AtomicInteger bodyReads = new AtomicInteger();
        try (IslandCaptureSession session = enabled(); BlockedBody blocker = new BlockedBody()) {
            offer(session, blocker); blocker.awaitEntered(); session.setLockState(LOCKED);
            List<Future<?>> futures = new ArrayList<>();
            for (int producer = 0; producer < 8; producer++) {
                futures.add(producers.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IOException("producer gate timed out");
                    for (int index = 0; index < 8; index++) {
                        if (offer(session, () -> { bodyReads.incrementAndGet(); return fields("never"); }) == QUEUED) {
                            accepted.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(64, accepted.get()); assertEquals(64, session.pendingCount());
            assertEquals(0L, session.pendingReservedBodyBytes()); assertEquals(0, bodyReads.get());
            assertEquals(QUEUE_FULL, offer(session, () -> fields("no")));
            blocker.close(); idle(session);
            Set<String> ids = new HashSet<>(); Set<Long> sequences = new HashSet<>();
            for (IslandCaptureRecord record : session.records()) {
                assertTrue(ids.add(record.id)); assertTrue(sequences.add(record.sequence));
            }
            assertEquals(0, bodyReads.get()); assertEquals(64, session.records().size());
        } finally {
            start.countDown(); producers.shutdownNow();
            assertTrue("producer pool did not terminate", producers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void closeIsIdempotentDisablesAndClearsSession() throws Exception {
        IslandCaptureSession session = enabled();
        try {
            offer(session, () -> fields("old")); idle(session);
            session.close(); session.close();
            assertTrue(session.records().isEmpty()); assertEquals(0, session.pendingCount());
            assertEquals(DISABLED, offer(session, () -> fields("never")));
            assertEquals(0L, session.completedBodyBytes()); idle(session);
        } finally { session.close(); }
    }
}
