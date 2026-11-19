/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 单次选择的不可变结果；诊断仅含条目身份、固定码与声明路径。 */
public final class IslandRuleSelection {
    public static final int MAX_EVENTS = 128;
    public static final int MAX_RULE_ID_UNITS = 128;
    public final IslandRuleSet.Entry selectedEntry;
    public final List<Event> events;
    public final long droppedEvents;

    public enum Phase { SEMANTIC, MATCH, SELECTION, PREPARATION, EXTRACTION, POST_PROCESS, EXECUTION }
    public enum Code {
        CANDIDATE, FIELD_MISSING, FIELD_MISMATCH, L3_OPERATOR_TYPE, RULE_EXCEPTION,
        SELECTED, END_NO_SELECTION, SKIPPED_DISABLED, INVALID_RULE,
        R_INPUT_LIMIT, R_MISSING_FIELD, R_VALUE_TYPE, R_BUDGET, R_TIMEOUT, R_EXCEPTION,
        L3_REFERENCE, L3_PATH_SCOPE, L3_QUOTA, L3_FORBIDDEN_CAPABILITY,
        R_EMPTY_RESULT, EXECUTED, FALLBACK
    }

    public static final class Event {
        public final int entryIndex;
        public final String ruleId;
        public final Phase phase;
        public final Code code;
        public final String path;

        Event(int entryIndex, String ruleId, Phase phase, Code code, String path) {
            this.entryIndex = entryIndex;
            this.ruleId = ruleId != null && ruleId.length() <= MAX_RULE_ID_UNITS ? ruleId : null;
            this.phase = phase;
            this.code = code;
            this.path = path;
        }
    }

    private IslandRuleSelection(IslandRuleSet.Entry selectedEntry, List<Event> events,
            long droppedEvents) {
        this.selectedEntry = selectedEntry;
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.droppedEvents = droppedEvents;
    }

    /** 每次调用独立收集；不保留历史、不去重，也不为丢弃事件分配对象。 */
    static final class Collector {
        private final List<Event> events = new ArrayList<>();
        private long dropped;

        void add(int index, String ruleId, Phase phase, Code code, String path) {
            if (events.size() < MAX_EVENTS) {
                events.add(new Event(index, ruleId, phase, code, path));
            } else if (dropped < Long.MAX_VALUE) {
                dropped++;
            }
        }

        IslandRuleSelection finish(IslandRuleSet.Entry selectedEntry) {
            return new IslandRuleSelection(selectedEntry, events, dropped);
        }
    }
}
