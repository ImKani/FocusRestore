/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 声明顺序保持不变的不可变规则快照；本类不排序或执行规则。 */
public final class IslandRuleSet {
    public static final int MAX_ACTIVE_RULES = 50;
    public static final int MAX_RULE_UTF8_BYTES = 65536;
    public static final int MAX_TOTAL_UTF8_BYTES = 2 * 1024 * 1024;

    public final long revision;
    public final List<Entry> entries;
    public final int activeRuleCount;
    public final int totalUtf8Bytes;

    public IslandRuleSet(List<Entry> entries) {
        this(0L, entries);
    }

    public IslandRuleSet(long revision, List<Entry> entries) {
        this(revision, entries, false);
    }

    IslandRuleSet(long revision, List<Entry> entries, boolean sharedObjects) {
        if (revision < 0) throw new IllegalArgumentException("revision");
        Set<IslandRule> counted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<Entry> copy = IslandRule.immutableList(entries, "entries");
        Set<String> ids = new HashSet<>();
        int active = 0;
        long bytes = 0;
        for (Entry entry : copy) {
            if (!ids.add(entry.id)) throw new IllegalArgumentException("duplicate rule id");
            if (entry.enabled && ++active > MAX_ACTIVE_RULES) {
                throw new IllegalArgumentException("active rule capacity");
            }
            if (!sharedObjects || counted.add(entry.rule)) bytes += entry.utf8Bytes;
            if (bytes > MAX_TOTAL_UTF8_BYTES) {
                throw new IllegalArgumentException("total rule byte capacity");
            }
        }
        this.revision = revision;
        this.entries = copy;
        this.activeRuleCount = active;
        this.totalUtf8Bytes = (int) bytes;
    }

    public static IslandRuleSet create(List<Entry> entries) {
        return new IslandRuleSet(entries);
    }

    /** priority 属于外部条目，不进入单规则 JSON 协议。 */
    public static final class Entry {
        public final String id;
        public final long revision;
        public final boolean enabled;
        public final int priority;
        public final IslandRule rule;
        public final int utf8Bytes;

        public Entry(String id, long revision, boolean enabled, IslandRule rule, int utf8Bytes) {
            this(id, revision, enabled, 0, rule, utf8Bytes);
        }

        public Entry(String id, long revision, boolean enabled, int priority,
                IslandRule rule, int utf8Bytes) {
            this.id = Objects.requireNonNull(id, "id");
            if (id.length() == 0) throw new IllegalArgumentException("empty rule id");
            if (revision < 0) throw new IllegalArgumentException("revision");
            if (utf8Bytes < 1 || utf8Bytes > MAX_RULE_UTF8_BYTES) {
                throw new IllegalArgumentException("rule byte capacity");
            }
            this.revision = revision;
            this.enabled = enabled;
            this.priority = priority;
            this.rule = Objects.requireNonNull(rule, "rule");
            this.utf8Bytes = utf8Bytes;
        }
    }
}
