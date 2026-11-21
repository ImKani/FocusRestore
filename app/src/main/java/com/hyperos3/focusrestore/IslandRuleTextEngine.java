/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Bounded asynchronous rule application with notification-level deadlines and stale-result guards. */
public final class IslandRuleTextEngine implements AutoCloseable {
    public static final long DEFAULT_DEADLINE_MILLIS = 250L;
    public static final int DEFAULT_WORKERS = 2;
    public static final int DEFAULT_QUEUE_CAPACITY = 16;

    public enum Outcome { SUCCESS, FALLBACK, CANCELLED }
    public enum Reason { MATCHED, NO_MATCH_OR_INVALID, DEADLINE, CANCELLED, ENGINE_FAILURE }
    public enum Submission { ACCEPTED, DEDUPLICATED, REJECTED_INVALID, REJECTED_QUEUE_FULL, SHUTDOWN }

    public static final class Result {
        public final Outcome outcome;
        /** Non-null only when a custom rule produced a non-empty display string. */
        public final String customText;
        public final Reason reason;
        /** Fixed-code/path diagnostics from the executor; never contains notification values. */
        public final List<IslandRuleSelection.Event> ruleEvents;
        public final List<IslandRuleContextAdapter.Finding> inputFindings;
        public final String winningRuleId;
        public final long droppedRuleEvents;

        private Result(Outcome outcome, String customText, Reason reason,
                IslandRuleExecutor.Result execution, List<IslandRuleContextAdapter.Finding> findings) {
            this.outcome = outcome;
            this.customText = customText;
            this.reason = reason;
            this.ruleEvents = execution == null ? Collections.<IslandRuleSelection.Event>emptyList()
                    : execution.events;
            this.inputFindings = findings == null
                    ? Collections.<IslandRuleContextAdapter.Finding>emptyList()
                    : Collections.unmodifiableList(new java.util.ArrayList<>(findings));
            this.winningRuleId = execution == null || execution.winningEntry == null
                    ? null : execution.winningEntry.id;
            this.droppedRuleEvents = execution == null ? 0L : execution.droppedEvents;
        }
    }

    public interface Callback { void onResult(Result result); }

    /** Captures immutable identities; isCurrent must compare the live SBN/Entry token by ==. */
    public static final class Request {
        public final String notificationKey;
        public final Object identityToken;
        public final long notificationGeneration;
        public final Object snapshotIdentity;
        public final long snapshotGeneration;
        public final String notificationPackageName;
        private final String rawPayload;
        public final IslandRuleSetLoader.Snapshot snapshot;
        private final BooleanSupplier isCurrent;
        private final java.util.concurrent.Executor mainThreadExecutor;
        private final Callback callback;

        public Request(String notificationKey, Object identityToken, long notificationGeneration,
                IslandRuleSetLoader.Snapshot snapshot, Object snapshotIdentity, long snapshotGeneration,
                String notificationPackageName, String rawPayload, BooleanSupplier isCurrent,
                java.util.concurrent.Executor mainThreadExecutor, Callback callback) {
            this.notificationKey = notificationKey;
            this.identityToken = identityToken;
            this.notificationGeneration = notificationGeneration;
            this.snapshot = snapshot;
            this.snapshotIdentity = snapshotIdentity;
            this.snapshotGeneration = snapshotGeneration;
            this.notificationPackageName = notificationPackageName;
            this.rawPayload = rawPayload;
            this.isCurrent = isCurrent;
            this.mainThreadExecutor = mainThreadExecutor;
            this.callback = callback;
        }
    }

    private static final class DedupeKey {
        final String notificationKey;
        final long notificationGeneration;
        final long snapshotGeneration;
        DedupeKey(Request request) {
            notificationKey = request.notificationKey;
            notificationGeneration = request.notificationGeneration;
            snapshotGeneration = request.snapshotGeneration;
        }
        @Override public int hashCode() {
            int result = notificationKey.hashCode();
            result = 31 * result + (int) (notificationGeneration ^ (notificationGeneration >>> 32));
            return 31 * result + (int) (snapshotGeneration ^ (snapshotGeneration >>> 32));
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof DedupeKey)) return false;
            DedupeKey that = (DedupeKey) other;
            return notificationGeneration == that.notificationGeneration
                    && snapshotGeneration == that.snapshotGeneration
                    && notificationKey.equals(that.notificationKey);
        }
    }

    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor watchdog;
    private final IslandRuleContextAdapter contextAdapter;
    private final IslandRuleExecutor ruleExecutor;
    private final long deadlineNanos;
    private final ConcurrentHashMap<DedupeKey, Job> inFlight = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public IslandRuleTextEngine() {
        this(DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY, DEFAULT_DEADLINE_MILLIS);
    }

    public IslandRuleTextEngine(int workerCount, int queueCapacity, long deadlineMillis) {
        this(workerCount, queueCapacity, deadlineMillis, new IslandRuleContextAdapter(),
                new IslandRuleExecutor());
    }

    IslandRuleTextEngine(int workerCount, int queueCapacity, long deadlineMillis,
            IslandRuleContextAdapter contextAdapter, IslandRuleExecutor ruleExecutor) {
        if (workerCount < 1 || queueCapacity < 1 || deadlineMillis < 1) {
            throw new IllegalArgumentException("positive worker, queue, and deadline limits required");
        }
        this.contextAdapter = Objects.requireNonNull(contextAdapter, "contextAdapter");
        this.ruleExecutor = Objects.requireNonNull(ruleExecutor, "ruleExecutor");
        this.deadlineNanos = TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
        this.workers = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(queueCapacity), namedFactory("IslandRuleText"),
                new ThreadPoolExecutor.AbortPolicy());
        this.watchdog = new ScheduledThreadPoolExecutor(1, namedFactory("IslandRuleDeadline"));
        this.watchdog.setRemoveOnCancelPolicy(true);
    }

    public Submission submit(Request request) {
        if (!validRequest(request)) return Submission.REJECTED_INVALID;
        if (closed.get()) return Submission.SHUTDOWN;
        final DedupeKey key = new DedupeKey(request);
        final Job job = new Job(key, request, System.nanoTime() + deadlineNanos);
        if (inFlight.putIfAbsent(key, job) != null) return Submission.DEDUPLICATED;
        if (closed.get()) {
            inFlight.remove(key, job);
            return Submission.SHUTDOWN;
        }
        try {
            job.watchdogFuture = watchdog.schedule(new Runnable() {
                @Override public void run() { job.finish(Outcome.CANCELLED, Reason.DEADLINE, true); }
            }, deadlineNanos, TimeUnit.NANOSECONDS);
            job.workerFuture = workers.submit(new Runnable() {
                @Override public void run() { runJob(job); }
            });
            if (job.finished.get()) job.workerFuture.cancel(true);
            return Submission.ACCEPTED;
        } catch (RejectedExecutionException rejected) {
            job.discard();
            return closed.get() ? Submission.SHUTDOWN : Submission.REJECTED_QUEUE_FULL;
        }
    }

    /** Cancels only the exact notification and snapshot version that became obsolete. */
    public void cancel(String notificationKey, Object identityToken, long notificationGeneration,
            Object snapshotIdentity, long snapshotGeneration) {
        for (Job job : inFlight.values()) {
            if (job.key.notificationKey.equals(notificationKey)
                    && job.request.identityToken == identityToken
                    && job.key.notificationGeneration == notificationGeneration
                    && job.request.snapshotIdentity == snapshotIdentity
                    && job.key.snapshotGeneration == snapshotGeneration) {
                job.finish(Outcome.CANCELLED, Reason.CANCELLED, true);
            }
        }
    }

    private void runJob(Job job) {
        if (job.expiredOrInvalid()) {
            job.finish(Outcome.CANCELLED, job.expired() ? Reason.DEADLINE : Reason.CANCELLED, true);
            return;
        }
        try {
            IslandRuleContextAdapter.Result adapted = contextAdapter.adapt(
                    job.request.notificationPackageName, job.request.rawPayload, false);
            IslandRuleExecutor.Result executed = ruleExecutor.execute(
                    job.request.snapshot.orderedActiveEntries, adapted.context,
                    job.request.rawPayload, new BooleanSupplier() {
                        @Override public boolean getAsBoolean() { return job.expiredOrInvalid(); }
                    });
            if (job.expired()) {
                job.finish(Outcome.CANCELLED, Reason.DEADLINE, true, null, executed, adapted.findings);
            } else if (!job.current()) {
                job.finish(Outcome.CANCELLED, Reason.CANCELLED, false, null, executed, adapted.findings);
            } else if (executed.status == IslandRuleExecutor.Status.SUCCESS
                    && executed.text != null && !executed.text.isEmpty()) {
                job.finish(Outcome.SUCCESS, Reason.MATCHED, false, executed.text, executed, adapted.findings);
            } else if (executed.status == IslandRuleExecutor.Status.CANCELLED) {
                job.finish(Outcome.CANCELLED, job.expired() ? Reason.DEADLINE : Reason.CANCELLED,
                        true, null, executed, adapted.findings);
            } else {
                job.finish(Outcome.FALLBACK, Reason.NO_MATCH_OR_INVALID, false,
                        null, executed, adapted.findings);
            }
        } catch (RuntimeException failure) {
            job.finish(Outcome.FALLBACK, Reason.ENGINE_FAILURE, false);
        }
    }

    private static boolean isCurrent(BooleanSupplier validity) {
        try { return validity.getAsBoolean(); }
        catch (RuntimeException invalid) { return false; }
    }

    private static boolean validRequest(Request request) {
        return request != null && request.notificationKey != null && !request.notificationKey.isEmpty()
                && request.identityToken != null && request.notificationGeneration >= 0
                && request.snapshot != null && request.snapshot.generation == request.snapshotGeneration
                && request.snapshotIdentity != null
                && request.snapshotGeneration >= 0 && request.notificationPackageName != null
                && !request.notificationPackageName.isEmpty() && request.rawPayload != null
                && !request.rawPayload.isEmpty() && request.rawPayload.length() <= InputLimits.MAX_PAYLOAD_CHARS
                && request.isCurrent != null && request.mainThreadExecutor != null && request.callback != null;
    }

    private static ThreadFactory namedFactory(final String prefix) {
        return new ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger serial =
                    new java.util.concurrent.atomic.AtomicInteger();
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, prefix + "-" + serial.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Job job : inFlight.values()) job.finish(Outcome.CANCELLED, Reason.CANCELLED, true);
        workers.shutdownNow();
        watchdog.shutdownNow();
    }

    private final class Job {
        final DedupeKey key;
        final Request request;
        final long deadlineAt;
        final AtomicBoolean finished = new AtomicBoolean();
        volatile Future<?> workerFuture;
        volatile ScheduledFuture<?> watchdogFuture;

        Job(DedupeKey key, Request request, long deadlineAt) {
            this.key = key;
            this.request = request;
            this.deadlineAt = deadlineAt;
        }
        boolean expired() { return System.nanoTime() - deadlineAt >= 0; }
        boolean current() { return isCurrent(request.isCurrent); }
        boolean expiredOrInvalid() { return finished.get() || expired() || !current(); }

        void finish(Outcome outcome, Reason reason, boolean interrupt) {
            finish(outcome, reason, interrupt, null, null, null);
        }
        void finish(Outcome outcome, Reason reason, boolean interrupt, String text) {
            finish(outcome, reason, interrupt, text, null, null);
        }
        void finish(Outcome outcome, Reason reason, boolean interrupt, String text,
                IslandRuleExecutor.Result execution, List<IslandRuleContextAdapter.Finding> findings) {
            if (!finished.compareAndSet(false, true)) return;
            inFlight.remove(key, this);
            ScheduledFuture<?> timer = watchdogFuture;
            if (timer != null) timer.cancel(false);
            Future<?> task = workerFuture;
            if (interrupt && task != null) task.cancel(true);
            if (!current()) return;
            final Result result = new Result(outcome,
                    outcome == Outcome.SUCCESS ? text : null, reason, execution, findings);
            final BooleanSupplier validity = request.isCurrent;
            final Callback callback = request.callback;
            try {
                request.mainThreadExecutor.execute(new Runnable() {
                    @Override public void run() {
                        if (isCurrent(validity)) callback.onResult(result);
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // The main-thread dispatcher is shutting down; no safe callback thread exists.
            }
        }
        void discard() {
            finished.set(true);
            inFlight.remove(key, this);
            ScheduledFuture<?> timer = watchdogFuture;
            if (timer != null) timer.cancel(false);
        }
    }
}
