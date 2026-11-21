/* SPDX-License-Identifier: GPL-3.0-only
 * ImKani / FocusRestore / https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** D1 原始通知标量适配：不读取规则 meta，不调用内置解析器，不保存原始 payload。 */
public final class IslandRuleContextAdapter {
    public enum Code {
        INPUT_MISSING, INPUT_CAPACITY, INVALID_JSON, ROOT_TYPE,
        FIELD_MISSING, FIELD_NULL, FIELD_TYPE,
        PROTOCOL_NEGATIVE, PROTOCOL_NON_INTEGER, PROTOCOL_RANGE
    }
    public static final class Finding {
        public final Code code;
        public final String path;
        private Finding(Code code, String path) { this.code = code; this.path = path; }
    }
    public static final class Result {
        public final IslandRuleContext context;
        public final List<Finding> findings;
        private Result(IslandRuleContext context, List<Finding> findings) {
            this.context = context;
            this.findings = Collections.unmodifiableList(new ArrayList<Finding>(findings));
        }
    }

    /** raw 是调用方提供的 JSON 字符串；包名只来自参数。无 IO，无任意对象转换。 */
    public Result adapt(String notificationPackageName, String raw) {
        return adapt(notificationPackageName, raw, true);
    }

    Result adapt(String notificationPackageName, String raw, boolean classify) {
        List<Finding> findings = new ArrayList<Finding>();
        String packageName = notificationPackageName;
        if (packageName == null) add(findings, Code.FIELD_MISSING, "notification.packageName");
        if (raw == null || raw.isEmpty()) {
            add(findings, Code.INPUT_MISSING, "$");
            return result(packageName, null, null, null, findings);
        }
        if (!InputLimits.isPayloadAllowed(raw)) {
            add(findings, Code.INPUT_CAPACITY, "$");
            return result(packageName, null, null, null, findings);
        }
        Object parsed;
        // 语法层不套用规则字段/深度8限制；总节点和迭代栈受原始输入长度上界约束。
        try { parsed = IslandExportJson.parse(raw); }
        catch (IslandExportJson.Invalid ignored) {
            add(findings, Code.INVALID_JSON, "$");
            return result(packageName, null, null, null, findings);
        }
        if (!(parsed instanceof Map)) {
            add(findings, Code.ROOT_TYPE, "$");
            return result(packageName, null, null, null, findings);
        }
        Map<?, ?> root = (Map<?, ?>) parsed;
        String protocol = protocol(root, findings);
        String business = string(root, "business", findings);
        String scene = string(root, "scene", findings);
        IslandNotificationType type = classify ? IslandNotificationType.classify(root, protocol, business, scene, null) : null;
        return new Result(new IslandRuleContext(packageName, protocol, business, scene, type), findings);
    }
    static IslandNotificationType classify(Map<?, ?> root, Runnable checkpoint) {
        List<Finding> findings = new ArrayList<>();
        return IslandNotificationType.classify(root, protocol(root, findings),
                string(root, "business", findings), string(root, "scene", findings), checkpoint);
    }

    private Result result(String pkg, String protocol, String business, String scene, List<Finding> findings) {
        return new Result(new IslandRuleContext(pkg, protocol, business, scene), findings);
    }
    private static void add(List<Finding> findings, Code code, String path) {
        // 最多包名1项 + 三个 root 字段各1项；整份 JSON 错误时只有一个根原因。
        findings.add(new Finding(code, path));
    }
    private static boolean available(Map<?, ?> root, String key, List<Finding> findings) {
        if (!root.containsKey(key)) { add(findings, Code.FIELD_MISSING, "$." + key); return false; }
        if (root.get(key) == null) { add(findings, Code.FIELD_NULL, "$." + key); return false; }
        return true;
    }
    private static String string(Map<?, ?> root, String key, List<Finding> findings) {
        if (!available(root, key, findings)) return null;
        Object value = root.get(key);
        if (value instanceof String) return (String) value;
        add(findings, Code.FIELD_TYPE, "$." + key); return null;
    }
    private static String protocol(Map<?, ?> root, List<Finding> findings) {
        if (!available(root, "protocol", findings)) return null;
        Object value = root.get("protocol");
        if (!(value instanceof IslandExportJson.NumberToken)) {
            add(findings, Code.FIELD_TYPE, "$.protocol"); return null;
        }
        String token = ((IslandExportJson.NumberToken) value).text;
        int e = token.indexOf('e');
        if (e < 0) e = token.indexOf('E');
        int end = e < 0 ? token.length() : e;
        int start = token.charAt(0) == '-' ? 1 : 0;
        int dot = token.indexOf('.');
        int fractionalDigits = dot < 0 ? 0 : end - dot - 1;
        StringBuilder coefficient = new StringBuilder(end - start);
        for (int i = start; i < end; i++) if (token.charAt(i) != '.') coefficient.append(token.charAt(i));
        int first = 0;
        while (first < coefficient.length() && coefficient.charAt(first) == '0') first++;
        // 精确零与符号/指数无关；-0 和巨大指数的 0 也规范化为 0。
        if (first == coefficient.length()) return "0";
        if (start != 0) { add(findings, Code.PROTOCOL_NEGATIVE, "$.protocol"); return null; }
        int last = coefficient.length();
        while (coefficient.charAt(last - 1) == '0') last--;
        // 显式指数只累积到 input 长度+11；分数/尾零补偿的绝对值不超过 input 长度。
        // 超大指数不解析成 BigDecimal/大幂，因此不会先展开再检查范围。
        int exponent = e < 0 ? 0 : boundedExponent(token, e + 1);
        exponent += coefficient.length() - last - fractionalDigits;
        if (exponent < 0) { add(findings, Code.PROTOCOL_NON_INTEGER, "$.protocol"); return null; }
        int digits = last - first;
        if (digits + exponent > 10) { add(findings, Code.PROTOCOL_RANGE, "$.protocol"); return null; }
        StringBuilder decimal = new StringBuilder(10);
        decimal.append(coefficient, first, last);
        for (int i = 0; i < exponent; i++) decimal.append('0');
        String normalized = decimal.toString();
        if (normalized.length() == 10 && normalized.compareTo("2147483647") > 0) {
            add(findings, Code.PROTOCOL_RANGE, "$.protocol"); return null;
        }
        return normalized;
    }
    private static int boundedExponent(String token, int index) {
        boolean negative = token.charAt(index) == '-';
        if (negative || token.charAt(index) == '+') index++;
        int cap = token.length() + 11;
        int value = 0;
        for (; index < token.length(); index++) {
            int digit = token.charAt(index) - '0';
            if (value > (cap - digit) / 10) return negative ? -cap : cap;
            value = value * 10 + digit;
        }
        return negative ? -value : value;
    }
}
