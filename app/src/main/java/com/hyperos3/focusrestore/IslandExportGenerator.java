/* SPDX-License-Identifier: GPL-3.0-only
 * ImKani / FocusRestore / https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hyperos3.focusrestore.IslandExportDocument.Metadata;
import com.hyperos3.focusrestore.IslandExportDocument.Reason;
import com.hyperos3.focusrestore.IslandExportDocument.Redaction;
import com.hyperos3.focusrestore.IslandExportRequest.Mode;
import com.hyperos3.focusrestore.IslandExportState.ExportAuthorization;
import com.hyperos3.focusrestore.IslandExportState.FullConfirmation;
import com.hyperos3.focusrestore.IslandExportState.LockState;

/**
 * C2-4 纯分步生成器；合同为 capture-export-spec.md §3/4/6/10/13 及作者两项接口裁决。
 * 不持有真实状态、回调或系统对象。调用方须在每步前重新读取状态，profile 变化递增代次。
 */
public final class IslandExportGenerator {
    public static final int MAX_SAMPLES = 64;
    public static final int MAX_JSON_BYTES = 2097152;
    public static final int MAX_MARKDOWN_BYTES = 524288;
    private static final String PLACEHOLDER = "<redacted:string>";
    private static final Set<String> WRAPPERS = immutableSet("param_v2", "param_v3", "param_voip_v2");
    private static final Set<String> EXTRA_KEYS = immutableSet("miui.focus.isFocus", "miui.focus.param",
            "miui.focus.param.custom", "miui.focus.ticker", "miui.focus.rv", "miui.focus.rvNight",
            "miui.focus.rvBar", "miui.focus.rvBarNight");
    private static final Set<String> SAFE_TYPES = immutableSet("boolean", "string", "number", "null",
            "missing", "wrong-type", "read-error", "remote-views", "RemoteViews", "object", "unknown");
    private static final Set<String> PREFIXES;
    static {
        Set<String> prefixes = new HashSet<String>();
        for (IslandRulePaths.Entry entry : IslandRulePaths.all()) {
            String path = entry.path;
            while (!path.isEmpty()) {
                prefixes.add(path);
                path = path.substring(0, path.lastIndexOf('/'));
            }
        }
        PREFIXES = Collections.unmodifiableSet(prefixes);
    }
    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(values)));
    }

    /** begin 的拒绝仅携带固定内容无关原因。后续步骤以不完整结果返回同一类原因。 */
    public static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final Reason reason;
        private Rejected(Reason reason) { super(reason.name()); this.reason = reason; }
    }
    public static final class ExportPlan {
        public final List<IslandCaptureRecord> frozenSamples;
        public final Mode mode;
        public final long frozenSessionGeneration;
        public final int nextIndex;
        private final LockState initialLock;
        private final Set<String> allowedPackages;
        private final List<String> jsonSamples;
        private final List<String> markdownSamples;
        private final List<Redaction> redactions;
        private final Reason terminalReason;
        private final String sessionId;
        private ExportPlan(List<IslandCaptureRecord> samples, Mode mode, long generation,
                LockState lock, Set<String> allowed, List<String> json, List<String> markdown,
                List<Redaction> redactions, Reason terminalReason) {
            frozenSamples = Collections.unmodifiableList(new ArrayList<IslandCaptureRecord>(samples));
            this.mode = mode; frozenSessionGeneration = generation; initialLock = lock;
            allowedPackages = allowed; nextIndex = json.size();
            jsonSamples = Collections.unmodifiableList(new ArrayList<String>(json));
            markdownSamples = Collections.unmodifiableList(new ArrayList<String>(markdown));
            this.redactions = Collections.unmodifiableList(new ArrayList<Redaction>(redactions));
            this.terminalReason = terminalReason;
            // 纯生成的本地关联标识，不复用通知 key 或读取时钟/随机设备。
            sessionId = "export-" + generation;
        }
    }
    public static final class Step {
        public final ExportPlan plan;
        public final String jsonFragment;
        public final String markdownFragment;
        public final IslandExportDocument incomplete;
        private Step(ExportPlan plan, String json, String markdown, IslandExportDocument failure) {
            this.plan = plan; jsonFragment = json; markdownFragment = markdown; incomplete = failure;
        }
    }

    public ExportPlan begin(IslandExportRequest request, IslandExportState stateAtBegin) {
        if (request == null || stateAtBegin == null) throw new Rejected(Reason.INVALID_REQUEST);
        Reason invalid = initial(request.mode, stateAtBegin);
        if (invalid != Reason.NONE) throw new Rejected(invalid);
        List<IslandCaptureRecord> samples = new ArrayList<IslandCaptureRecord>();
        if (request.selection == null) {
            if (request.records.size() > MAX_SAMPLES) throw new Rejected(Reason.SAMPLE_LIMIT);
            samples.addAll(request.records);
        } else {
            if (request.selection.size() > MAX_SAMPLES) throw new Rejected(Reason.SAMPLE_LIMIT);
            Set<Integer> seen = new HashSet<Integer>();
            for (Integer index : request.selection) {
                if (index == null || index < 0 || index >= request.records.size() || !seen.add(index))
                    throw new Rejected(Reason.INVALID_REQUEST);
                samples.add(request.records.get(index));
            }
        }
        for (IslandCaptureRecord record : samples) {
            if (!validRecord(record)) throw new Rejected(Reason.INVALID_REQUEST);
            if (request.mode == Mode.REDACTED && stateAtBegin.lockState != LockState.UNLOCKED
                    && record.state != IslandCaptureRecord.State.CAPTURED
                    && record.state != IslandCaptureRecord.State.LOCKED_PLACEHOLDER)
                throw new Rejected(Reason.INVALID_REQUEST);
            if (request.mode == Mode.FULL && record.state == IslandCaptureRecord.State.CAPTURED
                    && !request.allowedPackages.contains(record.packageName)) throw new Rejected(Reason.UNAUTHORIZED);
        }
        return new ExportPlan(samples, request.mode, stateAtBegin.sessionGeneration, stateAtBegin.lockState,
                request.allowedPackages, Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<Redaction>emptyList(), Reason.NONE);
    }

    public Step renderNextSample(ExportPlan plan, IslandExportState stateNow) {
        if (plan == null) throw new Rejected(Reason.INVALID_REQUEST);
        Reason reason = current(plan, stateNow);
        if (reason != Reason.NONE) return failedStep(plan, reason);
        if (plan.nextIndex >= plan.frozenSamples.size()) return new Step(plan, null, null, null);
        try {
            List<Redaction> changes = new ArrayList<Redaction>();
            Map<String, Object> sample = project(plan, plan.frozenSamples.get(plan.nextIndex), changes);
            sample.put("redactions", redactionMaps(changes));
            String json = IslandExportJson.write(sample, MAX_JSON_BYTES);
            String markdown;
            try { markdown = markdownSample(sample, plan.nextIndex, changes.size()); }
            catch (IslandExportJson.Capacity limit) { return failedStep(plan, Reason.MARKDOWN_CAPACITY); }
            List<String> jsonSamples = new ArrayList<String>(plan.jsonSamples); jsonSamples.add(json);
            List<String> markdownSamples = new ArrayList<String>(plan.markdownSamples); markdownSamples.add(markdown);
            List<Redaction> allChanges = new ArrayList<Redaction>(plan.redactions); allChanges.addAll(changes);
            ExportPlan next = new ExportPlan(plan.frozenSamples, plan.mode, plan.frozenSessionGeneration,
                    plan.initialLock, plan.allowedPackages, jsonSamples, markdownSamples, allChanges, Reason.NONE);
            // 中途也按完整包头/包尾计量；超额不交付部分文档。
            try { jsonDocument(next, true, Reason.NONE); }
            catch (IslandExportJson.Capacity limit) { return failedStep(plan, Reason.JSON_CAPACITY); }
            try { markdownDocument(next, true, Reason.NONE); }
            catch (IslandExportJson.Capacity limit) { return failedStep(plan, Reason.MARKDOWN_CAPACITY); }
            // 片段只留在不可变 plan 的私有部分；finish 全量容量检查通过后才交付可读文档。
            return new Step(next, null, null, null);
        } catch (Rejected rejected) { return failedStep(plan, rejected.reason); }
        catch (IslandExportJson.Capacity limit) { return failedStep(plan, Reason.JSON_CAPACITY); }
        catch (RuntimeException failure) { return failedStep(plan, Reason.INTERNAL_ERROR); }
    }

    public IslandExportDocument finish(ExportPlan plan, IslandExportState stateAtFinish) {
        if (plan == null) throw new Rejected(Reason.INVALID_REQUEST);
        Reason reason = current(plan, stateAtFinish);
        if (reason == Reason.NONE && plan.nextIndex != plan.frozenSamples.size()) reason = Reason.NOT_FINISHED;
        if (reason != Reason.NONE) return incomplete(plan, reason);
        String json;
        try { json = jsonDocument(plan, true, Reason.NONE); }
        catch (IslandExportJson.Capacity failure) { return incomplete(plan, Reason.JSON_CAPACITY); }
        String markdown;
        try { markdown = markdownDocument(plan, true, Reason.NONE); }
        catch (IslandExportJson.Capacity failure) { return incomplete(plan, Reason.MARKDOWN_CAPACITY); }
        return new IslandExportDocument(json, markdown, metadata(plan, true, Reason.NONE), plan.redactions, Reason.NONE);
    }
    private Step failedStep(ExportPlan plan, Reason reason) {
        ExportPlan terminal = new ExportPlan(plan.frozenSamples, plan.mode, plan.frozenSessionGeneration,
                plan.initialLock, plan.allowedPackages, plan.jsonSamples, plan.markdownSamples, plan.redactions, reason);
        return new Step(terminal, null, null, incomplete(terminal, reason));
    }
    private IslandExportDocument incomplete(ExportPlan plan, Reason reason) {
        // 不完整输出不返回部分文档；也不声称删除调用方此前保存的外部副本。
        return new IslandExportDocument(null, null, metadata(plan, false, reason),
                Collections.<Redaction>emptyList(), reason);
    }
    private Metadata metadata(ExportPlan plan, boolean complete, Reason reason) {
        return new Metadata(plan.mode, complete, complete ? plan.frozenSamples.size() : plan.nextIndex,
                plan.sessionId, false, reason);
    }
    private Reason initial(Mode mode, IslandExportState state) {
        if (state.exportAuth != ExportAuthorization.AUTHORIZED) return Reason.UNAUTHORIZED;
        if (mode == Mode.FULL) {
            if (state.lockState != LockState.UNLOCKED) return Reason.FULL_LOCKED;
            if (state.fullConfirm != FullConfirmation.CONFIRMED) return Reason.FULL_NOT_CONFIRMED;
        }
        return Reason.NONE;
    }
    private Reason current(ExportPlan plan, IslandExportState state) {
        if (plan.terminalReason != Reason.NONE) return plan.terminalReason;
        if (state == null) return Reason.INVALID_REQUEST;
        if (state.sessionGeneration != plan.frozenSessionGeneration) return Reason.SESSION_CHANGED;
        if (state.exportAuth != ExportAuthorization.AUTHORIZED) return Reason.UNAUTHORIZED;
        if (state.lockState != plan.initialLock) return Reason.LOCK_STATE_CHANGED;
        if (plan.mode == Mode.FULL && state.fullConfirm != FullConfirmation.CONFIRMED) return Reason.CONFIRMATION_LOST;
        return Reason.NONE;
    }
    private boolean validRecord(IslandCaptureRecord record) {
        if (record == null || record.id == null || record.state == null) return false;
        if (record.state == IslandCaptureRecord.State.CAPTURED)
            return record.reason == null && record.packageName != null && record.userId != null
                    && record.notificationKey != null && (record.bodyText != null || validSourceKey(record.sourceKey))
                    && record.rawParamJSON != null && record.extrasJson != null;
        return record.reason != null && record.packageName == null && record.userId == null
                && record.notificationKey == null && record.bodyText == null
                && record.rawParamJSON == null && record.extrasJson == null;
    }

    private Map<String, Object> project(ExportPlan plan, IslandCaptureRecord record, List<Redaction> changes) {
        String path = "/samples/" + plan.nextIndex;
        boolean redact = plan.mode == Mode.REDACTED;
        Map<String, Object> sample = map("id", record.id, "capturedAt", Instant.ofEpochMilli(record.capturedAt).toString(),
                "sequence", record.sequence, "state", record.state.name(), "reason", record.reason == null ? null : record.reason.name());
        Map<String, Object> sbn = map("packageName", record.packageName, "userId", record.userId, "key", record.notificationKey);
        if (redact) {
            if (record.packageName != null && !plan.allowedPackages.contains(record.packageName))
                sbn.put("packageName", replacement(record.packageName, path + "/sbn/packageName", changes, "package-not-allowlisted"));
            if (record.userId != null) sbn.put("userId", replacement(record.userId, path + "/sbn/userId", changes, "identity"));
            if (record.notificationKey != null) sbn.put("key", replacement(record.notificationKey, path + "/sbn/key", changes, "identity"));
        }
        sample.put("sbn", sbn);
        sample.put("bodyText", redact && record.bodyText != null
                ? replacement(record.bodyText, path + "/bodyText", changes, "free-text") : record.bodyText);
        sample.put("extras", record.extrasJson == null ? null : extras(record.extrasJson, redact, path + "/extras", changes));
        if (record.rawParamJSON == null) sample.put("rawParamJSON", null);
        else {
            String raw = record.rawParamJSON;
            String value = raw;
            String representation = "original-json-text";
            if (redact) {
                representation = "redacted-json-text";
                try {
                    Object parsed = IslandExportJson.parse(raw);
                    Object redacted = redactTree(parsed, path + "/rawParamJSON/value", changes, record.sourceKey != null);
                    if (record.sourceKey != null) sample.put("ruleMatch", ruleMatch(record.packageName, parsed));
                    value = IslandExportJson.write(redacted, MAX_JSON_BYTES);
                    if (!raw.equals(value)) change(changes, path + "/rawParamJSON/value", "rewrite-json", raw, value, "redacted-representation", false);
                } catch (IslandExportJson.Invalid failure) {
                    value = PLACEHOLDER;
                    change(changes, path + "/rawParamJSON/value", "replace", raw, value, "invalid-json", true);
                }
            }
            sample.put("rawParamJSON", map("sourceKey", validSourceKey(record.sourceKey) ? record.sourceKey : null, "representation", representation, "value", value,
                    "originalUtf16Length", raw.length(), "exportedUtf16Length", value.length(), "replayableOriginal", !redact));
        }
        sample.put("comparison", map("before", null, "after", null, "changed", null, "status", "not-observed"));
        return sample;
    }

    private static boolean validSourceKey(String key) {
        return "miui.focus.param".equals(key) || "miui.focus.param.custom".equals(key);
    }

    // 真实通知在结构字段也可能夹带任意文字。仅保留有界的十进制数值。
    private static boolean boundedStructuralNumber(Object value) {
        String text = value instanceof IslandExportJson.NumberToken
                ? ((IslandExportJson.NumberToken) value).text : null;
        return text != null && text.length() <= 24 && text.matches("-?[0-9]{1,12}(\\.[0-9]{1,8})?");
    }

    private static Map<String, Object> ruleMatch(String pkg, Object parsed) {
        Map<String, Object> out = map("packageName", pkg);
        if (!(parsed instanceof Map)) return out;
        Map<?, ?> root = (Map<?, ?>) parsed;
        Object protocol = root.get("protocol");
        if (protocol instanceof IslandExportJson.NumberToken) {
            String number = ((IslandExportJson.NumberToken) protocol).text;
            if (number.length() == 1 && number.charAt(0) >= '0' && number.charAt(0) <= '9') {
                out.put("protocolVersion", number);
            } else {
                out.put("protocolVersionPresent", true);
            }
        }
        if (root.get("business") instanceof String) out.put("businessRedacted", true);
        Object scene = root.get("scene");
        if ("verifyCode".equals(scene) || "stopWatch".equals(scene) || "timer".equals(scene)
                || "foodDelivery".equals(scene) || "templateRevertProgressScene".equals(scene)) {
            out.put("scene", scene);
        } else if (scene instanceof String) {
            out.put("sceneRedacted", true);
        }
        return out;
    }
    private static final class Visit {
        final Object value;
        final Object parent;
        final Object key;
        final String semantic;
        final String path;
        Visit(Object value, Object parent, Object key, String semantic, String path) {
            this.value = value; this.parent = parent; this.key = key; this.semantic = semantic; this.path = path;
        }
    }
    @SuppressWarnings("unchecked")
    private Object redactTree(Object root, String path, List<Redaction> changes, boolean realNotification) {
        List<Object> holder = new ArrayList<Object>(); holder.add(null);
        Deque<Visit> work = new ArrayDeque<Visit>(); work.push(new Visit(root, holder, 0, "", path));
        while (!work.isEmpty()) {
            Visit visit = work.pop();
            Object value = visit.value;
            Object output;
            if (value instanceof Map) {
                Map<String, Object> object = new LinkedHashMap<String, Object>(); output = object;
                List<Map.Entry<String, Object>> entries = new ArrayList<Map.Entry<String, Object>>(((Map<String, Object>) value).entrySet());
                for (int i = entries.size() - 1; i >= 0; i--) {
                    Map.Entry<String, Object> entry = entries.get(i);
                    String next = visit.semantic + "/" + pointer(entry.getKey());
                    boolean wrapper = visit.semantic.isEmpty() && visit.path.equals(path) && WRAPPERS.contains(entry.getKey());
                    if (!wrapper && !PREFIXES.contains(next)) {
                        // 禁止把含身份的动态键名复制进路径、原因或 Markdown。
                        change(changes, visit.path, "remove-subtree", entry.getValue(), null, "unsafe-key", true);
                    } else {
                        object.put(entry.getKey(), null);
                        work.push(new Visit(entry.getValue(), object, entry.getKey(), wrapper ? "" : next,
                                visit.path + "/" + pointer(entry.getKey())));
                    }
                }
            } else if (value instanceof List) {
                List<?> input = (List<?>) value;
                List<Object> list = new ArrayList<Object>(Collections.nCopies(input.size(), (Object) null)); output = list;
                for (int i = input.size() - 1; i >= 0; i--)
                    work.push(new Visit(input.get(i), list, i, visit.semantic + "/" + i, visit.path + "/" + i));
            } else {
                IslandRulePaths.Entry entry = IslandRulePaths.find(visit.semantic);
                output = value == null || (entry != null && entry.securityLevel == IslandRulePaths.SecurityLevel.STRUCTURAL
                        && (!realNotification || boundedStructuralNumber(value)))
                        ? value : replacement(value, visit.path, changes, "free-text");
            }
            if (visit.parent instanceof Map) ((Map<String, Object>) visit.parent).put((String) visit.key, output);
            else ((List<Object>) visit.parent).set((Integer) visit.key, output);
        }
        return holder.get(0);
    }

    @SuppressWarnings("unchecked")
    private Object extras(String text, boolean redact, String path, List<Redaction> changes) {
        Object root;
        try { root = IslandExportJson.parse(text); }
        catch (IslandExportJson.Invalid failure) {
            if (!redact) throw new Rejected(Reason.UNSAFE_EXTRAS);
            change(changes, path, "replace", text, PLACEHOLDER, "invalid-json", true);
            return PLACEHOLDER;
        }
        if (!(root instanceof Map)) throw new Rejected(Reason.UNSAFE_EXTRAS);
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> item : ((Map<String, Object>) root).entrySet()) {
            String key = item.getKey(); Object value = item.getValue();
            if (!EXTRA_KEYS.contains(key) || !validExtra(key, value)) {
                if (!redact) throw new Rejected(Reason.UNSAFE_EXTRAS);
                change(changes, path, "remove-subtree", value, null, "unsafe-extras-field", true);
                continue;
            }
            String itemPath = path + "/" + pointer(key);
            if (redact && key.equals("miui.focus.ticker")) {
                if (value instanceof String) value = replacement(value, itemPath, changes, "free-text");
                else {
                    Map<String, Object> descriptor = new LinkedHashMap<String, Object>((Map<String, Object>) value);
                    if (descriptor.get("value") instanceof String)
                        descriptor.put("value", replacement(descriptor.get("value"), itemPath + "/value", changes, "free-text"));
                    value = descriptor;
                }
            }
            output.put(key, value);
        }
        return output;
    }
    private boolean validExtra(String key, Object value) {
        if (key.equals("miui.focus.isFocus") && value instanceof Boolean) return true;
        if (key.equals("miui.focus.ticker") && value instanceof String) return true;
        if (!(value instanceof Map)) return false;
        Map<?, ?> descriptor = (Map<?, ?>) value;
        for (Map.Entry<?, ?> item : descriptor.entrySet()) {
            String field = (String) item.getKey(); Object scalar = item.getValue();
            if (field.equals("present")) { if (!(scalar instanceof Boolean)) return false; }
            else if (field.equals("valueType")) { if (!(scalar instanceof String) || !SAFE_TYPES.contains(scalar)) return false; }
            else if (field.equals("length") && (key.equals("miui.focus.param") || key.equals("miui.focus.param.custom")
                    || key.equals("miui.focus.ticker"))) {
                if (!(scalar instanceof IslandExportJson.NumberToken)
                        || !nonNegativeInteger(((IslandExportJson.NumberToken) scalar).text)) return false;
            } else if (field.equals("value") && key.equals("miui.focus.isFocus")) { if (!(scalar instanceof Boolean)) return false; }
            else if (field.equals("value") && key.equals("miui.focus.ticker")) { if (!(scalar instanceof String)) return false; }
            else return false;
        }
        return true;
    }
    private boolean nonNegativeInteger(String text) {
        if (text.isEmpty() || (text.length() > 1 && text.charAt(0) == '0')) return false;
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) < '0' || text.charAt(i) > '9') return false;
        return true;
    }
    private Object replacement(Object original, String path, List<Redaction> changes, String reason) {
        String placeholder = "<redacted:" + type(original) + ">";
        change(changes, path, "replace", original, placeholder, reason, false);
        return placeholder;
    }
    private void change(List<Redaction> changes, String path, String action, Object original, Object exported, String reason, boolean structure) {
        changes.add(new Redaction(path, action, type(original), type(exported), length(original), length(exported), reason, structure));
    }
    private String type(Object value) {
        if (value == null) return "null";
        if (value instanceof Map) return "object";
        if (value instanceof List) return "array";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number || value instanceof IslandExportJson.NumberToken) return "number";
        return "string";
    }
    private Integer length(Object value) {
        if (value == null || value instanceof Map || value instanceof List) return null;
        if (value instanceof IslandExportJson.NumberToken) return ((IslandExportJson.NumberToken) value).text.length();
        return value.toString().length();
    }
    private static String pointer(String key) { return key.replace("~", "~0").replace("/", "~1"); }
    private List<Object> redactionMaps(List<Redaction> changes) {
        List<Object> list = new ArrayList<Object>();
        for (Redaction change : changes) list.add(map("path", change.path, "action", change.action,
                "originalType", change.originalType, "exportedType", change.exportedType,
                "originalUtf16Length", change.originalUtf16Length, "exportedUtf16Length", change.exportedUtf16Length,
                "reason", change.reason, "structureChanged", change.structureChanged));
        return list;
    }
    private String jsonDocument(ExportPlan plan, boolean complete, Reason reason) {
        Map<String, Object> meta = map("formatVersion", 1, "documentStatus", "draft", "exportMode", plan.mode.name().toLowerCase(java.util.Locale.ROOT),
                "sessionId", plan.sessionId, "complete", complete, "replayableOriginal", false,
                "sampleCount", plan.frozenSamples.size(), "reason", reason.name());
        IslandExportJson.Text json = new IslandExportJson.Text(MAX_JSON_BYTES);
        json.add("{\"meta\":").add(IslandExportJson.write(meta, MAX_JSON_BYTES)).add(",\"samples\":[");
        for (int i = 0; i < plan.jsonSamples.size(); i++) {
            if (i != 0) json.add(","); json.add(plan.jsonSamples.get(i));
        }
        return json.add("]}").toString();
    }
    private String markdownDocument(ExportPlan plan, boolean complete, Reason reason) {
        IslandExportJson.Text markdown = new IslandExportJson.Text(MAX_MARKDOWN_BYTES);
        markdown.add("# FocusRestore capture export\n\nFormat: draft 1\nMode: ").add(plan.mode.name())
                .add("\nComplete: ").add(Boolean.toString(complete)).add("\nSamples: ")
                .add(Integer.toString(plan.frozenSamples.size())).add("\nReason: ").add(reason.name()).add("\n\n");
        for (String sample : plan.markdownSamples) markdown.add(sample);
        return markdown.toString();
    }
    private String markdownSample(Map<String, Object> sample, int index, int redactionCount) {
        IslandExportJson.Text markdown = new IslandExportJson.Text(MAX_MARKDOWN_BYTES);
        markdown.add("## Sample ").add(Integer.toString(index + 1)).add("\n\n### Overview\n\n");
        markdown.add("    ").add(IslandExportJson.write(map("id", sample.get("id"), "capturedAt", sample.get("capturedAt"),
                "state", sample.get("state"), "reason", sample.get("reason"), "sbn", sample.get("sbn")), MAX_MARKDOWN_BYTES)).add("\n\n");
        markdown.add("### Original fields / redacted representation\n\n    ")
                .add(IslandExportJson.write(map("bodyText", sample.get("bodyText"), "extras", sample.get("extras"),
                        "rawParamJSON", sample.get("rawParamJSON")), MAX_MARKDOWN_BYTES)).add("\n\n");
        markdown.add("### Before / after comparison\n\n    {\"before\":null,\"after\":null,\"changed\":null,\"status\":\"not-observed\"}\n\n")
                .add("### Redactions / errors\n\nTransformations: ").add(Integer.toString(redactionCount)).add("\n\n");
        // 内容只来自投影树，并编码为单行 JSON 后放进缩进代码块，输入无法闭合围栏或形成活动链接/HTML。
        return markdown.toString();
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
