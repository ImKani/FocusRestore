/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import static org.junit.Assert.*;

public class IslandRuleTextEngineTest {
    private static final String RAW = "{\"protocol\":1,\"business\":\"train\",\"scene\":\"arrival\",\"desc1\":\"Platform 4\"}";
    private static final String RULE_JSON = "{\"ruleVersion\":1,\"meta\":{\"packageName\":\"test.app\","
            + "\"templateType\":\"legacy\",\"ruleAuthor\":\"ImKani\",\"ruleDescription\":\"\","
            + "\"createdAt\":\"2026-11-18T00:00:00Z\"},\"match\":{\"all\":["
            + "{\"field\":\"packageName\",\"op\":\"equals\",\"value\":\"test.app\"}]},"
            + "\"extract\":[{\"id\":\"title\",\"path\":\"/desc1\",\"required\":true}],"
            + "\"display\":{\"order\":[{\"slot\":\"title\",\"ref\":\"title\"}]}}";

    @Test public void successfulRulePublishesOnlyCustomText() throws Exception {
        IslandRuleSetLoader.Snapshot snapshot = snapshot(1, RULE_JSON);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<IslandRuleTextEngine.Result> result = new AtomicReference<>();
        try (IslandRuleTextEngine engine = new IslandRuleTextEngine(1, 2, 1000)) {
            assertEquals(IslandRuleTextEngine.Submission.ACCEPTED, engine.submit(request(
                    "notification", new Object(), 1, snapshot, new Object(), 1, RAW, () -> true,
                    Runnable::run, value -> { result.set(value); delivered.countDown(); })));
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
        }
        assertEquals(IslandRuleTextEngine.Outcome.SUCCESS, result.get().outcome);
        assertEquals(IslandRuleTextEngine.Reason.MATCHED, result.get().reason);
        assertEquals("Platform 4", result.get().customText);
        assertEquals("rule", result.get().winningRuleId);
        assertFalse(result.get().ruleEvents.isEmpty());
        for (IslandRuleSelection.Event event : result.get().ruleEvents) {
            assertNotNull(event.code);
            assertNotNull(event.path);
        }
    }

    @Test public void noMatchAndInvalidPayloadReturnFallbackWithoutText() throws Exception {
        IslandRuleSetLoader.Snapshot snapshot = snapshot(1, RULE_JSON);
        assertOutcome(snapshot, "{\"protocol\":1,\"business\":\"other\",\"scene\":\"arrival\"}",
                IslandRuleTextEngine.Outcome.FALLBACK, IslandRuleTextEngine.Reason.NO_MATCH_OR_INVALID);
        assertOutcome(snapshot, "[1]", IslandRuleTextEngine.Outcome.FALLBACK,
                IslandRuleTextEngine.Reason.NO_MATCH_OR_INVALID);
    }

    @Test public void notificationDeadlineIncludesBlockedExecution() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<IslandRuleTextEngine.Result> result = new AtomicReference<>();
        IslandRuleSetLoader.Snapshot snapshot = snapshot(1, RULE_JSON);
        try (IslandRuleTextEngine engine = new IslandRuleTextEngine(1, 1, 100,
                new IslandRuleContextAdapter(), new IslandRuleExecutor())) {
            assertEquals(IslandRuleTextEngine.Submission.ACCEPTED, engine.submit(request(
                    "deadline", new Object(), 1, snapshot, new Object(), 1, RAW,
                    blockingValidity(started, release), Runnable::run,
                    value -> { result.set(value); delivered.countDown(); })));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertTrue(delivered.await(1, TimeUnit.SECONDS));
            release.countDown();
        }
        assertEquals(IslandRuleTextEngine.Outcome.CANCELLED, result.get().outcome);
        assertEquals(IslandRuleTextEngine.Reason.DEADLINE, result.get().reason);
        assertNull(result.get().customText);
    }

    @Test public void boundedWorkerQueueRejectsOverflow() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IslandRuleSetLoader.Snapshot snapshot = snapshot(1, RULE_JSON);
        try (IslandRuleTextEngine engine = new IslandRuleTextEngine(1, 1, 5000)) {
            assertEquals(IslandRuleTextEngine.Submission.ACCEPTED,
                    engine.submit(request("one", new Object(), 1, snapshot, new Object(), 1,
                            RAW, blockingValidity(started, release), Runnable::run, ignored -> { })));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals(IslandRuleTextEngine.Submission.ACCEPTED,
                    engine.submit(request("two", new Object(), 1, snapshot, new Object(), 1,
                            RAW, () -> true, Runnable::run, ignored -> { })));
            assertEquals(IslandRuleTextEngine.Submission.REJECTED_QUEUE_FULL,
                    engine.submit(request("three", new Object(), 1, snapshot, new Object(), 1,
                            RAW, () -> true, Runnable::run, ignored -> { })));
            release.countDown();
        }
    }

    @Test public void duplicateTupleIsDeduplicatedUntilFirstJobCompletes() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IslandRuleSetLoader.Snapshot snapshot = snapshot(4, RULE_JSON);
        Object token = new Object();
        Object snapshotIdentity = new Object();
        try (IslandRuleTextEngine engine = new IslandRuleTextEngine(1, 2, 5000)) {
            IslandRuleTextEngine.Request first = request("same", token, 7, snapshot,
                    snapshotIdentity, 4, RAW, blockingValidity(started, release),
                    Runnable::run, ignored -> { });
            assertEquals(IslandRuleTextEngine.Submission.ACCEPTED, engine.submit(first));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals(IslandRuleTextEngine.Submission.DEDUPLICATED,
                    engine.submit(request("same", new Object(), 7, snapshot,
                            new Object(), 4, RAW, () -> true, Runnable::run, ignored -> { })));
            release.countDown();
        }
    }

    @Test public void explicitCancellationRequiresExactNotificationAndSnapshotIdentity() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IslandRuleSetLoader.Snapshot snapshot = snapshot(2, RULE_JSON);
        Object token = new Object();
        Object snapshotIdentity = new Object();
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<IslandRuleTextEngine.Result> result = new AtomicReference<>();
        try (IslandRuleTextEngine engine = new IslandRuleTextEngine(1, 1, 5000)) {
            assertEquals(IslandRuleTextEngine.Submission.ACCEPTED, engine.submit(request(
                    "cancel", token, 5, snapshot, snapshotIdentity, 2, RAW,
                    blockingValidity(started, release), Runnable::run,
                    value -> { result.set(value); delivered.countDown(); })));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            engine.cancel("cancel", new Object(), 5, snapshotIdentity, 2);
            engine.cancel("cancel", token, 5, new Object(), 2);
            engine.cancel("cancel", token, 5, snapshotIdentity, 1);
            assertEquals(1L, delivered.getCount());
            engine.cancel("cancel", token, 5, snapshotIdentity, 2);
            assertTrue(delivered.await(1, TimeUnit.SECONDS));
            release.countDown();
        }
        assertEquals(IslandRuleTextEngine.Outcome.CANCELLED, result.get().outcome);
        assertEquals(IslandRuleTextEngine.Reason.CANCELLED, result.get().reason);
    }

    @Test public void switchingToCompactSuppressesFullTextAlreadyQueuedOnMain() throws Exception {
        ManualExecutor main = new ManualExecutor();
        CountDownLatch enqueued = new CountDownLatch(1);
        AtomicInteger mode = new AtomicInteger(FocusRestoreSettings.ISLAND_TEXT_MODE_FULL);
        AtomicInteger calls = new AtomicInteger();
        try (IslandRuleTextEngine engine = new IslandRuleTextEngine(1, 1, 1000)) {
            assertEquals(IslandRuleTextEngine.Submission.ACCEPTED, engine.submit(request(
                    "key", new Object(), 1, snapshot(1, RULE_JSON), new Object(), 1, RAW,
                    () -> FocusPriorityPolicy.allowsRuleBody(true, mode.get()),
                    task -> { main.execute(task); enqueued.countDown(); }, ignored -> calls.incrementAndGet())));
            assertTrue(enqueued.await(2, TimeUnit.SECONDS));
            mode.set(FocusRestoreSettings.ISLAND_TEXT_MODE_COMPACT);
            main.runNext();
            assertEquals(0, calls.get());
        }
    }

    @Test public void os4SameCandidateNeedsRebindOnApplyAndRevoke() {
        Object item = new Object(), settings = new Object(), config = new Object();
        assertTrue(HyperOS4FocusController.canReuseContent(item, item, settings, settings,
                config, config, "内置全文", "内置全文"));
        assertFalse(HyperOS4FocusController.canReuseContent(item, item, settings, settings,
                config, config, "内置全文", "所选字段"));
        assertFalse(HyperOS4FocusController.canReuseContent(item, item, settings, settings,
                config, config, "所选字段", "内置全文"));
    }

    @Test public void staleGenerationIdentityOrSnapshotSuppressesQueuedCallback() throws Exception {
        assertStaleCallbackSuppressed(0);
        assertStaleCallbackSuppressed(1);
        assertStaleCallbackSuppressed(2);
        assertStaleCallbackSuppressed(3);
        assertStaleCallbackSuppressed(4);
    }

    private static void assertStaleCallbackSuppressed(int changedField) throws Exception {
        IslandRuleSetLoader.Snapshot snapshot = snapshot(3, RULE_JSON);
        Object token = new Object();
        Object snapshotIdentity = new Object();
        LiveIdentity live = new LiveIdentity("key", token, 9, snapshotIdentity, 3);
        ManualExecutor main = new ManualExecutor();
        CountDownLatch enqueued = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (IslandRuleTextEngine engine = new IslandRuleTextEngine(1, 1, 1000)) {
            assertEquals(IslandRuleTextEngine.Submission.ACCEPTED, engine.submit(request(
                    "key", token, 9, snapshot, snapshotIdentity, 3, RAW,
                    () -> live.matches("key", token, 9, snapshotIdentity, 3),
                    task -> { main.execute(task); enqueued.countDown(); }, ignored -> calls.incrementAndGet())));
            assertTrue(enqueued.await(2, TimeUnit.SECONDS));
            if (changedField == 0) live.key = "replacement";
            if (changedField == 1) live.identity = new Object();
            if (changedField == 2) live.generation++;
            if (changedField == 3) live.snapshotIdentity = new Object();
            if (changedField == 4) live.snapshotGeneration++;
            main.runNext();
            assertEquals(0, calls.get());
        }
    }

    private static void assertOutcome(IslandRuleSetLoader.Snapshot snapshot, String raw,
            IslandRuleTextEngine.Outcome expected, IslandRuleTextEngine.Reason reason) throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<IslandRuleTextEngine.Result> result = new AtomicReference<>();
        try (IslandRuleTextEngine engine = new IslandRuleTextEngine(1, 1, 1000)) {
            assertEquals(IslandRuleTextEngine.Submission.ACCEPTED, engine.submit(request(
                    "fallback", new Object(), 1, snapshot, new Object(), 1, raw, () -> true,
                    Runnable::run, value -> { result.set(value); delivered.countDown(); })));
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
        }
        assertEquals(expected, result.get().outcome);
        assertEquals(reason, result.get().reason);
        assertNull(result.get().customText);
        if ("[1]".equals(raw)) {
            assertEquals(IslandRuleContextAdapter.Code.ROOT_TYPE,
                    result.get().inputFindings.get(0).code);
        }
    }

    private static IslandRuleTextEngine.Request request(String key, Object identity, long generation,
            IslandRuleSetLoader.Snapshot snapshot, Object snapshotIdentity, long snapshotGeneration,
            String raw, java.util.function.BooleanSupplier valid, Executor main, IslandRuleTextEngine.Callback callback) {
        return new IslandRuleTextEngine.Request(key, identity, generation, snapshot, snapshotIdentity,
                snapshotGeneration, "test.app", raw, valid, main, callback);
    }

    private static IslandRuleSetLoader.Snapshot snapshot(long generation, final String json) {
        final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return new IslandRuleSetLoader().load(generation, Collections.singletonList(
                new IslandRuleSetLoader.Source("rule", 1, true, () -> new ByteArrayInputStream(bytes))));
    }

    private static BooleanSupplier blockingValidity(final CountDownLatch started,
            final CountDownLatch release) {
        final AtomicInteger calls = new AtomicInteger();
        return () -> {
            if (calls.getAndIncrement() == 0) {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            return true;
        };
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayBlockingQueue<Runnable> tasks = new ArrayBlockingQueue<>(2);
        @Override public void execute(Runnable task) { assertTrue(tasks.offer(task)); }
        void runNext() throws InterruptedException {
            Runnable task = tasks.poll(1, TimeUnit.SECONDS);
            assertNotNull(task);
            task.run();
        }
    }

    private static final class LiveIdentity {
        volatile String key;
        volatile Object identity;
        volatile long generation;
        volatile Object snapshotIdentity;
        volatile long snapshotGeneration;
        LiveIdentity(String key, Object identity, long generation, Object snapshotIdentity,
                long snapshotGeneration) {
            this.key = key;
            this.identity = identity;
            this.generation = generation;
            this.snapshotIdentity = snapshotIdentity;
            this.snapshotGeneration = snapshotGeneration;
        }
        boolean matches(String expectedKey, Object expectedIdentity, long expectedGeneration,
                Object expectedSnapshotIdentity, long expectedSnapshotGeneration) {
            return key.equals(expectedKey) && identity == expectedIdentity && generation == expectedGeneration
                    && snapshotIdentity == expectedSnapshotIdentity
                    && snapshotGeneration == expectedSnapshotGeneration;
        }
    }
}
