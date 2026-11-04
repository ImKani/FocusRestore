/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.LinkedHashMap;
import java.util.Map;

/** 保留真实安装句柄，部分失败后只补装缺失项。 */
final class HookRegistration<T> {
    interface Operation<T> { T install() throws Throwable; }
    private final Map<Object, T> installed = new LinkedHashMap<>();
    private final Map<Object, String> failures = new LinkedHashMap<>();

    synchronized T obtain(Object key, Operation<T> operation) {
        if (installed.containsKey(key)) return installed.get(key);
        try {
            T handle = operation.install();
            if (handle == null) throw new IllegalStateException("installation returned no handle");
            installed.put(key, handle);
            failures.remove(key);
            return handle;
        } catch (Throwable error) {
            failures.put(key, error.toString());
            throw new IllegalStateException("Hook installation failed: " + key, error);
        }
    }

    synchronized void missing(Object key) { failures.put(key, "no matching target"); }
    synchronized void resolved(Object key) { failures.remove(key); }
    synchronized int count() { return installed.size(); }
    synchronized int failedCount() { return failures.size(); }
    synchronized String failures() { return failures.toString(); }
}
