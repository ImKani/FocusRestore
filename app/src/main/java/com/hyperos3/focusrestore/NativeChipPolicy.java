/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** 仅使用当前宿主原生已激活且未隐藏的chip；不把overflow或未知模型解释为已显示。 */
final class NativeChipPolicy {
    final boolean ready, allowed, important;
    final Set<String> notificationKeys;
    NativeChipPolicy(boolean ready,boolean allowed,boolean important,Set<String> keys) {
        this.ready=ready; this.allowed=allowed; this.important=important;
        this.notificationKeys=Collections.unmodifiableSet(new HashSet<>(keys));
    }
    static NativeChipPolicy unknown() { return new NativeChipPolicy(false,false,false,Collections.emptySet()); }
    boolean allows(String key) { return ready && (!allowed || (!important && !notificationKeys.contains(key))); }
    @Override public boolean equals(Object value) {
        if (!(value instanceof NativeChipPolicy)) return false;
        NativeChipPolicy other=(NativeChipPolicy)value;
        return ready==other.ready && allowed==other.allowed && important==other.important && notificationKeys.equals(other.notificationKeys);
    }
    @Override public int hashCode() { return java.util.Objects.hash(ready,allowed,important,notificationKeys); }
}
