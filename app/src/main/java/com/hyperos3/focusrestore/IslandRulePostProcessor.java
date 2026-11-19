/* SPDX-License-Identifier: GPL-3.0-only
 * ImKani / FocusRestore / https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** C3-2 正文后处理；不选规则，也不处理回退。 */
public final class IslandRulePostProcessor {
    private static final int MAX_PROJECTIONS = 16;
    private static final int MAX_REF_UNITS = 32;

    public enum Code { R_EMPTY_RESULT, R_BUDGET, R_TIMEOUT, R_VALUE_TYPE, R_EXCEPTION }

    public static final class Result {
        public final String text;
        public final Code code;
        public final String reason;

        private Result(String text, Code code, String reason) {
            this.text = text;
            this.code = code;
            this.reason = reason;
        }

        public boolean isSuccess() { return code == null; }
    }

    /** 接收同一规则成功提取的结果、该规则的 display 和本次执行的共享预算。 */
    public Result process(IslandRuleExtractor.Result extraction, DisplaySpec display,
            IslandRuleBudget budget) {
        if (extraction == null || !extraction.isSuccess())
            return failure(Code.R_EXCEPTION, "POST_PROCESS_INPUT_REQUIRED");
        return processBindings(extraction.bindings, display, budget);
    }

    // 编辑器只预览已投影的登记标量，不需要恢复样本的解析树。
    Result processBindings(Map<String, String> bindings, DisplaySpec display, IslandRuleBudget budget) {
        if (display == null || budget == null)
            return failure(Code.R_EXCEPTION, "POST_PROCESS_INPUT_REQUIRED");
        try {
            budget.checkTime();
            if (display.order.isEmpty() || display.order.size() > MAX_PROJECTIONS
                    || display.separator.length() > InputLimits.MAX_SEPARATOR_CHARS)
                return failure(Code.R_BUDGET, "DISPLAY_CAPACITY");

            budget.stringOperation();
            Set<String> seen = null;
            if (display.dedupe) {
                budget.dedupeOperation();
                seen = new HashSet<String>();
            }

            // 成功提取的绑定最多 4096 单元；最多 16 段和 15 个 32 单元分隔符。
            StringBuilder joined = new StringBuilder();
            for (DisplaySpec.Projection projection : display.order) {
                budget.checkTime();
                if (projection.ref.length() > MAX_REF_UNITS)
                    return failure(Code.R_BUDGET, "DISPLAY_CAPACITY");
                String bound = bindings.get(projection.ref);
                if (bound == null) continue;
                String fragment = normalize(bound, budget);
                if (fragment == null) return failure(Code.R_VALUE_TYPE, "INVALID_SURROGATE");
                if (fragment.isEmpty() || (seen != null && !seen.add(fragment))) continue;
                if (joined.length() != 0) joined.append(display.separator);
                joined.append(fragment);
            }
            String normalized = normalize(joined, budget);
            if (normalized == null) return failure(Code.R_VALUE_TYPE, "INVALID_SURROGATE");
            String text = InputLimits.limitOutput(normalized).trim();
            budget.checkTime();
            return text.isEmpty() ? failure(Code.R_EMPTY_RESULT, "EMPTY_RESULT")
                    : new Result(text, null, null);
        } catch (IslandRuleBudget.Limit limit) {
            return failure(limit.timeout ? Code.R_TIMEOUT : Code.R_BUDGET, limit.reason);
        } catch (IslandRuleBudget.Cancelled cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            return failure(Code.R_EXCEPTION, "POST_PROCESS_EXCEPTION");
        }
    }

    /** 过滤不等价于空白；延后写入分隔空格，同时去除首尾空格。 */
    private static String normalize(CharSequence input, IslandRuleBudget budget) {
        StringBuilder normalized = new StringBuilder(input.length());
        boolean pendingSpace = false;
        for (int index = 0; index < input.length(); index++) {
            if ((index & 255) == 0) budget.checkTime();
            char c = input.charAt(index);
            if (filtered(c)) continue;
            if (whiteSpace(c)) {
                pendingSpace = normalized.length() != 0;
                continue;
            }
            if (Character.isHighSurrogate(c) && (index + 1 == input.length()
                    || !Character.isLowSurrogate(input.charAt(index + 1)))) return null;
            if (Character.isLowSurrogate(c) && (index == 0
                    || !Character.isHighSurrogate(input.charAt(index - 1)))) return null;
            if (pendingSpace) normalized.append(' ');
            normalized.append(c);
            pendingSpace = false;
        }
        return normalized.toString();
    }

    private static boolean filtered(char c) {
        return c == 0x200B || c == 0x2060 || c == 0xFEFF
                || (c <= 0x1F && c != '\t' && c != '\n')
                || (c >= 0x80 && c <= 0x9F);
    }

    private static boolean whiteSpace(char c) {
        return c == '\t' || c == '\n' || c == ' ' || c == 0x00A0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029
                || c == 0x202F || c == 0x205F || c == 0x3000;
    }

    private static Result failure(Code code, String reason) {
        return new Result(null, code, reason);
    }
}
