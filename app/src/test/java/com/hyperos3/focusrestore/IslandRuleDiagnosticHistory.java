/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.hyperos3.focusrestore.IslandRuleSelection.Code;
import com.hyperos3.focusrestore.IslandRuleSelection.Event;
import com.hyperos3.focusrestore.IslandRuleSelection.Phase;

/** 对已结束的执行结果按诊断位置聚合。实例由调用方持有，可显式清理。 */
public final class IslandRuleDiagnosticHistory {
    public static final int MAX_ENTRIES = 64;

    private final LinkedHashMap<Key, Long> entries = new LinkedHashMap<>(MAX_ENTRIES, 1.0f, true);
    private long evictions;
    private long droppedExecutionEvents;

    /** 每次调用按原事件顺序累加。位置索引基于候选顺序，规则集更换后可清理旧统计。 */
    public synchronized void record(IslandRuleExecutor.Result result) {
        Objects.requireNonNull(result, "result");
        droppedExecutionEvents = addSaturating(droppedExecutionEvents, result.droppedEvents);
        // Result 由执行器创建，事件路径只含固定结构及有界声明索引。
        for (Event event : result.events) {
            Key key = new Key(event);
            Long previous = entries.get(key);
            if (previous == null && entries.size() == MAX_ENTRIES) {
                entries.remove(entries.keySet().iterator().next());
                evictions = addSaturating(evictions, 1L);
            }
            entries.put(key, previous == null ? 1L : addSaturating(previous, 1L));
        }
    }

    /** 从最久到最近观察的不可变快照，读取快照不改变淘汰顺序。 */
    public synchronized Snapshot snapshot() {
        List<Entry> records = new ArrayList<>(entries.size());
        for (Map.Entry<Key, Long> item : entries.entrySet()) {
            records.add(new Entry(item.getKey(), item.getValue()));
        }
        return new Snapshot(records, evictions, droppedExecutionEvents);
    }

    public synchronized void clear() {
        entries.clear();
        evictions = 0L;
        droppedExecutionEvents = 0L;
    }

    static long addSaturating(long count, long increment) {
        return Long.MAX_VALUE - count < increment ? Long.MAX_VALUE : count + increment;
    }

    private static final class Key {
        final int entryIndex;
        final Phase phase;
        final Code code;
        final String path;

        Key(Event event) {
            entryIndex = event.entryIndex;
            phase = event.phase;
            code = event.code;
            path = event.path;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return entryIndex == key.entryIndex && phase == key.phase && code == key.code
                    && path.equals(key.path);
        }

        @Override public int hashCode() {
            int hash = 31 * entryIndex + phase.hashCode();
            hash = 31 * hash + code.hashCode();
            return 31 * hash + path.hashCode();
        }
    }

    public static final class Snapshot {
        public final List<Entry> entries;
        public final long evictions;
        public final long droppedExecutionEvents;

        private Snapshot(List<Entry> entries, long evictions, long droppedExecutionEvents) {
            this.entries = Collections.unmodifiableList(entries);
            this.evictions = evictions;
            this.droppedExecutionEvents = droppedExecutionEvents;
        }
    }

    public static final class Entry {
        public final int entryIndex;
        public final Phase phase;
        public final Code code;
        public final String path;
        public final long occurrences;

        private Entry(Key key, long occurrences) {
            this.entryIndex = key.entryIndex;
            this.phase = key.phase;
            this.code = key.code;
            this.path = key.path;
            this.occurrences = occurrences;
        }
    }
}
