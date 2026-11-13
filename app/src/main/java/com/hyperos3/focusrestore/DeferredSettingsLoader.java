/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

/** Retries a deferred settings read with a bounded startup window. */
final class DeferredSettingsLoader {
    private static final long[] RETRY_DELAYS_MS = {1000L, 2000L, 4000L, 8000L, 15000L, 30000L};

    interface Scheduler {
        Cancellable postDelayed(Runnable action, long delayMs);
    }

    interface Cancellable {
        void cancel();
    }

    interface Reader {
        boolean read();
    }

    interface FailureHandler {
        void onFailure(String stage, RuntimeException failure);
    }

    private static final FailureHandler NO_FAILURE_HANDLER = (stage, failure) -> { };

    private final Object lock = new Object();
    private final Scheduler scheduler;
    private final Reader reader;
    private final Runnable onReady;
    private final Runnable onExhausted;
    private final FailureHandler failureHandler;

    private boolean started;
    private boolean finished;
    private long generation;
    private Cancellable pending;

    DeferredSettingsLoader(Scheduler scheduler, Reader reader,
                           Runnable onReady, Runnable onExhausted) {
        this(scheduler, reader, onReady, onExhausted, NO_FAILURE_HANDLER);
    }

    DeferredSettingsLoader(Scheduler scheduler, Reader reader, Runnable onReady,
                           Runnable onExhausted, FailureHandler failureHandler) {
        if (scheduler == null || reader == null || onReady == null || onExhausted == null
                || failureHandler == null) {
            throw new IllegalArgumentException("Deferred settings loader dependencies must not be null");
        }
        this.scheduler = scheduler;
        this.reader = reader;
        this.onReady = onReady;
        this.onExhausted = onExhausted;
        this.failureHandler = failureHandler;
    }

    /** Starts retrying after one second; repeated starts are ignored. */
    void start() {
        final long token;
        synchronized (lock) {
            if (started || finished) return;
            started = true;
            token = ++generation;
        }
        schedule(token, 0);
    }

    /** Invalidates pending or in-flight results. Safe to call more than once. */
    void cancel() {
        Cancellable toCancel;
        synchronized (lock) {
            if (finished) return;
            finished = true;
            generation++;
            toCancel = pending;
            pending = null;
        }
        if (toCancel != null) {
            try {
                toCancel.cancel();
            } catch (RuntimeException failure) {
                reportFailure("deferred settings cancel", failure);
            }
        }
    }

    private void schedule(long token, int retryIndex) {
        Runnable task = () -> attempt(token, retryIndex);
        Cancellable scheduled;
        try {
            scheduled = scheduler.postDelayed(task, RETRY_DELAYS_MS[retryIndex]);
        } catch (RuntimeException failure) {
            finishAfterScheduleFailure(token, failure);
            return;
        }
        boolean keep;
        boolean rejected = false;
        synchronized (lock) {
            keep = generation == token && !finished && scheduled != null;
            if (keep) {
                pending = scheduled;
            } else if (generation == token && !finished) {
                finished = true;
                generation++;
                rejected = scheduled == null;
            }
        }
        if (!keep && scheduled != null) cancelSafely(scheduled);
        if (rejected) {
            reportFailure("deferred settings schedule", new IllegalStateException("scheduler rejected retry"));
            runExhaustedSafely();
        }
    }

    private void finishAfterScheduleFailure(long token, RuntimeException failure) {
        boolean notify;
        synchronized (lock) {
            notify = generation == token && !finished;
            if (notify) {
                finished = true;
                generation++;
            }
        }
        if (notify) {
            reportFailure("deferred settings schedule", failure);
            runExhaustedSafely();
        }
    }

    private void reportFailure(String stage, RuntimeException failure) {
        try {
            failureHandler.onFailure(stage, failure);
        } catch (RuntimeException ignored) {
            // Diagnostics are best-effort and must not escape onto SystemUI's main thread.
        }
    }

    private void runExhaustedSafely() {
        try {
            onExhausted.run();
        } catch (RuntimeException failure) {
            reportFailure("deferred settings exhausted callback", failure);
        }
    }

    private void cancelSafely(Cancellable cancellable) {
        try {
            cancellable.cancel();
        } catch (RuntimeException failure) {
            reportFailure("deferred settings cancel", failure);
        }
    }

    private void attempt(long token, int retryIndex) {
        synchronized (lock) {
            if (generation != token || finished) return;
            pending = null;
        }

        boolean ready;
        try {
            ready = reader.read();
        } catch (RuntimeException failure) {
            reportFailure("deferred settings read", failure);
            ready = false;
        }
        Runnable callback = null;
        int nextRetry = -1;
        synchronized (lock) {
            if (generation != token || finished) return;
            if (ready) {
                finished = true;
                generation++;
                callback = onReady;
            } else if (retryIndex + 1 >= RETRY_DELAYS_MS.length) {
                finished = true;
                generation++;
                callback = onExhausted;
            } else {
                nextRetry = retryIndex + 1;
            }
        }
        if (callback != null) runCallbackSafely(callback);
        else if (nextRetry >= 0) schedule(token, nextRetry);
    }

    private void runCallbackSafely(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException failure) {
            reportFailure(callback == onExhausted
                    ? "deferred settings exhausted callback"
                    : "deferred settings ready callback", failure);
            if (callback != onExhausted) runExhaustedSafely();
        }
    }
}
