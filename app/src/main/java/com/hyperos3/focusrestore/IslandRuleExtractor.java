/* SPDX-License-Identifier: GPL-3.0-only
 * ImKani / FocusRestore / https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** C3-1 只产生绑定文本，不匹配、不清洗、不生成最终正文或 fallback。 */
public final class IslandRuleExtractor {
    public enum Code {
        R_INPUT_LIMIT, R_MISSING_FIELD, R_VALUE_TYPE, R_BUDGET, R_TIMEOUT, R_EXCEPTION,
        L3_REFERENCE, L3_PATH_SCOPE, L3_QUOTA, L3_FORBIDDEN_CAPABILITY
    }
    public static final class Problem {
        public final Code code;
        public final String path;
        public final String reason;
        private Problem(Code code, String path, String reason) {
            this.code = code; this.path = path; this.reason = reason;
        }
    }
    /** 私有解析树不向外暴露；只接受严格 JSON，不能注入任意 Map/对象行为。 */
    public static final class Payload {
        private final Map<?, ?> root;
        private Payload(Map<?, ?> root) { this.root = root; }

        IslandNotificationType notificationType(IslandRuleBudget budget) {
            return IslandRuleContextAdapter.classify(root, budget::checkTime);
        }
    }
    public static final class Preparation {
        public final Payload payload;
        public final Problem failure;
        private Preparation(Payload payload, Problem failure) {
            this.payload = payload; this.failure = failure;
        }
        public boolean isSuccess() { return failure == null; }
    }
    public static final class Result {
        public final Map<String, String> bindings;
        public final List<Problem> omissions;
        public final Problem failure;
        private Result(Map<String, String> bindings, List<Problem> omissions, Problem failure) {
            this.bindings = Collections.unmodifiableMap(new LinkedHashMap<String, String>(bindings));
            this.omissions = Collections.unmodifiableList(new ArrayList<Problem>(omissions));
            this.failure = failure;
        }
        public boolean isSuccess() { return failure == null; }
    }
    private static final String[] CONTAINERS = {"param_v2", "param_v3", "param_voip_v2"};
    private static final Object MISSING = new Object();
    private static final int MAX_BINDING_UNITS = 4096;
    private static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Problem problem;
        Rejected(Problem problem) { super(null, null, false, false); this.problem = problem; }
    }

    public Preparation prepare(String raw, final IslandRuleBudget budget) {
        try {
            if (budget == null) throw reject(Code.R_EXCEPTION, "$", "BUDGET_REQUIRED");
            budget.checkTime();
            if (raw == null || raw.isEmpty()) throw reject(Code.R_MISSING_FIELD, "$", "INPUT_MISSING");
            // 复用通知双上界；不误套规则文件的深度8、字段白名单或64键限制。
            if (!InputLimits.isPayloadAllowed(raw)) throw reject(Code.R_INPUT_LIMIT, "$", "INPUT_CAPACITY");
            Object parsed;
            try {
                parsed = IslandExportJson.parse(raw, new Runnable() {
                    @Override public void run() { budget.checkTime(); }
                });
            } catch (IslandExportJson.Invalid invalid) {
                throw reject(Code.R_VALUE_TYPE, "$", "INVALID_JSON");
            }
            budget.checkTime();
            if (!(parsed instanceof Map)) throw reject(Code.R_VALUE_TYPE, "$", "ROOT_TYPE");
            return new Preparation(new Payload((Map<?, ?>) parsed), null);
        } catch (Rejected failure) { return new Preparation(null, failure.problem); }
        catch (IslandRuleBudget.Limit failure) { return new Preparation(null, limit(failure, "$")); }
        catch (IslandRuleBudget.Cancelled cancelled) { throw cancelled; }
        catch (RuntimeException failure) {
            return new Preparation(null, problem(Code.R_EXCEPTION, "$", "PREPARATION_EXCEPTION"));
        }
    }

    public Result extract(IslandRule rule, Payload payload, IslandRuleBudget budget) {
        List<Problem> omissions = new ArrayList<Problem>();
        String at = "$";
        try {
            if (budget == null) throw reject(Code.R_EXCEPTION, at, "BUDGET_REQUIRED");
            budget.checkTime();
            validate(rule, budget);
            if (payload == null) throw reject(Code.R_MISSING_FIELD, at, "PAYLOAD_MISSING");
            String group = rule.meta.templateType;
            boolean mixed = IslandRulePaths.MIXED_TEMPLATE.equals(group);
            Map<?, ?> selected = mixed || "legacy".equals(group) ? null : selectContainer(payload.root, budget);
            boolean ordinary = !mixed && !"legacy".equals(group) && !"fallback".equals(group);
            Object entrance = null;
            if (ordinary) {
                at = "$.meta.templateType";
                entrance = read(selected, group, budget);
                if (entrance == MISSING || entrance == null)
                    throw reject(Code.R_MISSING_FIELD, at, entrance == null ? "TEMPLATE_NULL" : "TEMPLATE_MISSING");
                if (!(entrance instanceof Map)) throw reject(Code.R_VALUE_TYPE, at, "TEMPLATE_TYPE");
            }
            Map<String, String> values = new LinkedHashMap<String, String>();
            for (int index = 0; index < rule.extract.size(); index++) {
                ExtractSpec binding = rule.extract.get(index);
                at = "$.extract[" + index + "].path";
                String[] segments = binding.path.substring(1).split("/", -1);
                String bindingGroup = mixed ? IslandRulePaths.find(binding.path).templateGroup : group;
                // legacy 只读 raw。其他绑定共用首个选中容器，禁止逐字段去下一个容器找值。
                Object current = payload.root;
                if (!"legacy".equals(bindingGroup)) {
                    if (selected == null) selected = selectContainer(payload.root, budget);
                    current = selected;
                }
                int first = 0;
                // 组入口已为第一绑定完成首段读取；其他绑定仍独立计费，不能共享路径缓存避开预算。
                if (ordinary && index == 0) { current = entrance; first = 1; }
                Problem absent = null;
                for (int step = first; step < segments.length; step++) {
                    if (!(current instanceof Map)) {
                        absent = problem(Code.R_VALUE_TYPE, at, "INTERMEDIATE_TYPE"); break;
                    }
                    current = read((Map<?, ?>) current, segments[step], budget);
                    if (current == MISSING || current == null) {
                        absent = problem(Code.R_MISSING_FIELD, at, current == null ? "VALUE_NULL" : "MEMBER_MISSING"); break;
                    }
                }
                if (absent == null && !(current instanceof String)
                        && !(current instanceof Boolean) && !(current instanceof IslandExportJson.NumberToken))
                    absent = problem(Code.R_VALUE_TYPE, at, "LEAF_TYPE");
                if (absent != null) {
                    if (binding.required) throw new Rejected(absent);
                    omissions.add(absent);
                    continue;
                }
                String text = current instanceof String ? (String) current
                        : current instanceof Boolean ? ((Boolean) current ? "true" : "false")
                        : ((IslandExportJson.NumberToken) current).text;
                // 必须在替换前拒绝非法代理项，optional 也不能用替换修复输入。
                requireUnicode(text, budget, at);
                for (int j = 0; j < binding.replace.size(); j++) {
                    at = "$.extract[" + index + "].replace[" + j + "]";
                    budget.stringOperation();
                    ExtractSpec.Replacement pair = binding.replace.get(j);
                    text = replace(text, pair.from, pair.to, budget, at);
                }
                if (text.length() > MAX_BINDING_UNITS) throw reject(Code.R_BUDGET, at, "BINDING_CAPACITY");
                budget.checkTime();
                values.put(binding.id, text);
            }
            budget.checkTime();
            return new Result(values, omissions, null);
        } catch (Rejected failure) { return failed(omissions, failure.problem); }
        catch (IslandRuleBudget.Limit failure) { return failed(omissions, limit(failure, at)); }
        catch (IslandRuleBudget.Cancelled cancelled) { throw cancelled; }
        catch (RuntimeException failure) {
            return failed(omissions, problem(Code.R_EXCEPTION, at, "EXTRACTION_EXCEPTION"));
        }
    }

    // 预览在有界解析后复用容器选择。执行器仍为每次字段访问计费。
    static Map<?, ?> selectContainer(Map<?, ?> root, IslandRuleBudget budget) {
        for (String key : CONTAINERS) {
            Object candidate = budget == null ? root.get(key) : read(root, key, budget);
            if (candidate instanceof Map) return (Map<?, ?>) candidate;
        }
        return root;
    }

    private static Object read(Map<?, ?> object, String key, IslandRuleBudget budget) {
        budget.readField();
        return object.containsKey(key) ? object.get(key) : MISSING;
    }
    /** 导入只检查有界模型，不读取通知，也不消耗运行期预算。 */
    static Problem validateImportModel(IslandRule rule) {
        try {
            validate(rule, null);
            return null;
        } catch (Rejected failure) {
            return failure.problem;
        }
    }

    /** 导入与运行入口共用防御；运行入口额外检查时间预算。 */
    private static void validate(IslandRule rule, IslandRuleBudget budget) {
        if (rule == null || rule.ruleVersion != IslandRule.CURRENT_VERSION) {
            throw reject(Code.L3_FORBIDDEN_CAPABILITY, "$", "RULE_VERSION");
        }
        if (rule.extract.isEmpty() || rule.extract.size() > 32
                || rule.display.order.isEmpty() || rule.display.order.size() > 16)
            throw reject(Code.L3_QUOTA, "$", "MODEL_COUNT");
        Set<String> ids = new HashSet<String>();
        for (int index = 0; index < rule.extract.size(); index++) {
            if (budget != null) budget.checkTime();
            ExtractSpec binding = rule.extract.get(index);
            String at = "$.extract[" + index + "]";
            if (!identifier(binding.id) || !ids.add(binding.id)) throw reject(Code.L3_REFERENCE, at + ".id", "BINDING_ID");
            IslandRulePaths.Entry entry = binding.path.length() <= 512 ? IslandRulePaths.find(binding.path) : null;
            if (!IslandRulePaths.isRuntimeAllowed(entry) || !IslandRulePaths.matchesTemplate(rule.meta.templateType, entry)
                    || binding.path.split("/", -1).length - 1 > 8)
                throw reject(Code.L3_PATH_SCOPE, at + ".path", "PATH_SCOPE");
            if (binding.replace.size() > 8) throw reject(Code.L3_QUOTA, at + ".replace", "REPLACEMENT_COUNT");
            for (ExtractSpec.Replacement pair : binding.replace) {
                if (pair.from.isEmpty() || pair.from.length() > 128 || pair.to.length() > 128)
                    throw reject(Code.L3_QUOTA, at + ".replace", "REPLACEMENT_SIZE");
                requireUnicode(pair.from, budget, at + ".replace");
                requireUnicode(pair.to, budget, at + ".replace");
            }
        }
        if (rule.display.separator.length() > 32) throw reject(Code.L3_QUOTA, "$.display.separator", "SEPARATOR_SIZE");
        requireUnicode(rule.display.separator, budget, "$.display.separator");
        for (int index = 0; index < rule.display.order.size(); index++) {
            DisplaySpec.Projection projection = rule.display.order.get(index);
            String at = "$.display.order[" + index + "]";
            if (!"title".equals(projection.slot) && !"subtitle".equals(projection.slot)
                    && !"progress".equals(projection.slot) && !"extra".equals(projection.slot))
                throw reject(Code.L3_FORBIDDEN_CAPABILITY, at + ".slot", "SLOT_SCOPE");
            if (!identifier(projection.ref) || !ids.contains(projection.ref))
                throw reject(Code.L3_REFERENCE, at + ".ref", "DANGLING_REFERENCE");
        }
    }
    private static boolean identifier(String text) {
        if (text.isEmpty() || text.length() > 32 || !letter(text.charAt(0))) return false;
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!letter(c) && !(c >= '0' && c <= '9') && c != '_') return false;
        }
        return true;
    }
    private static boolean letter(char c) { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'; }
    private static void requireUnicode(String text, IslandRuleBudget budget, String at) {
        for (int i = 0, work = 0; i < text.length(); i++, work++) {
            if (budget != null && (work & 255) == 0) budget.checkTime();
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1)))
                    throw reject(Code.R_VALUE_TYPE, at, "INVALID_SURROGATE");
                i++;
            } else if (Character.isLowSurrogate(c)) throw reject(Code.R_VALUE_TYPE, at, "INVALID_SURROGATE");
        }
    }
    /** KMP 单次扫描；每次匹配后从下一原始字符继续，不重读替换生成文本。 */
    private static String replace(String input, String from, String to, IslandRuleBudget budget, String at) {
        int[] prefix = new int[from.length()];
        for (int i = 1, matched = 0; i < from.length(); i++) {
            while (matched > 0 && from.charAt(i) != from.charAt(matched)) matched = prefix[matched - 1];
            if (from.charAt(i) == from.charAt(matched)) matched++;
            prefix[i] = matched;
        }
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (int i = 0, matched = 0; i < input.length(); i++) {
            if ((i & 255) == 0) budget.checkTime();
            char c = input.charAt(i);
            while (matched > 0 && c != from.charAt(matched)) matched = prefix[matched - 1];
            if (c == from.charAt(matched)) matched++;
            if (matched == from.length()) {
                int start = i + 1 - from.length();
                append(out, input, last, start, at);
                append(out, to, 0, to.length(), at);
                last = i + 1;
                matched = 0;
            }
        }
        append(out, input, last, input.length(), at);
        budget.checkTime();
        return out.toString();
    }
    private static void append(StringBuilder out, String text, int start, int end, String at) {
        if (end - start > MAX_BINDING_UNITS - out.length()) throw reject(Code.R_BUDGET, at, "BINDING_CAPACITY");
        out.append(text, start, end);
    }
    private static Result failed(List<Problem> omissions, Problem failure) {
        return new Result(Collections.<String, String>emptyMap(), omissions, failure);
    }
    private static Problem problem(Code code, String at, String reason) { return new Problem(code, at, reason); }
    private static Rejected reject(Code code, String at, String reason) { return new Rejected(problem(code, at, reason)); }
    private static Problem limit(IslandRuleBudget.Limit failure, String at) {
        return problem(failure.timeout ? Code.R_TIMEOUT : Code.R_BUDGET, at, failure.reason);
    }
}
