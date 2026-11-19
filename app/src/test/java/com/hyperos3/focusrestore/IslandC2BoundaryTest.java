/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.*;
import static com.hyperos3.focusrestore.IslandCaptureSession.IdentityAuthorization.*;
import static com.hyperos3.focusrestore.IslandCaptureSession.LockState.*;
import static com.hyperos3.focusrestore.IslandCaptureSession.Submission.*;

/** C2-5 跨捕获/导出的真实内存边界；来源为项目 C2-3/C2-4 API 与既有合同测试。 */
public class IslandC2BoundaryTest {
    private static final String PACKAGE = "com.example.c2boundary";
    private static final long EXPORT_GENERATION = 7;

    private static IslandCaptureSession enabled() {
        IslandCaptureSession session = new IslandCaptureSession();
        session.enable(Collections.singleton(PACKAGE));
        session.setLockState(UNLOCKED);
        return session;
    }
    private static IslandCaptureSession.BodyFields fields(String body) {
        return new IslandCaptureSession.BodyFields(body, "{}", "{}");
    }
    private static IslandCaptureSession.Submission offer(IslandCaptureSession session,
            IslandCaptureSession.BodySource source) {
        return session.offer(PACKAGE, 10, "private-notification-key", CONFIRMED, source);
    }
    private static String repeat(char character, int count) {
        char[] text = new char[count]; Arrays.fill(text, character); return new String(text);
    }
    private static String exactJson(String key, int length) {
        String prefix = "{\"" + key + "\":\"";
        return prefix + repeat('a', length - prefix.length() - 2) + "\"}";
    }
    private static void idle(IslandCaptureSession session) throws InterruptedException {
        assertTrue("controlled capture worker did not become idle", session.awaitIdle(10, TimeUnit.SECONDS));
    }
    private static void noPrivateFields(IslandCaptureRecord record) {
        assertNull(record.packageName); assertNull(record.userId); assertNull(record.notificationKey);
        assertNull(record.bodyText); assertNull(record.rawParamJSON); assertNull(record.extrasJson);
        assertEquals(0L, record.bodyBytes);
    }
    private static final class Gate implements IslandCaptureSession.BodySource, AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        @Override public IslandCaptureSession.BodyFields read() throws InterruptedException {
            entered.countDown();
            if (!released.await(10, TimeUnit.SECONDS)) throw new AssertionError("controlled gate timeout");
            return fields("gate-body");
        }
        void awaitEntered() throws InterruptedException {
            assertTrue("worker did not enter gate", entered.await(10, TimeUnit.SECONDS));
        }
        @Override public void close() { released.countDown(); }
    }
    private static IslandExportState state(IslandExportState.LockState lock, long generation) {
        return new IslandExportState(lock, IslandExportState.ExportAuthorization.AUTHORIZED,
                IslandExportState.FullConfirmation.CONFIRMED, generation);
    }
    private static IslandExportState unlocked() {
        return state(IslandExportState.LockState.UNLOCKED, EXPORT_GENERATION);
    }
    private static IslandExportRequest request(List<IslandCaptureRecord> records, IslandExportRequest.Mode mode) {
        return new IslandExportRequest(records, null, Collections.singleton(PACKAGE), mode);
    }
    private static IslandExportDocument export(List<IslandCaptureRecord> records, IslandExportRequest.Mode mode) {
        IslandExportGenerator generator = new IslandExportGenerator();
        IslandExportGenerator.ExportPlan plan = generator.begin(request(records, mode), unlocked());
        while (plan.nextIndex < plan.frozenSamples.size()) {
            IslandExportGenerator.Step step = generator.renderNextSample(plan, unlocked());
            assertNull(step.incomplete); assertNull(step.jsonFragment); assertNull(step.markdownFragment);
            plan = step.plan;
        }
        IslandExportDocument document = generator.finish(plan, unlocked());
        assertTrue(document.complete); assertTrue(document.meta.complete);
        return document;
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> samples(IslandExportDocument document) {
        return (List<Map<String, Object>>) (List<?>) ((Map<?, ?>) IslandExportJson.parse(document.json)).get("samples");
    }

    @Test public void actualSessionThreeExactFieldsBecome196608ByteRecordAndRedactedDocument() throws Exception {
        String body = repeat('b', 65536);
        String raw = exactJson("title", 65536);
        String extras = exactJson("miui.focus.ticker", 65536);
        assertEquals(65536, raw.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(65536, extras.getBytes(StandardCharsets.UTF_8).length);
        try (IslandCaptureSession session = enabled()) {
            assertEquals(QUEUED, offer(session, () -> new IslandCaptureSession.BodyFields(body, raw, extras)));
            idle(session);
            IslandCaptureRecord record = session.records().get(0);
            assertEquals(IslandCaptureRecord.State.CAPTURED, record.state);
            assertEquals(196608L, record.bodyBytes); assertEquals(196608L, session.completedBodyBytes());
            assertEquals(body, record.bodyText); assertEquals(raw, record.rawParamJSON); assertEquals(extras, record.extrasJson);
            IslandExportDocument document = export(session.records(), IslandExportRequest.Mode.REDACTED);
            assertFalse(document.json.contains(repeat('b', 100)));
            assertEquals(1, document.meta.sampleCount); assertEquals(0L, session.counters().droppedOversize);
        }
    }

    @Test public void realSessionEach65537ByteFieldRejectsWholeRecordAndKeepsNextCapture() throws Exception {
        String exact = repeat('a', 65536);
        String over = exact + "a";
        try (IslandCaptureSession session = enabled()) {
            for (int field = 0; field < 3; field++) {
                final IslandCaptureSession.BodyFields content = new IslandCaptureSession.BodyFields(
                        field == 0 ? over : exact, field == 1 ? over : exact, field == 2 ? over : exact);
                assertEquals(QUEUED, offer(session, () -> content)); idle(session);
            }
            assertEquals(QUEUED, offer(session, () -> fields("after-oversize"))); idle(session);
            assertEquals(4, session.records().size());
            for (int i = 0; i < 3; i++) {
                IslandCaptureRecord record = session.records().get(i);
                assertEquals(IslandCaptureRecord.State.REJECTED, record.state);
                assertEquals(IslandCaptureRecord.Reason.OVERSIZE, record.reason); noPrivateFields(record);
            }
            assertEquals("after-oversize", session.records().get(3).bodyText);
            assertEquals(3L, session.counters().droppedOversize);
            assertEquals("after-oversize".length() + 4L, session.completedBodyBytes());
        }
    }

    @Test public void mixedPendingUses64ItemsAndWorstCaseReservationBeforeAnyQueuedRead() throws Exception {
        AtomicInteger queuedReads = new AtomicInteger();
        try (IslandCaptureSession session = enabled(); Gate gate = new Gate()) {
            offer(session, gate); gate.awaitEntered();
            for (int i = 0; i < 10; i++) assertEquals(QUEUED, offer(session,
                    () -> { queuedReads.incrementAndGet(); return fields(""); }));
            assertEquals(1966080L, session.pendingReservedBodyBytes());
            assertEquals(QUEUE_FULL, offer(session, () -> fields("eleventh-unknown-body")));
            for (int i = 0; i < 54; i++) assertEquals(AUTH_UNVERIFIED,
                    session.offer(PACKAGE, 10, "private-key", UNVERIFIED,
                            () -> { queuedReads.incrementAndGet(); return fields("not-authorized"); }));
            assertEquals(64, session.pendingCount()); assertEquals(1966080L, session.pendingReservedBodyBytes());
            assertEquals(QUEUE_FULL, session.offer(PACKAGE, 10, "private-key", DENIED, () -> fields("not-read")));
            assertEquals(0, queuedReads.get()); assertEquals(2L, session.counters().droppedQueueFull);
            session.clear(); gate.close(); idle(session);
            assertEquals(0, queuedReads.get()); assertEquals(0, session.pendingCount());
            assertEquals(0L, session.pendingReservedBodyBytes()); assertTrue(session.records().isEmpty());
            assertEquals(65L, session.counters().droppedSessionStale);
        }
    }

    @Test public void actualCompletedBufferReachesTwoMiBWithThreeFieldRecordsWithoutEviction() throws Exception {
        String exact = repeat('a', 65536);
        try (IslandCaptureSession session = enabled()) {
            for (int i = 0; i < 10; i++) {
                offer(session, () -> new IslandCaptureSession.BodyFields(exact, exact, exact)); idle(session);
            }
            offer(session, () -> new IslandCaptureSession.BodyFields(exact, exact, "")); idle(session);
            List<IslandCaptureRecord> retained = session.records();
            assertEquals(11, retained.size()); assertEquals(2097152L, session.completedBodyBytes());
            offer(session, () -> new IslandCaptureSession.BodyFields("a", "", "")); idle(session);
            assertEquals(11, session.records().size()); assertEquals(2097152L, session.completedBodyBytes());
            for (int i = 0; i < retained.size(); i++) assertSame(retained.get(i), session.records().get(i));
            assertEquals(1L, session.counters().droppedCompletedBufferFull);
        }
    }

    @Test public void sixtyFourRealCapturedRecordsExportInCaptureOrderAndSixtyFifthDoesNotEvict() throws Exception {
        try (IslandCaptureSession session = enabled()) {
            for (int i = 0; i < 64; i++) {
                final String body = "body-" + i;
                assertEquals(QUEUED, offer(session, () -> fields(body))); idle(session);
            }
            List<IslandCaptureRecord> frozen = session.records();
            offer(session, () -> fields("sixty-fifth")); idle(session);
            assertEquals(64, session.records().size()); assertEquals(1L, session.counters().droppedCompletedBufferFull);
            IslandExportDocument document = export(frozen, IslandExportRequest.Mode.FULL);
            List<Map<String, Object>> exported = samples(document);
            assertEquals(64, exported.size());
            for (int i = 0; i < 64; i++) {
                assertEquals(IslandCaptureRecord.State.CAPTURED, frozen.get(i).state);
                assertSame(frozen.get(i), session.records().get(i));
                assertEquals(frozen.get(i).id, exported.get(i).get("id"));
                assertEquals("body-" + i, exported.get(i).get("bodyText"));
            }
        }
    }

    @Test public void eightCountersAccumulateExactlyAcrossOneSessionAndGenerationClear() throws Exception {
        try (IslandCaptureSession session = new IslandCaptureSession(); Gate gate = new Gate()) {
            assertEquals(DISABLED, offer(session, () -> fields("disabled")));
            session.enable(Collections.singleton(PACKAGE)); session.setLockState(UNLOCKED);
            assertEquals(NOT_ALLOWLISTED, session.offer("other.package", 10, "key", CONFIRMED, () -> fields("no")));
            for (IslandCaptureSession.IdentityAuthorization auth : Arrays.asList(UNVERIFIED, DENIED))
                assertEquals(AUTH_UNVERIFIED, session.offer(PACKAGE, 10, "key", auth, () -> fields("no")));
            idle(session);
            offer(session, () -> new IslandCaptureSession.BodyFields(repeat('a', 65537), "", "")); idle(session);
            session.setLockState(LOCKED); offer(session, () -> fields("no")); idle(session);
            session.setLockState(UNLOCKED); offer(session, gate); gate.awaitEntered();
            for (int i = 0; i < 10; i++) assertEquals(QUEUED, offer(session, () -> fields("stale")));
            assertEquals(QUEUE_FULL, offer(session, () -> fields("overflow")));
            session.clear(); gate.close(); idle(session);
            for (int i = 0; i < 65; i++) { offer(session, () -> new IslandCaptureSession.BodyFields("", "", "")); idle(session); }
            IslandCaptureSession.Counters counters = session.counters();
            assertEquals(1L, counters.droppedQueueFull);
            assertEquals(1L, counters.droppedOversize);
            assertEquals(1L, counters.droppedCompletedBufferFull);
            assertEquals(11L, counters.droppedSessionStale);
            assertEquals(1L, counters.rejectedDisabled);
            assertEquals(1L, counters.rejectedNotAllowlisted);
            assertEquals(2L, counters.rejectedAuthUnverified);
            assertEquals(1L, counters.lockedPlaceholders);
            assertEquals(64, session.records().size()); assertEquals(0L, session.completedBodyBytes());
            session.clear();
            assertEquals(11L, counters.droppedSessionStale); assertEquals(1L, session.counters().lockedPlaceholders);
        }
    }

    @Test public void processingLockUnknownAndOldGenerationStopQueuedReadsAndExportPlan() throws Exception {
        for (int change = 0; change < 3; change++) {
            AtomicInteger queuedReads = new AtomicInteger();
            try (IslandCaptureSession session = enabled(); Gate gate = new Gate()) {
                offer(session, () -> fields("prior-capture")); idle(session);
                IslandExportGenerator generator = new IslandExportGenerator();
                IslandExportGenerator.ExportPlan plan = generator.begin(request(session.records(), IslandExportRequest.Mode.FULL), unlocked());
                offer(session, gate); gate.awaitEntered();
                offer(session, () -> { queuedReads.incrementAndGet(); return fields("queued-private"); });
                IslandExportState changed;
                IslandExportDocument.Reason expected;
                if (change == 2) {
                    session.clear(); changed = state(IslandExportState.LockState.UNLOCKED, EXPORT_GENERATION + 1);
                    expected = IslandExportDocument.Reason.SESSION_CHANGED;
                } else {
                    session.setLockState(change == 0 ? LOCKED : UNKNOWN);
                    changed = state(change == 0 ? IslandExportState.LockState.LOCKED : IslandExportState.LockState.UNKNOWN,
                            EXPORT_GENERATION);
                    expected = IslandExportDocument.Reason.LOCK_STATE_CHANGED;
                }
                IslandExportGenerator.Step stopped = generator.renderNextSample(plan, changed);
                assertEquals(expected, stopped.incomplete.reason); assertFalse(stopped.incomplete.meta.complete);
                assertEquals(0, stopped.plan.nextIndex); assertNull(stopped.jsonFragment); assertNull(stopped.markdownFragment);
                assertEquals(expected, generator.renderNextSample(stopped.plan, unlocked()).incomplete.reason);
                assertEquals(expected, generator.finish(stopped.plan, unlocked()).reason);
                gate.close(); idle(session); assertEquals(0, queuedReads.get());
                if (change == 2) {
                    assertTrue(session.records().isEmpty()); assertEquals(2L, session.counters().droppedSessionStale);
                } else {
                    assertEquals(3, session.records().size());
                    assertEquals("prior-capture", session.records().get(0).bodyText);
                    for (int i = 1; i < 3; i++) {
                        IslandCaptureRecord record = session.records().get(i);
                        assertEquals(IslandCaptureRecord.State.LOCKED_PLACEHOLDER, record.state);
                        assertEquals(change == 0 ? IslandCaptureRecord.Reason.LOCKED : IslandCaptureRecord.Reason.LOCK_STATE_UNKNOWN,
                                record.reason); noPrivateFields(record);
                    }
                }
            }
        }
    }

    @Test public void realUnverifiedAndDeniedInputsHaveNoIdentityBeforeAndAfterFullExport() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (IslandCaptureSession session = enabled()) {
            for (IslandCaptureSession.IdentityAuthorization auth : Arrays.asList(UNVERIFIED, DENIED))
                session.offer(PACKAGE, 10, "secret-key", auth, () -> { reads.incrementAndGet(); return fields("secret-body"); });
            idle(session); assertEquals(0, reads.get()); assertEquals(2, session.records().size());
            for (IslandCaptureRecord record : session.records()) {
                assertEquals(IslandCaptureRecord.State.REJECTED, record.state);
                assertEquals(IslandCaptureRecord.Reason.AUTH_UNVERIFIED, record.reason); noPrivateFields(record);
            }
            IslandExportDocument document = export(session.records(), IslandExportRequest.Mode.FULL);
            assertFalse(document.json.contains("secret-key")); assertFalse(document.json.contains("secret-body"));
            for (Map<String, Object> sample : samples(document)) {
                for (Object value : ((Map<?, ?>) sample.get("sbn")).values()) assertNull(value);
                assertNull(sample.get("bodyText")); assertNull(sample.get("rawParamJSON")); assertNull(sample.get("extras"));
            }
        }
    }

    @Test public void publicPlanAndStateExposeNoRenderedFragmentsAndStepFragmentsStayNull() throws Exception {
        try (IslandCaptureSession session = enabled()) {
            offer(session, () -> fields("original-secret")); idle(session);
            IslandExportGenerator generator = new IslandExportGenerator();
            IslandExportGenerator.ExportPlan plan = generator.begin(request(session.records(), IslandExportRequest.Mode.FULL), unlocked());
            // frozenSamples 是合同规定的原记录快照，含正文不代表对外泄露渲染片段。
            assertEquals("original-secret", plan.frozenSamples.get(0).bodyText);
            Set<String> expected = new HashSet<String>(Arrays.asList("frozenSamples", "mode", "frozenSessionGeneration", "nextIndex"));
            for (Field field : IslandExportGenerator.ExportPlan.class.getFields()) {
                assertTrue(Modifier.isFinal(field.getModifiers())); assertTrue(expected.remove(field.getName()));
                assertNotEquals(String.class, field.getType());
            }
            assertTrue(expected.isEmpty());
            expected.addAll(Arrays.asList("lockState", "exportAuth", "fullConfirm", "sessionGeneration"));
            for (Field field : IslandExportState.class.getFields()) {
                assertTrue(Modifier.isFinal(field.getModifiers())); assertTrue(expected.remove(field.getName()));
                assertNotEquals(String.class, field.getType());
            }
            assertTrue(expected.isEmpty());
            IslandExportGenerator.Step rendered = generator.renderNextSample(plan, unlocked());
            assertNull(rendered.jsonFragment); assertNull(rendered.markdownFragment);
            IslandExportGenerator.Step end = generator.renderNextSample(rendered.plan, unlocked());
            assertNull(end.jsonFragment); assertNull(end.markdownFragment);
            IslandExportGenerator.Step stopped = generator.renderNextSample(plan,
                    state(IslandExportState.LockState.UNKNOWN, EXPORT_GENERATION));
            assertNull(stopped.jsonFragment); assertNull(stopped.markdownFragment);
            assertNull(stopped.incomplete.json); assertNull(stopped.incomplete.markdown);
            assertEquals(0, plan.nextIndex); assertEquals(1, rendered.plan.nextIndex);
        }
    }

    @Test public void allRealCaptureStatesExportNotObservedComparisonInBothModes() throws Exception {
        try (IslandCaptureSession session = enabled()) {
            offer(session, () -> fields("captured")); idle(session);
            session.setLockState(LOCKED); offer(session, () -> fields("locked-not-read")); idle(session);
            session.setLockState(UNLOCKED);
            session.offer(PACKAGE, 10, "key", UNVERIFIED, () -> fields("unauthorized-not-read")); idle(session);
            offer(session, () -> { throw new IllegalStateException("private failure content"); }); idle(session);
            List<IslandCaptureRecord> records = session.records();
            assertEquals(4, records.size());
            Set<IslandCaptureRecord.State> kinds = new HashSet<IslandCaptureRecord.State>();
            for (IslandCaptureRecord record : records) kinds.add(record.state);
            assertEquals(new HashSet<IslandCaptureRecord.State>(Arrays.asList(IslandCaptureRecord.State.values())), kinds);
            for (IslandExportRequest.Mode mode : IslandExportRequest.Mode.values()) {
                IslandExportDocument document = export(records, mode);
                for (Map<String, Object> sample : samples(document)) {
                    Map<?, ?> comparison = (Map<?, ?>) sample.get("comparison");
                    assertEquals(4, comparison.size()); assertEquals("not-observed", comparison.get("status"));
                    for (String key : Arrays.asList("before", "after", "changed")) {
                        assertTrue(comparison.containsKey(key)); assertNull(comparison.get(key));
                    }
                }
                assertFalse(document.json.contains("private failure content"));
            }
        }
    }
}
