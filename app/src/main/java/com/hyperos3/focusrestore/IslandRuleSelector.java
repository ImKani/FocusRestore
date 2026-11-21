/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.List;
import com.hyperos3.focusrestore.IslandRuleSelection.Code;
import com.hyperos3.focusrestore.IslandRuleSelection.Phase;

/** C2-2 只选首个 match 成立的条目，不执行规则内容或内置解析器。 */
public final class IslandRuleSelector {
    /** 保留旧接口；详细诊断由 selectDetailed 返回，不写日志或触发 fallback。 */
    public IslandRuleSet.Entry select(List<IslandRuleSet.Entry> orderedActiveEntries,
            IslandRuleContext context) {
        return selectDetailed(orderedActiveEntries, context).selectedEntry;
    }

    /** 输入已按 priority/声明顺序排列；首个成功即停，不为诊断遍历后续条目。 */
    public IslandRuleSelection selectDetailed(List<IslandRuleSet.Entry> orderedActiveEntries,
            IslandRuleContext context) {
        IslandRuleSelection.Collector diagnostics = new IslandRuleSelection.Collector();
        if (orderedActiveEntries != null && context != null) {
            for (int index = 0; index < orderedActiveEntries.size(); index++) {
                IslandRuleSet.Entry entry = null;
                try {
                    entry = orderedActiveEntries.get(index);
                    if (matchesCandidate(entry, context, index, diagnostics, null)) {
                        diagnostics.add(index, entry.id, Phase.SELECTION, Code.SELECTED, "$");
                        return diagnostics.finish(entry);
                    }
                } catch (RuntimeException rejected) {
                    diagnostics.add(index, entry == null ? null : entry.id,
                            entry == null ? Phase.SELECTION : Phase.MATCH, Code.RULE_EXCEPTION, "$.match");
                }
            }
        }
        diagnostics.add(-1, null, Phase.SELECTION, Code.END_NO_SELECTION, "$");
        return diagnostics.finish(null);
    }

    interface MatchMeter {
        void readField();
        void checkTime();
    }

    static boolean matchesCandidate(IslandRuleSet.Entry entry, IslandRuleContext context, int index,
            IslandRuleSelection.Collector diagnostics, MatchMeter meter) {
        if (entry == null) {
            diagnostics.add(index, null, Phase.SELECTION, Code.INVALID_RULE, "$");
            return false;
        }
        if (!entry.enabled) {
            diagnostics.add(index, entry.id, Phase.SELECTION, Code.SKIPPED_DISABLED, "$");
            return false;
        }
        diagnostics.add(index, entry.id, Phase.SELECTION, Code.CANDIDATE, "$");
        IslandRule rule = entry.rule;
        try {
            boolean versionSupported = rule.ruleVersion == IslandRule.CURRENT_VERSION;
            if (!versionSupported || rule.match.isEmpty() || rule.match.size() > 16) {
                diagnostics.add(index, entry.id, Phase.SEMANTIC, Code.INVALID_RULE,
                        !versionSupported ? "$.ruleVersion" : "$.match.all");
                return false;
            }
            // 语义先于条件短路，避免前一条件不匹配掩盖后面的非法协议操作。
            List<IslandRuleMatchValidator.Problem> problems = new IslandRuleMatchValidator().validate(rule);
            if (!problems.isEmpty()) {
                for (IslandRuleMatchValidator.Problem problem : problems)
                    diagnostics.add(index, entry.id, Phase.SEMANTIC, problem.code, problem.path);
                return false;
            }
        } catch (RuntimeException rejected) {
            diagnostics.add(index, entry.id, Phase.SEMANTIC,
                    meter == null ? Code.RULE_EXCEPTION : Code.R_EXCEPTION, "$.match");
            return false;
        }
        return matches(rule, context, index, entry.id, diagnostics, meter);
    }

    private static boolean matches(IslandRule rule, IslandRuleContext context, int entryIndex,
            String ruleId, IslandRuleSelection.Collector diagnostics, MatchMeter meter) {
        for (int index = 0; index < rule.match.size(); index++) {
            MatchClause clause = rule.match.get(index);
            if (meter != null) meter.readField();
            String actual = resolve(clause, context);
            if (!"equals".equals(clause.op) && !"contains".equals(clause.op)
                    && !"startsWith".equals(clause.op)) {
                throw new IllegalArgumentException("unsupported match operator");
            }
            String path = "$.match.all[" + index + "]";
            if (meter != null) {
                // 仅执行入口限长：条件值沿用规则解码上限；上下文不得超过原始 payload 的字符上限。
                if (clause.value.length() > 256 || (actual != null && actual.length() > 256 * 1024)) {
                    diagnostics.add(entryIndex, ruleId, Phase.MATCH, Code.R_INPUT_LIMIT, path);
                    return false;
                }
            }
            if (actual == null) {
                diagnostics.add(entryIndex, ruleId, Phase.MATCH, Code.FIELD_MISSING, path + ".field");
                return false;
            }
            boolean matched = matches(clause, actual, meter);
            if (meter != null) meter.checkTime();
            if (!matched) {
                diagnostics.add(entryIndex, ruleId, Phase.MATCH, Code.FIELD_MISMATCH, path + ".value");
                return false;
            }
        }
        if (rule.notificationType != null) {
            if (meter != null) meter.readField();
            if (!rule.notificationType.equals(context.notificationType)) {
                diagnostics.add(entryIndex, ruleId, Phase.MATCH, Code.FIELD_MISMATCH, "$.match.notificationType");
                return false;
            }
        }
        return true;
    }

    private static String resolve(MatchClause clause, IslandRuleContext context) {
        switch (clause.field) {
            case "packageName": return context.packageName;
            case "protocolVersion": return context.protocolVersion;
            case "business": return context.business;
            case "scene": return context.scene;
            default: throw new IllegalArgumentException("unsupported match field");
        }
    }

    private static boolean matches(MatchClause clause, String actual, MatchMeter meter) {
        switch (clause.op) {
            case "equals": return actual.equals(clause.value);
            case "contains": return meter == null ? actual.contains(clause.value)
                    : contains(actual, clause.value, meter);
            case "startsWith": return actual.startsWith(clause.value);
            default: throw new IllegalArgumentException("unsupported match operator");
        }
    }

    private static boolean contains(String actual, String expected, MatchMeter meter) {
        if (expected.isEmpty()) return true;
        // 单次比较最多 256 单元，分批检查时间；不在整段 contains 返回后才检查。
        int last = actual.length() - expected.length();
        for (int offset = 0; offset <= last; offset++) {
            if ((offset & 63) == 0) meter.checkTime();
            if (actual.regionMatches(offset, expected, 0, expected.length())) return true;
        }
        return false;
    }
}
