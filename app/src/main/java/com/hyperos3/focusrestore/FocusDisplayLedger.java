/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.HashMap;
import java.util.Map;

/** 保留存活通知的首显记录，容量不能改变到期语义；只有真实删除事件才释放对应代次。 */
final class FocusDisplayLedger {
    interface Clock { long now(); }
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();
    private static final class Window {
        final long generation, started;
        Window(long generation,long started) { this.generation=generation; this.started=started; }
    }
    FocusDisplayLedger(Clock clock) { this.clock=clock; }
    synchronized boolean displayed(String key,long generation) {
        if (key == null || key.isEmpty()) return false;
        Window previous=windows.get(key);
        if (previous != null && previous.generation == generation) return false;
        windows.put(key,new Window(generation,clock.now()));
        return true;
    }
    synchronized long remaining(String key,long generation,long limit) {
        if (limit == Long.MAX_VALUE) return limit;
        Window window=windows.get(key);
        if (window == null || window.generation != generation) return limit;
        long elapsed=Math.max(0L,clock.now()-window.started);
        return elapsed >= limit ? 0L : limit-elapsed;
    }
    synchronized void removed(String key,long generation) {
        Window window=windows.get(key);
        if (window != null && window.generation == generation) windows.remove(key);
    }
    synchronized void clear() { windows.clear(); }
}
