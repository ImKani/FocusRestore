/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 单个状态栏计时绑定；调度、可见性和代次校验可在 JVM 独立验证。仅在 UI 线程调用。 */
final class FocusTimerTicker {
    interface Scheduler {
        long wallNow();
        long elapsedNow();
        void postDelayed(Runnable task, long delay);
        void remove(Runnable task);
    }

    interface Target {
        boolean isCurrent();
        void show(String text);
    }

    private final IslandClockTimer timer;
    private final Scheduler scheduler;
    private final Target target;
    private final long wallAtBind;
    private final long elapsedAtBind;
    private final Runnable tick = this::render;
    private boolean visible;
    private boolean closed;

    FocusTimerTicker(IslandClockTimer timer, Scheduler scheduler, Target target) {
        this.timer = timer;
        this.scheduler = scheduler;
        this.target = target;
        wallAtBind = scheduler.wallNow();
        elapsedAtBind = scheduler.elapsedNow();
    }

    void setVisible(boolean value) {
        if (closed || visible == value) return;
        visible = value;
        scheduler.remove(tick);
        if (visible) render();
    }

    void render() {
        scheduler.remove(tick);
        if (closed) return;
        // 已移除或复用到另一通知的视图不能被队列里的旧 tick 重新写入。
        if (!target.isCurrent()) { close(); return; }
        long duration = timer.durationAt(wallAtBind,
                Math.max(0L, scheduler.elapsedNow() - elapsedAtBind));
        target.show(timer.format(duration));
        if (!closed && visible && timer.running() && (timer.type > 0 || duration > 0L)) {
            scheduler.postDelayed(tick, 1000L);
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        scheduler.remove(tick);
    }
}
