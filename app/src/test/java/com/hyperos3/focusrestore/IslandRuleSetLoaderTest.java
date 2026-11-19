/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** C2-1 纯 JVM 加载/排序/缓存测试，不应用 match 或执行 extract/display。 */
public class IslandRuleSetLoaderTest {
    private final IslandRuleSetLoader loader = new IslandRuleSetLoader();

    private static byte[] valid() throws IOException {
        return Files.readAllBytes(Paths.get("docs", "samples", "rule-travel.valid.json"));
    }
    private static byte[] padded(int bytes) throws IOException {
        byte[] fixture = valid();
        assertTrue(bytes >= fixture.length);
        byte[] out = Arrays.copyOf(fixture, bytes);
        Arrays.fill(out, fixture.length, out.length, (byte) ' ');
        return out;
    }
    private static IslandRuleSetLoader.Source source(String id, boolean enabled, int priority,
            final byte[] bytes) {
        return new IslandRuleSetLoader.Source(id, 1L, enabled, priority,
                () -> new ByteArrayInputStream(bytes));
    }
    private static List<String> ids(List<IslandRuleSet.Entry> entries) {
        List<String> ids = new ArrayList<>();
        for (IslandRuleSet.Entry entry : entries) ids.add(entry.id);
        return ids;
    }
    private static void immutable(Runnable action) {
        try { action.run(); fail("mutable collection"); }
        catch (UnsupportedOperationException expected) { }
    }
    private static void decodeFailure(IslandRuleSetLoader.Failure failure, IslandRuleCodec.Code code) {
        assertEquals(IslandRuleSetLoader.Code.DECODE_REJECTED, failure.code);
        assertNotNull(failure.decodeError);
        assertEquals(code, failure.decodeError.code);
    }

    @Test public void loadsSingleRuleFromActualFileAndPreservesExternalMetadata() throws Exception {
        byte[] bytes = valid();
        Path file = Files.createTempFile("focusrestore-c21-", ".json");
        Files.write(file, bytes);
        file.toFile().deleteOnExit();
        IslandRuleSetLoader.Source source = IslandRuleSetLoader.Source.fromFile(
                "travel", 12L, true, 9, file.toFile());
        IslandRuleSetLoader.Snapshot snapshot = loader.load(7L, Collections.singletonList(source));
        assertTrue(snapshot.loaded); assertEquals(7L, snapshot.generation);
        assertEquals(7L, snapshot.ruleSet.revision);
        assertTrue(snapshot.failures.isEmpty());
        IslandRuleSet.Entry entry = snapshot.ruleSet.entries.get(0);
        assertEquals("travel", entry.id); assertEquals(12L, entry.revision);
        assertEquals(9, entry.priority); assertTrue(entry.enabled);
        assertEquals(bytes.length, entry.utf8Bytes);
        assertEquals("com.miui.personalassistant", entry.rule.meta.packageName);
    }

    @Test public void malformedRuleIsSkippedWithL1DetailsAndNextRuleLoads() throws Exception {
        IslandRuleSetLoader.Snapshot result = loader.load(1L, Arrays.asList(
                source("first", true, 0, valid()), source("broken", true, 99,
                        "{\"ruleVersion\":1,".getBytes(StandardCharsets.UTF_8)),
                source("last", true, 0, valid())));
        assertEquals(Arrays.asList("first", "last"), ids(result.ruleSet.entries));
        assertEquals(1, result.failures.size());
        IslandRuleSetLoader.Failure failure = result.failures.get(0);
        assertEquals(1, failure.declarationIndex); assertEquals("broken", failure.ruleId);
        decodeFailure(failure, IslandRuleCodec.Code.L1_JSON);
        assertNotNull(failure.decodeError.path); assertTrue(failure.decodeError.offset >= 0);
    }

    @Test public void unknownVersionsNeverBecomeActiveIncludingDowngrade() throws Exception {
        String json = new String(valid(), StandardCharsets.UTF_8);
        byte[] future = json.replace("\"ruleVersion\": 1", "\"ruleVersion\": 2").getBytes(StandardCharsets.UTF_8);
        byte[] old = json.replace("\"ruleVersion\": 1", "\"ruleVersion\": 0").getBytes(StandardCharsets.UTF_8);
        assertFalse(Arrays.equals(valid(), future)); assertFalse(Arrays.equals(valid(), old));
        IslandRuleSetLoader.Snapshot result = loader.load(1L, Arrays.asList(
                source("future", true, Integer.MAX_VALUE, future),
                source("old", true, 10, old), source("known", true, 0, valid())));
        assertEquals(Collections.singletonList("known"), ids(result.orderedActiveEntries));
        assertEquals(2, result.failures.size());
        for (IslandRuleSetLoader.Failure failure : result.failures) decodeFailure(failure, IslandRuleCodec.Code.L1_VERSION);
    }

    @Test public void singleFileLimitUsesActualBoundedBytesAndClosesRejectedStream() throws Exception {
        final AtomicInteger readBytes = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final byte[] huge = padded(100000);
        IslandRuleSetLoader.Source oversized = new IslandRuleSetLoader.Source("large", 0L, true, () ->
                new ByteArrayInputStream(huge) {
                    @Override public synchronized int read(byte[] buffer, int offset, int length) {
                        int count = super.read(buffer, offset, length);
                        if (count > 0) readBytes.addAndGet(count);
                        return count;
                    }
                    @Override public void close() { closed.incrementAndGet(); }
                });
        IslandRuleSetLoader.Snapshot result = loader.load(1L, Arrays.asList(
                source("exact", true, 0, padded(65536)), oversized, source("after", true, 0, valid())));
        assertEquals(Arrays.asList("exact", "after"), ids(result.ruleSet.entries));
        assertEquals(65536, result.ruleSet.entries.get(0).utf8Bytes);
        assertEquals(65537, readBytes.get()); assertEquals(1, closed.get());
        decodeFailure(result.failures.get(0), IslandRuleCodec.Code.L1_FILE_SIZE);
        IslandRuleSetLoader.Snapshot oneOver = loader.load(1L,
                Collections.singletonList(source("over", true, 0, padded(65537))));
        decodeFailure(oneOver.failures.get(0), IslandRuleCodec.Code.L1_FILE_SIZE);
    }

    @Test public void totalExactlyTwoMiBAcceptsDisabledFilesAndSkipsNext() throws Exception {
        List<IslandRuleSetLoader.Source> sources = new ArrayList<>();
        byte[] full = padded(65536);
        for (int i = 0; i < 33; i++) sources.add(source("r" + i, i == 0, 0, full));
        IslandRuleSetLoader.Snapshot result = loader.load(1L, sources);
        assertEquals(32, result.ruleSet.entries.size());
        assertEquals(2097152, result.ruleSet.totalUtf8Bytes);
        assertEquals(1, result.ruleSet.activeRuleCount);
        assertEquals(IslandRuleSetLoader.Code.SET_BYTE_CAPACITY, result.failures.get(0).code);
        assertEquals(32, result.failures.get(0).declarationIndex);
    }

    @Test public void rejectedByteOverflowDoesNotReserveCapacityForLaterSmallerFile() throws Exception {
        List<IslandRuleSetLoader.Source> sources = new ArrayList<>();
        for (int i = 0; i < 31; i++) sources.add(source("full" + i, false, 0, padded(65536)));
        sources.add(source("partial", false, 0, padded(60000)));
        sources.add(source("doesNotFit", false, Integer.MAX_VALUE, padded(6000)));
        sources.add(source("fits", false, 0, padded(5536)));
        IslandRuleSetLoader.Snapshot result = loader.load(1L, sources);
        assertEquals(2097152, result.ruleSet.totalUtf8Bytes);
        assertEquals("fits", result.ruleSet.entries.get(32).id);
        assertEquals(1, result.failures.size());
        assertEquals("doesNotFit", result.failures.get(0).ruleId);
        assertEquals(IslandRuleSetLoader.Code.SET_BYTE_CAPACITY, result.failures.get(0).code);
    }

    @Test public void fiftyActiveAcceptedBeforeSortingAndDisabledRuleDoesNotUseActiveQuota() throws Exception {
        List<IslandRuleSetLoader.Source> sources = new ArrayList<>();
        byte[] bytes = valid();
        for (int i = 0; i < 50; i++) sources.add(source("active" + i, true, 0, bytes));
        sources.add(source("over", true, Integer.MAX_VALUE, bytes));
        sources.add(source("disabled", false, Integer.MAX_VALUE, bytes));
        IslandRuleSetLoader.Snapshot result = loader.load(1L, sources);
        assertEquals(51, result.ruleSet.entries.size());
        assertEquals(50, result.ruleSet.activeRuleCount);
        assertEquals(50, result.orderedActiveEntries.size());
        assertEquals("active0", result.orderedActiveEntries.get(0).id);
        assertEquals(IslandRuleSetLoader.Code.ACTIVE_RULE_CAPACITY, result.failures.get(0).code);
        assertEquals(50, result.failures.get(0).declarationIndex);
    }

    @Test public void invalidRulesDoNotConsumeActiveQuotaOrTotalBytes() throws Exception {
        List<IslandRuleSetLoader.Source> sources = new ArrayList<>();
        for (int i = 0; i < 50; i++) sources.add(source("bad" + i, true, 0, new byte[0]));
        sources.add(source("valid", true, 0, valid()));
        IslandRuleSetLoader.Snapshot result = loader.load(1L, sources);
        assertEquals(50, result.failures.size());
        assertEquals(1, result.ruleSet.activeRuleCount);
        assertEquals(valid().length, result.ruleSet.totalUtf8Bytes);
    }

    @Test public void sortsPriorityDescendingAndTiesKeepImportOrderAtIntegerExtremes() throws Exception {
        byte[] bytes = valid();
        IslandRuleSetLoader.Snapshot result = loader.load(1L, Arrays.asList(
                source("min", true, Integer.MIN_VALUE, bytes), source("tieFirst", true, 7, bytes),
                source("disabled", false, Integer.MAX_VALUE, bytes),
                source("max", true, Integer.MAX_VALUE, bytes), source("tieSecond", true, 7, bytes),
                new IslandRuleSetLoader.Source("default", 0L, true, () -> new ByteArrayInputStream(bytes))));
        assertEquals(Arrays.asList("max", "tieFirst", "tieSecond", "default", "min"), ids(result.orderedActiveEntries));
        assertEquals(Arrays.asList("min", "tieFirst", "disabled", "max", "tieSecond", "default"), ids(result.ruleSet.entries));
        assertEquals(0, result.ruleSet.entries.get(5).priority);
    }

    @Test public void jsonCannotOverrideExternalPriority() throws Exception {
        String json = new String(valid(), StandardCharsets.UTF_8).replace("\"ruleVersion\": 1", "\"priority\":99,\"ruleVersion\": 1");
        IslandRuleSetLoader.Snapshot result = loader.load(1L, Collections.singletonList(
                source("jsonPriority", true, 0, json.getBytes(StandardCharsets.UTF_8))));
        assertTrue(result.ruleSet.entries.isEmpty());
        decodeFailure(result.failures.get(0), IslandRuleCodec.Code.L1_UNKNOWN_FIELD);
    }

    @Test public void snapshotsAreImmutableAndDetachedFromCallerList() throws Exception {
        List<IslandRuleSetLoader.Source> sources = new ArrayList<>();
        sources.add(source("first", true, 1, valid()));
        sources.add(source("bad", true, 0, new byte[0]));
        IslandRuleSetLoader.Snapshot result = loader.load(1L, sources);
        sources.clear();
        assertEquals(1, result.ruleSet.entries.size()); assertEquals(1, result.failures.size());
        immutable(() -> result.ruleSet.entries.clear());
        immutable(() -> result.orderedActiveEntries.clear());
        immutable(() -> result.failures.clear());
    }

    @Test public void sourceOpenReadCloseAndUncheckedFailuresAreIsolated() throws Exception {
        List<IslandRuleSetLoader.Source> sources = Arrays.asList(
                new IslandRuleSetLoader.Source("open", 0L, true, () -> { throw new IOException("private path"); }),
                new IslandRuleSetLoader.Source("read", 0L, true, () -> new InputStream() {
                    @Override public int read() throws IOException { throw new IOException("private data"); }
                }),
                new IslandRuleSetLoader.Source("close", 0L, true, () -> new ByteArrayInputStream(valid()) {
                    @Override public void close() throws IOException { throw new IOException("private path"); }
                }),
                new IslandRuleSetLoader.Source("unchecked", 0L, true, () -> { throw new IllegalStateException("private data"); }),
                source("after", true, 0, valid()));
        IslandRuleSetLoader.Snapshot result = loader.load(1L, sources);
        assertEquals(Collections.singletonList("after"), ids(result.orderedActiveEntries));
        assertEquals(4, result.failures.size());
        assertEquals(IslandRuleSetLoader.Code.SOURCE_IO, result.failures.get(0).code);
        decodeFailure(result.failures.get(1), IslandRuleCodec.Code.L1_IO);
        assertEquals(IslandRuleSetLoader.Code.SOURCE_IO, result.failures.get(2).code);
        assertEquals(IslandRuleSetLoader.Code.SOURCE_EXCEPTION, result.failures.get(3).code);
        for (IslandRuleSetLoader.Failure failure : result.failures) {
            assertFalse(failure.reason.contains("private"));
        }
    }

    @Test public void missingFileAndInvalidMetadataDoNotPoisonOtherEntries() throws Exception {
        Path missing = Paths.get(System.getProperty("java.io.tmpdir"), "focusrestore-nonexistent-c21", "missing.json");
        assertFalse(Files.exists(missing));
        final AtomicInteger duplicateOpened = new AtomicInteger();
        IslandRuleSetLoader.Snapshot result = loader.load(1L, Arrays.asList(
                null, new IslandRuleSetLoader.Source("", 0L, true, () -> new ByteArrayInputStream(valid())),
                new IslandRuleSetLoader.Source("negative", -1L, true, () -> new ByteArrayInputStream(valid())),
                IslandRuleSetLoader.Source.fromFile("missing", 0L, true, 0, missing.toFile()),
                source("same", true, 0, valid()), new IslandRuleSetLoader.Source("same", 0L, true, () -> {
                    duplicateOpened.incrementAndGet(); return new ByteArrayInputStream(valid());
                }), source("after", true, 0, valid())));
        assertEquals(Arrays.asList("same", "after"), ids(result.ruleSet.entries));
        assertEquals(5, result.failures.size()); assertEquals(0, duplicateOpened.get());
        assertEquals(IslandRuleSetLoader.Code.SOURCE_METADATA, result.failures.get(0).code);
        assertEquals(IslandRuleSetLoader.Code.SOURCE_IO, result.failures.get(3).code);
        assertEquals(IslandRuleSetLoader.Code.DUPLICATE_ID, result.failures.get(4).code);
    }

    @Test public void emptyAndEntirelyInvalidInputsProduceEmptyCompletedSnapshots() throws Exception {
        assertTrue(loader.load(0L, Collections.emptyList()).loaded);
        IslandRuleSetLoader.Snapshot bad = loader.load(1L, Collections.singletonList(source("bad", true, 0, new byte[0])));
        assertTrue(bad.loaded); assertTrue(bad.orderedActiveEntries.isEmpty()); assertEquals(1, bad.failures.size());
        assertEquals(IslandRuleSetLoader.Code.SOURCE_METADATA, loader.load(-1L, null).failures.get(0).code);
    }

    @Test public void cacheReadsAndInvalidationDoNotPerformIOAndRebuildIsExplicit() throws Exception {
        IslandRuleSetCache cache = new IslandRuleSetCache();
        AtomicInteger opens = new AtomicInteger();
        byte[] bytes = valid();
        IslandRuleSetLoader.Source entry = new IslandRuleSetLoader.Source("one", 1L, true, () -> {
            opens.incrementAndGet(); return new ByteArrayInputStream(bytes);
        });
        assertFalse(cache.current().loaded); assertTrue(cache.current().ruleSet.entries.isEmpty());
        assertTrue(cache.rebuild(Collections.singletonList(entry)));
        IslandRuleSetLoader.Snapshot first = cache.current();
        for (int i = 0; i < 100; i++) assertSame(first, cache.current());
        assertEquals(1, opens.get());
        cache.invalidate(); // 同一接口用于规则或相关设置变更；此处不接任何设置源。
        assertFalse(cache.current().loaded); assertTrue(cache.current().orderedActiveEntries.isEmpty());
        assertTrue(cache.current().generation > first.generation); assertEquals(1, opens.get());
        assertTrue(cache.rebuild(Collections.singletonList(source("new", true, 2, bytes))));
        assertEquals(Collections.singletonList("new"), ids(cache.current().orderedActiveEntries));
        assertEquals(Collections.singletonList("one"), ids(first.orderedActiveEntries));
    }

    @Test public void rebuildingWithUnknownVersionDoesNotRetainPriorKnownRule() throws Exception {
        IslandRuleSetCache cache = new IslandRuleSetCache();
        assertTrue(cache.rebuild(Collections.singletonList(source("old", true, 0, valid()))));
        byte[] unknown = new String(valid(), StandardCharsets.UTF_8).replace("\"ruleVersion\": 1", "\"ruleVersion\": 999").getBytes(StandardCharsets.UTF_8);
        assertTrue(cache.rebuild(Collections.singletonList(source("future", true, 0, unknown))));
        assertTrue(cache.current().loaded); assertTrue(cache.current().orderedActiveEntries.isEmpty());
        decodeFailure(cache.current().failures.get(0), IslandRuleCodec.Code.L1_VERSION);
    }

    private static IslandRuleSetLoader.Source blocking(CountDownLatch entered, CountDownLatch release,
            byte[] bytes) {
        return new IslandRuleSetLoader.Source("stale", 0L, true, () -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test release timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IOException("test interrupted");
            }
            return new ByteArrayInputStream(bytes);
        });
    }

    @Test public void invalidationDuringRebuildDiscardsStaleResultWithoutBlockingReads() throws Exception {
        IslandRuleSetCache cache = new IslandRuleSetCache();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        byte[] bytes = valid();
        try {
            Future<Boolean> old = worker.submit(() -> cache.rebuild(Collections.singletonList(blocking(entered, release, bytes))));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(cache.current().loaded);
            cache.invalidate(); long generation = cache.current().generation;
            release.countDown(); assertFalse(old.get(5, TimeUnit.SECONDS));
            assertEquals(generation, cache.current().generation);
            assertFalse(cache.current().loaded); assertTrue(cache.current().orderedActiveEntries.isEmpty());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test public void newerRebuildWinsEvenWhenOlderRebuildFinishesLast() throws Exception {
        IslandRuleSetCache cache = new IslandRuleSetCache();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        byte[] bytes = valid();
        try {
            Future<Boolean> old = worker.submit(() -> cache.rebuild(Collections.singletonList(blocking(entered, release, bytes))));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(cache.rebuild(Collections.singletonList(source("newest", true, 0, bytes))));
            IslandRuleSetLoader.Snapshot newest = cache.current();
            release.countDown(); assertFalse(old.get(5, TimeUnit.SECONDS));
            assertSame(newest, cache.current());
            assertEquals(Collections.singletonList("newest"), ids(cache.current().orderedActiveEntries));
        } finally { release.countDown(); worker.shutdownNow(); }
    }
}
