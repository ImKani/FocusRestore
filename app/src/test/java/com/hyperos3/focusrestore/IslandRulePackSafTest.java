/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class IslandRulePackSafTest {
    private static final IslandRuleImportValidator.Environment CURRENT =
            new IslandRuleImportValidator.Environment("17.03.260226.r", 42L);
    private static final IslandRuleImportValidator.Environment OTHER =
            new IslandRuleImportValidator.Environment("17.03.260226.r", 43L);
    private static final IslandRuleRepository.BatchConfirmation REPLACE = IslandRuleRepository.BatchConfirmation.REPLACE_ALL;
    private static final IslandRuleRepository.BatchConfirmation CLEAR = IslandRuleRepository.BatchConfirmation.CLEAR_ALL;
    private static final String SENTINEL = "private-test-content-737";
    private static final String RULE = "{\"ruleVersion\":1,\"meta\":{\"packageName\":\"com.test.pack\",\"templateType\":\"baseInfo\","
            + "\"ruleAuthor\":\"FocusRestore\",\"ruleDescription\":\"\",\"createdAt\":\"2026-11-19T00:00:00Z\"},"
            + "\"match\":{\"all\":[{\"field\":\"packageName\",\"op\":\"equals\",\"value\":\"com.test.pack\"}]},"
            + "\"extract\":[{\"id\":\"field1\",\"path\":\"/baseInfo/title\"}],\"display\":{\"order\":[{\"slot\":\"extra\",\"ref\":\"field1\"}]}}";
    private static final String UNVERIFIED = RULE.replace("\"createdAt\"", "\"protocolVersion\":1,\"createdAt\"");
    private static final String UNSUPPORTED = RULE.replace("\"ruleVersion\":1", "\"ruleVersion\":1,\"x-note\":\"" + SENTINEL + "\"");
    private static final String DOCUMENT_URI = "content://com.android.externalstorage.documents/document/primary%3Arules.frpk";
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void pickerCancelAndInvalidDocumentStayOutOfIo() {
        assertEquals(IslandRulePackSaf.Picker.CANCELLED, IslandRulePackSaf.picker(false, DOCUMENT_URI, true));
        assertEquals(IslandRulePackSaf.Picker.INVALID, IslandRulePackSaf.picker(true, null, false));
        assertEquals(IslandRulePackSaf.Picker.INVALID, IslandRulePackSaf.picker(true, "file:///rules.frpk", true));
        assertEquals(IslandRulePackSaf.Picker.INVALID, IslandRulePackSaf.picker(true, DOCUMENT_URI, false));
        assertEquals(IslandRulePackSaf.Picker.INVALID, IslandRulePackSaf.picker(true, DOCUMENT_URI + "#unexpected", true));
        assertEquals(IslandRulePackSaf.Picker.SELECTED, IslandRulePackSaf.picker(true, DOCUMENT_URI, true));
    }

    @Test public void oversizedAndCorruptInputsKeepOldRevision() throws Exception {
        IslandRuleRepository repository = repository();
        installSingle(repository, RULE);
        IslandRulePack.Failure tooLarge = assertThrows(IslandRulePack.Failure.class, () ->
                prepare(repository, new byte[IslandRulePack.MAX_BYTES + 1]));
        assertEquals(IslandRulePack.Code.PACK_SIZE, tooLarge.code);
        assertTrue(IslandRulePackSaf.failure(tooLarge).contains("2,122,796"));
        byte[] corrupt = pack(item(1, true, 7, RULE));
        corrupt[corrupt.length - 1] ^= 1;
        assertEquals(IslandRulePack.Code.DIGEST, assertThrows(IslandRulePack.Failure.class,
                () -> prepare(repository, corrupt)).code);
        assertEquals(1, repository.revision());
    }

    @Test public void timeoutWhileReadingClosesInputWithoutStagingOrSaving() throws Exception {
        IslandRuleRepository repository = repository();
        AtomicLong clock = new AtomicLong();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        IslandRulePackSaf.Operation operation = new IslandRulePackSaf.Operation(clock::get);
        InputStream stalled = new InputStream() {
            @Override public int read() {
                reads.incrementAndGet();
                clock.set(TimeUnit.MILLISECONDS.toNanos(IslandRulePackSaf.TIMEOUT_MS));
                return 0;
            }
            @Override public int read(byte[] bytes, int offset, int length) { return read() + 1; }
            @Override public void close() { closes.incrementAndGet(); }
        };
        assertEquals(IslandRulePackSaf.Stop.TIMEOUT, assertThrows(IslandRulePackSaf.Stopped.class,
                () -> IslandRulePackSaf.prepare(repository, () -> stalled, () -> CURRENT, operation)).reason);
        assertEquals(1, reads.get());
        assertEquals(1, closes.get());
        assertEquals(0, repository.revision());
    }

    @Test public void confirmationUsesExactValidatedBytesAndSafeOrderedSummary() throws Exception {
        IslandRuleRepository repository = repository();
        byte[] original = bytes(RULE + "\n");
        byte[] source = pack(IslandRulePackTest.entry(2, false, Integer.MIN_VALUE, original), item(1, true, 7, UNVERIFIED));
        AtomicInteger opens = new AtomicInteger();
        try (IslandRuleRepository.PendingBatch candidate = IslandRulePackSaf.prepare(repository, () -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(source);
        }, () -> CURRENT, operation())) {
            String summary = IslandRulePackSaf.summary(candidate);
            assertTrue(summary.contains("规则包 2 条，启用 1 条"));
            assertTrue(summary.contains("1. 禁用 · 优先级 -2147483648\n2. 启用 · 优先级 7 · 兼容性待核实"));
            assertFalse(summary.contains("com.test.pack"));
            assertFalse(summary.contains("/baseInfo/title"));
            assertEquals(0, repository.revision());
            Arrays.fill(source, (byte) 0);
            assertEquals(1, IslandRulePackSaf.commit(repository, candidate, REPLACE, () -> CURRENT, operation()));
        }
        assertEquals(1, opens.get());
        try (IslandRuleRepository.ReadView view = repository.openRead()) {
            assertArrayEquals(original, view.read(view.entries.get(0)));
        }
    }

    @Test public void lateBaselineAndEnvironmentChangesCannotSaveBatch() throws Exception {
        IslandRuleRepository repository = repository();
        byte[] source = pack(item(1, true, 7, RULE));
        try (IslandRuleRepository.PendingBatch candidate = prepare(repository, source)) {
            installSingle(repository, RULE);
            assertEquals(IslandRuleRepository.Code.BASELINE_CHANGED, assertThrows(IslandRuleRepository.Failure.class,
                    () -> IslandRulePackSaf.commit(repository, candidate, REPLACE, () -> CURRENT, operation())).code);
        }
        AtomicInteger environmentReads = new AtomicInteger();
        try (IslandRuleRepository.PendingBatch candidate = prepare(repository, source)) {
            assertEquals(IslandRuleRepository.Code.ENVIRONMENT_CHANGED, assertThrows(IslandRuleRepository.Failure.class,
                    () -> IslandRulePackSaf.commit(repository, candidate, REPLACE,
                            () -> environmentReads.incrementAndGet() == 1 ? CURRENT : OTHER, operation())).code);
            assertEquals(2, environmentReads.get());
        }
        assertEquals(1, repository.revision());
    }

    @Test public void emptyPackCanBeDismissedOrExplicitlyConfirmed() throws Exception {
        IslandRuleRepository repository = repository();
        installSingle(repository, RULE);
        try (IslandRuleRepository.PendingBatch candidate = prepare(repository, pack())) {
            assertTrue(IslandRulePackSaf.summary(candidate).contains("清空全部规则"));
            assertEquals(IslandRuleRepository.Code.VALIDATION, assertThrows(IslandRuleRepository.Failure.class,
                    () -> IslandRulePackSaf.commit(repository, candidate, REPLACE, () -> CURRENT, operation())).code);
        }
        assertEquals(1, repository.revision());
        try (IslandRuleRepository.PendingBatch candidate = prepare(repository, pack())) {
            assertEquals(2, IslandRulePackSaf.commit(repository, candidate, CLEAR, () -> CURRENT, operation()));
        }
        try (IslandRuleRepository.ReadView view = repository.openRead()) {
            assertEquals(2, view.revision);
            assertTrue(view.entries.isEmpty());
        }
    }

    @Test public void rejectedDisabledTailLeavesEarlierValidEntryUncommitted() throws Exception {
        IslandRuleRepository repository = repository();
        installSingle(repository, RULE);
        IslandRuleRepository.BatchRejected rejected = assertThrows(IslandRuleRepository.BatchRejected.class,
                () -> prepare(repository, pack(item(1, true, 0, RULE), item(2, false, 0, UNSUPPORTED))));
        String response = IslandRulePackSaf.failure(rejected);
        assertTrue(response.contains("第 2 条"));
        assertFalse(response.contains(SENTINEL));
        assertEquals(1, repository.revision());
    }

    @Test public void cancellationAtFinalGateRemovesStageAndPreservesEntireOldRevision() throws Exception {
        java.nio.file.Path root = temp.newFolder().toPath().resolve("rules");
        CountDownLatch staging = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        AtomicReference<IslandRulePackSaf.Operation> cancelling = new AtomicReference<>();
        IslandRuleRepository repository = new IslandRuleRepository(root, ignored -> {
            if (cancelling.get() != null && Files.exists(root.resolve("manifest.next"))) {
                staging.countDown();
                await(unblock);
            }
        });
        installSingle(repository, RULE);
        try (IslandRuleRepository.PendingBatch candidate = prepare(repository, pack(item(1, true, 0, RULE), item(2, true, 8, RULE)))) {
            AtomicLong clock = new AtomicLong();
            IslandRulePackSaf.Operation operation = new IslandRulePackSaf.Operation(clock::get);
            cancelling.set(operation);
            FutureTask<IslandRulePackSaf.Stopped> work = background(() -> assertThrows(IslandRulePackSaf.Stopped.class,
                    () -> IslandRulePackSaf.commit(repository, candidate, REPLACE, () -> CURRENT, operation)));
            try {
                await(staging);
                assertTrue(operation.stop(IslandRulePackSaf.Stop.CANCELLED));
                assertFalse(work.isDone());
            } finally {
                unblock.countDown();
            }
            assertEquals(IslandRulePackSaf.Stop.CANCELLED, work.get(5, TimeUnit.SECONDS).reason);
        }
        assertEquals(1, repository.revision());
        assertFalse(Files.exists(root.resolve("manifest.next")));
        try (Stream<java.nio.file.Path> files = Files.list(root)) {
            assertEquals(1, files.filter(path -> path.toString().endsWith(".blob")).count());
        }
    }

    @Test public void unsupportedExportAbortsBeforeTargetAndUsesFixedErrors() throws Exception {
        IslandRuleRepository repository = repository();
        installSingle(repository, RULE);
        installSingle(repository, UNSUPPORTED);
        IOException failure = assertThrows(IOException.class, () -> IslandRulePackSaf.prepareExport(repository, operation()));
        assertFalse(IslandRulePackSaf.failure(failure).contains(SENTINEL));
        assertFalse(IslandRulePackSaf.failure(new IOException(SENTINEL, failure)).contains(SENTINEL));
        assertEquals(2, repository.revision());
    }

    @Test public void documentGetsCompletePackAndItsActualPinnedRevision() throws Exception {
        IslandRuleRepository repository = repository();
        installSingle(repository, RULE);
        IslandRuleRepository.PackExport exported = IslandRulePackSaf.prepareExport(repository, operation());
        installSingle(repository, RULE);
        assertEquals(1, exported.revision);
        assertEquals(2, repository.revision());
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        IslandRulePackSaf.Operation operation = operation();
        IslandRulePackSaf.write(exported, output(written, () -> { throw new AssertionError("discarded valid export"); }), operation);
        assertFalse(operation.stop(IslandRulePackSaf.Stop.CANCELLED));
        assertArrayEquals(exported.bytes, written.toByteArray());
        assertEquals(1, IslandRulePackTest.decode(written.toByteArray()).entries.size());
    }

    @Test public void writeFailureAndCloseFailureMustDiscardAndNeverExposeExceptionText() {
        for (boolean failOnClose : new boolean[] {false, true}) {
            ByteArrayOutputStream partial = new ByteArrayOutputStream();
            AtomicInteger discarded = new AtomicInteger();
            OutputStream output = new OutputStream() {
                @Override public void write(int value) throws IOException {
                    partial.write(value);
                    if (!failOnClose) throw new IOException(SENTINEL);
                }
                @Override public void close() throws IOException { if (failOnClose) throw new IOException(SENTINEL); }
            };
            IslandRulePackSaf.WriteFailure failure = assertThrows(IslandRulePackSaf.WriteFailure.class, () ->
                    IslandRulePackSaf.write(new IslandRuleRepository.PackExport(4, new byte[32]), output(output, () -> {
                        discarded.incrementAndGet();
                        assertTrue(partial.size() > 0);
                        partial.reset();
                        return IslandRulePackSaf.Cleanup.REMOVED;
                    }), operation()));
            assertEquals(1, discarded.get());
            assertEquals(0, partial.size());
            assertFalse(IslandRulePackSaf.failure(failure).contains(SENTINEL));
        }
    }

    @Test public void cancelledOrTimedOutWritesMustDiscardEvenAfterClose() {
        for (IslandRulePackSaf.Stop reason : IslandRulePackSaf.Stop.values()) {
            AtomicLong clock = new AtomicLong();
            IslandRulePackSaf.Operation operation = new IslandRulePackSaf.Operation(clock::get);
            AtomicInteger discarded = new AtomicInteger();
            ByteArrayOutputStream written = new ByteArrayOutputStream() {
                @Override public void close() {
                    if (reason == IslandRulePackSaf.Stop.TIMEOUT) clock.set(TimeUnit.MILLISECONDS.toNanos(IslandRulePackSaf.TIMEOUT_MS));
                    else operation.stop(reason);
                }
            };
            IslandRulePackSaf.WriteFailure failure = assertThrows(IslandRulePackSaf.WriteFailure.class, () ->
                    IslandRulePackSaf.write(new IslandRuleRepository.PackExport(5, new byte[32]), output(written, () -> {
                        discarded.incrementAndGet();
                        return IslandRulePackSaf.Cleanup.UNCONFIRMED;
                    }), operation));
            assertEquals(1, discarded.get());
            assertEquals(reason, failure.stopped);
            assertTrue(IslandRulePackSaf.failure(failure).contains("无法确认清理"));
        }
    }

    @Test public void revokedOrDestroyedPageCannotDisplayLateResults() {
        IslandRulePackSaf.Operation operation = operation();
        assertTrue(operation.canDeliver(true, false));
        assertFalse(operation.canDeliver(false, false));
        assertFalse(operation.canDeliver(true, true));
        assertFalse(IslandRulePackSaf.Operation.canUpdate(true, true));
        assertFalse(IslandRulePackSaf.Operation.canUpdate(false, false));
        operation.stop(IslandRulePackSaf.Stop.CANCELLED);
        assertFalse(operation.canDeliver(true, false));
    }

    @Test public void blockedWriteTimesOutBeforeCloseOrCleanupAndKeepsWorkSlot() throws Exception {
        AtomicLong clock = new AtomicLong();
        IslandRulePackSaf.Operation operation = new IslandRulePackSaf.Operation(clock::get);
        IslandRulePackSaf.Flight flight = new IslandRulePackSaf.Flight();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger discarded = new AtomicInteger();
        OutputStream stream = new OutputStream() {
            @Override public void write(int value) {
                writing.countDown();
                await(unblock);
            }
            @Override public void close() { closed.incrementAndGet(); }
        };
        Object owner = new Object();
        assertTrue(flight.acquire(owner));
        FutureTask<IslandRulePackSaf.WriteFailure> work = background(() -> flight.run(owner, operation, () ->
                assertThrows(IslandRulePackSaf.WriteFailure.class, () ->
                        IslandRulePackSaf.write(new IslandRuleRepository.PackExport(1, new byte[1]), output(stream, () -> {
                            assertEquals(1, closed.get());
                            discarded.incrementAndGet();
                            return IslandRulePackSaf.Cleanup.REMOVED;
                        }), operation))));
        try {
            await(writing);
            assertEquals(IslandRulePackSaf.Deadline.NOT_DUE, operation.deadline());
            expire(clock);
            assertEquals(IslandRulePackSaf.Deadline.EXPIRED, operation.deadline());
            assertEquals("导出未确认，目标文件可能不完整，清理待确认。", IslandRulePackSaf.timeoutText(true));
            assertFalse(work.isDone());
            assertFalse(flight.acquire(new Object()));
            assertEquals(0, closed.get());
            assertEquals(0, discarded.get());
        } finally {
            unblock.countDown();
        }
        assertEquals(IslandRulePackSaf.Stop.TIMEOUT, work.get(5, TimeUnit.SECONDS).stopped);
        assertEquals(1, discarded.get());
        assertFalse(flight.occupied());
        assertEquals(IslandRulePackSaf.Deadline.EXPIRED, operation.deadline());
    }

    @Test public void blockedCleanupTimesOutAndLateResultCannotReplaceStatus() throws Exception {
        AtomicLong clock = new AtomicLong();
        IslandRulePackSaf.Operation operation = new IslandRulePackSaf.Operation(clock::get);
        IslandRulePackSaf.Flight flight = new IslandRulePackSaf.Flight();
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        Object owner = new Object();
        assertTrue(flight.acquire(owner));
        FutureTask<IslandRulePackSaf.WriteFailure> work = background(() -> flight.run(owner, operation, () ->
                assertThrows(IslandRulePackSaf.WriteFailure.class, () ->
                        IslandRulePackSaf.write(new IslandRuleRepository.PackExport(1, new byte[1]), output(new OutputStream() {
                            @Override public void write(int value) throws IOException { throw new IOException("WRITE_FAILED"); }
                        }, () -> {
                            cleaning.countDown();
                            await(unblock);
                            return IslandRulePackSaf.Cleanup.REMOVED;
                        }), operation))));
        try {
            await(cleaning);
            assertTrue(operation.stop(IslandRulePackSaf.Stop.CANCELLED));
            expire(clock);
            assertEquals(IslandRulePackSaf.Deadline.EXPIRED, operation.deadline());
            assertFalse(work.isDone());
            assertFalse(flight.acquire(new Object()));
        } finally {
            unblock.countDown();
        }
        assertEquals(IslandRulePackSaf.Cleanup.REMOVED, work.get(5, TimeUnit.SECONDS).cleanup);
        Object replacement = new Object();
        assertTrue(flight.acquire(replacement));
        flight.release(owner);
        assertTrue(flight.occupied());
        assertFalse(operation.canDeliver(true, false));
        assertFalse(IslandRulePackSaf.Operation.canUpdate(false, false));
        flight.release(replacement);
    }

    @Test public void lateStandaloneCleanupAfterPageDestroyedNeverUpdatesThatPage() throws Exception {
        AtomicLong clock = new AtomicLong();
        IslandRulePackSaf.Operation operation = new IslandRulePackSaf.Operation(clock::get);
        IslandRulePackSaf.Flight flight = new IslandRulePackSaf.Flight();
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        Object pageTask = new Object();
        assertTrue(flight.acquire(pageTask));
        FutureTask<IslandRulePackSaf.Cleanup> work = background(() -> flight.run(pageTask, operation,
                () -> IslandRulePackSaf.discard(output(new ByteArrayOutputStream(), () -> {
                    cleaning.countDown();
                    await(unblock);
                    return IslandRulePackSaf.Cleanup.EMPTIED;
                }))));
        try {
            await(cleaning);
            assertTrue(operation.stop(IslandRulePackSaf.Stop.CANCELLED));
            expire(clock);
            assertFalse(IslandRulePackSaf.Operation.canUpdate(true, true));
            assertFalse(flight.acquire(new Object()));
        } finally {
            unblock.countDown();
        }
        assertEquals(IslandRulePackSaf.Cleanup.EMPTIED, work.get(5, TimeUnit.SECONDS));
        // 即使主线程超时回调未运行，迟到结果也必须根据时钟归类。
        assertEquals(IslandRulePackSaf.Deadline.EXPIRED, operation.deadline());
        assertFalse(flight.occupied());
        assertFalse(IslandRulePackSaf.Operation.canUpdate(true, true));
    }

    @Test public void cancellationAfterSwitchStartsMustReportActualCommittedRevision() throws Exception {
        java.nio.file.Path root = temp.newFolder().toPath().resolve("rules");
        CountDownLatch switching = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        AtomicInteger moves = new AtomicInteger();
        IslandRuleRepository repository = new IslandRuleRepository(root, ignored -> {}, (from, to) -> {
            if (moves.incrementAndGet() == 2) {
                switching.countDown();
                await(unblock);
            }
            Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        });
        installSingle(repository, RULE);
        AtomicLong clock = new AtomicLong();
        IslandRulePackSaf.Operation operation = new IslandRulePackSaf.Operation(clock::get);
        try (IslandRuleRepository.PendingBatch candidate = prepare(repository, pack())) {
            FutureTask<Long> work = background(() -> IslandRulePackSaf.commit(repository, candidate, CLEAR,
                    () -> CURRENT, operation));
            try {
                await(switching);
                assertFalse(operation.stop(IslandRulePackSaf.Stop.CANCELLED));
                expire(clock);
                assertEquals(IslandRulePackSaf.Deadline.COMMIT_PENDING, operation.deadline());
                assertFalse(work.isDone());
            } finally {
                unblock.countDown();
            }
            assertEquals(2L, work.get(5, TimeUnit.SECONDS).longValue());
            assertTrue(operation.canDeliver(true, false));
            assertEquals(2L, repository.revision());
        }
    }

    private static void expire(AtomicLong clock) {
        clock.set(TimeUnit.MILLISECONDS.toNanos(IslandRulePackSaf.TIMEOUT_MS));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue("latch did not open", latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
    }

    private static <T> FutureTask<T> background(java.util.concurrent.Callable<T> work) {
        FutureTask<T> task = new FutureTask<>(work);
        Thread thread = new Thread(task, "fake-rule-pack-provider");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    private IslandRuleRepository repository() throws IOException {
        return new IslandRuleRepository(temp.newFolder().toPath().resolve("rules"), ignored -> {});
    }

    private static IslandRulePackSaf.Operation operation() { return new IslandRulePackSaf.Operation(System::nanoTime); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static IslandRulePack.Entry item(int id, boolean enabled, int priority, String rule) {
        return IslandRulePackTest.entry(id, enabled, priority, bytes(rule));
    }
    private static byte[] pack(IslandRulePack.Entry... items) throws IOException { return IslandRulePack.encode(Arrays.asList(items)); }
    private static IslandRuleRepository.PendingBatch prepare(IslandRuleRepository repo, byte[] input) throws IOException {
        return IslandRulePackSaf.prepare(repo, () -> new ByteArrayInputStream(input), () -> CURRENT, operation());
    }
    private static void installSingle(IslandRuleRepository repo, String rule) throws IOException {
        try (IslandRuleRepository.Pending candidate = repo.prepare(new ByteArrayInputStream(bytes(rule)), null, true, 0, CURRENT)) {
            repo.commit(candidate, false, CURRENT);
        }
    }
    private static IslandRulePackSaf.OutputDocument output(OutputStream out, java.util.function.Supplier<IslandRulePackSaf.Cleanup> cleanup) {
        return new IslandRulePackSaf.OutputDocument() {
            @Override public OutputStream open() { return out; }
            @Override public IslandRulePackSaf.Cleanup discard() { return cleanup.get(); }
        };
    }
}
