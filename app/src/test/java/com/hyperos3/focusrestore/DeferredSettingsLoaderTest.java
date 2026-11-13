/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeferredSettingsLoaderTest {
    @Test
    public void schedulerFailureEndsRetryAndNotifiesExhaustionOnce() {
        for (DeferredSettingsLoader.Scheduler scheduler : new DeferredSettingsLoader.Scheduler[] {
                (action, delayMs) -> null,
                (action, delayMs) -> { throw new IllegalStateException("scheduler rejected"); }
        }) {
            AtomicInteger reads = new AtomicInteger();
            AtomicInteger exhausted = new AtomicInteger();
            AtomicReference<String> reportedStage = new AtomicReference<>();
            DeferredSettingsLoader loader = new DeferredSettingsLoader(scheduler,
                    () -> { reads.incrementAndGet(); return false; }, () -> { }, exhausted::incrementAndGet,
                    (stage, failure) -> {
                        reportedStage.set(stage);
                        throw new IllegalStateException("reporter failed");
                    });

            loader.start();
            loader.start();

            assertEquals(0, reads.get());
            assertEquals(1, exhausted.get());
            assertEquals("deferred settings schedule", reportedStage.get());
        }
    }

    @Test
    public void staleSchedulerRejectionAfterCancelDoesNotNotifyExhaustion() {
        AtomicReference<DeferredSettingsLoader> reference = new AtomicReference<>();
        AtomicInteger exhausted = new AtomicInteger();
        DeferredSettingsLoader loader = new DeferredSettingsLoader((action, delayMs) -> {
            reference.get().cancel();
            return null;
        }, () -> false, () -> { }, exhausted::incrementAndGet);
        reference.set(loader);

        loader.start();

        assertEquals(0, exhausted.get());
    }

    @Test
    public void readerRuntimeExceptionUsesRemainingBoundedRetries() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger exhausted = new AtomicInteger();
        AtomicReference<String> reportedStage = new AtomicReference<>();
        DeferredSettingsLoader loader = new DeferredSettingsLoader(scheduler, () -> {
            if (reads.incrementAndGet() == 1) throw new IllegalStateException("temporary provider failure");
            return false;
        }, () -> { }, exhausted::incrementAndGet,
                (stage, failure) -> reportedStage.set(stage));

        loader.start();
        scheduler.advanceBy(60000L);

        assertEquals(6, reads.get());
        assertEquals(1, exhausted.get());
        assertEquals("deferred settings read", reportedStage.get());
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    public void completionCallbackRuntimeExceptionDoesNotEscape() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger exhausted = new AtomicInteger();
        AtomicReference<String> reportedStage = new AtomicReference<>();
        DeferredSettingsLoader loader = new DeferredSettingsLoader(scheduler,
                () -> true,
                () -> { throw new IllegalStateException("installation callback failed"); },
                exhausted::incrementAndGet,
                (stage, failure) -> reportedStage.set(stage));

        loader.start();
        scheduler.advanceBy(1000L);

        assertEquals(1, exhausted.get());
        assertEquals("deferred settings ready callback", reportedStage.get());
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    public void retriesWithBoundedBackoffAndStopsAfterSuccess() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger ready = new AtomicInteger();
        AtomicInteger exhausted = new AtomicInteger();
        DeferredSettingsLoader loader = new DeferredSettingsLoader(scheduler,
                () -> reads.incrementAndGet() >= 3, ready::incrementAndGet, exhausted::incrementAndGet);

        loader.start();
        loader.start();
        assertEquals(1, scheduler.pendingCount());
        scheduler.advanceBy(1000L);
        assertEquals(1, reads.get());
        scheduler.advanceBy(2000L);
        assertEquals(2, reads.get());
        scheduler.advanceBy(3999L);
        assertEquals(2, reads.get());
        scheduler.advanceBy(1L);

        assertEquals(3, reads.get());
        assertEquals(1, ready.get());
        assertEquals(0, exhausted.get());
        assertEquals(0, scheduler.pendingCount());
        scheduler.advanceBy(60000L);
        assertEquals(3, reads.get());
        assertEquals(1, ready.get());
    }

    @Test
    public void exhaustsAfterSixReadsAcrossSixtySeconds() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger ready = new AtomicInteger();
        AtomicInteger exhausted = new AtomicInteger();
        DeferredSettingsLoader loader = new DeferredSettingsLoader(scheduler,
                () -> { reads.incrementAndGet(); return false; },
                ready::incrementAndGet, exhausted::incrementAndGet);

        loader.start();
        scheduler.advanceBy(59999L);
        assertEquals(5, reads.get());
        assertEquals(0, exhausted.get());
        scheduler.advanceBy(1L);

        assertEquals(6, reads.get());
        assertEquals(0, ready.get());
        assertEquals(1, exhausted.get());
        assertEquals(0, scheduler.pendingCount());
        scheduler.advanceBy(60000L);
        assertEquals(6, reads.get());
        assertEquals(1, exhausted.get());
    }

    @Test
    public void cancelIsIdempotentAndInvalidatesPendingCallbacks() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger ready = new AtomicInteger();
        AtomicInteger exhausted = new AtomicInteger();
        DeferredSettingsLoader loader = new DeferredSettingsLoader(scheduler,
                () -> { reads.incrementAndGet(); return true; },
                ready::incrementAndGet, exhausted::incrementAndGet);

        loader.start();
        FakeScheduler.Task pending = scheduler.firstPending();
        loader.cancel();
        loader.cancel();
        pending.runEvenIfCancelled();
        scheduler.advanceBy(60000L);

        assertEquals(0, reads.get());
        assertEquals(0, ready.get());
        assertEquals(0, exhausted.get());
    }

    @Test
    public void cancelDuringReadInvalidatesTheReadResult() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger ready = new AtomicInteger();
        AtomicInteger exhausted = new AtomicInteger();
        AtomicReference<DeferredSettingsLoader> reference = new AtomicReference<>();
        DeferredSettingsLoader loader = new DeferredSettingsLoader(scheduler, () -> {
            reference.get().cancel();
            return true;
        }, ready::incrementAndGet, exhausted::incrementAndGet);
        reference.set(loader);

        loader.start();
        scheduler.advanceBy(1000L);

        assertEquals(0, ready.get());
        assertEquals(0, exhausted.get());
        assertEquals(0, scheduler.pendingCount());
    }

    private static final class FakeScheduler implements DeferredSettingsLoader.Scheduler {
        private final List<Task> tasks = new ArrayList<>();
        private long nowMs;

        @Override
        public DeferredSettingsLoader.Cancellable postDelayed(Runnable action, long delayMs) {
            Task task = new Task(nowMs + delayMs, action);
            tasks.add(task);
            return task;
        }

        int pendingCount() {
            int count = 0;
            for (Task task : tasks) if (!task.cancelled && !task.ran) count++;
            return count;
        }

        Task firstPending() {
            for (Task task : tasks) if (!task.cancelled && !task.ran) return task;
            throw new AssertionError("no pending task");
        }

        void advanceBy(long durationMs) {
            long targetMs = nowMs + durationMs;
            while (true) {
                Task next = null;
                for (Task task : tasks) {
                    if (task.cancelled || task.ran || task.dueMs > targetMs) continue;
                    if (next == null || task.dueMs < next.dueMs) next = task;
                }
                if (next == null) break;
                nowMs = next.dueMs;
                next.run();
            }
            nowMs = targetMs;
        }

        private static final class Task implements DeferredSettingsLoader.Cancellable {
            final long dueMs;
            final Runnable action;
            boolean cancelled;
            boolean ran;

            Task(long dueMs, Runnable action) {
                this.dueMs = dueMs;
                this.action = action;
            }

            @Override public void cancel() { cancelled = true; }

            void run() {
                if (cancelled || ran) return;
                ran = true;
                action.run();
            }

            void runEvenIfCancelled() {
                if (ran) return;
                ran = true;
                action.run();
            }
        }
    }
}
