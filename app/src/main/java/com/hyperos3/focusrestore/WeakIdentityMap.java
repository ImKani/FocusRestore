/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Objects;

/** 按对象身份保存挂钩状态。值须避免反向持有键。 */
final class WeakIdentityMap<V> {
    private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
    private final HashMap<Key, V> entries = new HashMap<>();

    synchronized V get(Object owner) {
        drainCollected();
        return owner == null ? null : entries.get(new Key(owner, null));
    }

    boolean containsKey(Object owner) { return get(owner) != null; }

    synchronized void put(Object owner, V value) {
        drainCollected();
        entries.put(new Key(Objects.requireNonNull(owner), collected), Objects.requireNonNull(value));
    }

    synchronized void remove(Object owner) {
        drainCollected();
        if (owner != null) entries.remove(new Key(owner, null));
    }

    private void drainCollected() {
        Reference<?> expired;
        while ((expired = collected.poll()) != null) entries.remove(expired);
    }

    private static final class Key extends WeakReference<Object> {
        private final int hash;

        Key(Object owner, ReferenceQueue<Object> queue) {
            super(owner, queue);
            hash = System.identityHashCode(owner);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true; // 出队时仍能移除已清空的原引用。
            if (!(other instanceof Key)) return false;
            Object owner = get();
            return owner != null && owner == ((Key) other).get();
        }
    }
}
