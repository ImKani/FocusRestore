/* SPDX-License-Identifier: GPL-3.0-only
 * ImKani / FocusRestore / https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * D2 封闭的纯 Java 捕获输入；来源合同见 docs/capture-export-spec.md §3.2/§4。
 * 参数选择依据 FocusParamKeys:24–28/43–50；保留未 trim 原值，不改内置读取。
 * 不读取 Android 对象，不验证身份。仅由 Session 的门禁后调用 read 才检查正文。
 */
public final class IslandCaptureInputAdapter {
    private IslandCaptureInputAdapter() { }

    /** 固定8键，拒绝通配符及任意 extrasJson；顺序只用于稳定描述，不作为身份凭证。 */
    private enum Key {
        FOCUS("miui.focus.isFocus"), PRIMARY(FocusParamKeys.PRIMARY), CUSTOM(FocusParamKeys.CUSTOM),
        TICKER("miui.focus.ticker"), RV("miui.focus.rv"), RV_NIGHT("miui.focus.rvNight"),
        RV_BAR("miui.focus.rvBar"), RV_BAR_NIGHT("miui.focus.rvBarNight");
        final String text;
        Key(String text) { this.text = text; }
        static Key find(String text) {
            for (Key key : values()) if (key.text.equals(text)) return key;
            return null;
        }
    }
    static final int MAX_SNIPPET_UNITS = 96;

    enum Uneditable {
        NO_PARAMETER("这条焦点通知没有岛参数"), INVALID("岛参数格式无效"),
        NO_FIELDS("岛参数没有可编辑的登记字段"), LIMIT("岛参数或字段超过捕获上限"),
        UNREADABLE("无法读取岛参数");
        final String text;
        Uneditable(String text) { this.text = text; }
    }

    static String shortText(Object value) {
        if (!(value instanceof String)) return "";
        String text = (String) value;
        int end = Math.min(text.length(), MAX_SNIPPET_UNITS);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        String bounded = text.substring(0, end);
        return wellFormed(bounded) ? bounded.replaceAll("[\\p{Cc}\\p{Cf}\\s]+", " ").trim() : "";
    }

    static boolean wellFormed(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    private enum Kind { ABSENT, NULL, STRING, BOOLEAN, WRONG_TYPE, READ_ERROR, REMOTE_VIEWS }

    /** 只保存真实 String/Boolean 或固定状态；绝不排队持有传入的任意对象。 */
    public static final class Value {
        private final Kind kind;
        private final String text;
        private final Boolean bool;
        private Value(Kind kind, String text, Boolean bool) {
            this.kind = kind; this.text = text; this.bool = bool;
        }
        public static Value absent() { return new Value(Kind.ABSENT, null, null); }
        public static Value of(Object scalar) {
            if (scalar == null) return new Value(Kind.NULL, null, null);
            if (scalar instanceof String) return new Value(Kind.STRING, (String) scalar, null);
            if (scalar instanceof Boolean) return new Value(Kind.BOOLEAN, null, (Boolean) scalar);
            return new Value(Kind.WRONG_TYPE, null, null);
        }
        /** 模拟已经脱离真实源的读取失败，不保存异常/回调或其原文。 */
        public static Value readFailure() { return new Value(Kind.READ_ERROR, null, null); }
        /** 纯类型描述占位，不证明真实 RemoteViews 或其来源，不能携带对象内容。 */
        public static Value remoteViewsMarker() { return new Value(Kind.REMOTE_VIEWS, null, null); }
    }

    public static final class Builder {
        private Value body = Value.absent();
        private final Value[] extras = new Value[Key.values().length];
        public Builder() { for (int i = 0; i < extras.length; i++) extras[i] = Value.absent(); }
        public Builder bodyText(Value value) { body = value == null ? Value.absent() : value; return this; }
        public Builder extra(String key, Value value) {
            Key allowed = Key.find(key);
            if (allowed == null) throw new IllegalArgumentException("unapproved capture key");
            extras[allowed.ordinal()] = value == null ? Value.absent() : value;
            return this;
        }
        public Input build() { return new Input(body, extras); }
    }
    /** 不含来源认证声明、任意读取接口或生命周期对象；构造时不选择/trim/编码正文。 */
    public static final class Input {
        private final Value body;
        private final Value[] extras;
        final Uneditable reason;
        final String snippet;
        private Input(Value body, Value[] extras) { this(body, extras, null, ""); }
        private Input(Value body, Value[] extras, Uneditable reason, String snippet) {
            this.body = body;
            this.extras = extras == null ? null : extras.clone();
            this.reason = reason;
            this.snippet = snippet;
        }
    }
    public enum Code { NOT_READ, READY, MISSING_FIELD, TYPE_MISMATCH, READ_FAILED, OVERSIZE }
    public static final class Summary {
        public final Code code;
        public final String path;
        /** 仅表示本次纯输入选择了哪个键；记录/导出没有此字段，不从它推定来源认证。 */
        public final String selectedKey;
        private Summary(Code code, String path, String selectedKey) {
            this.code = code; this.path = path; this.selectedKey = selectedKey;
        }
    }
    private enum InvalidScalar { TYPE_MISMATCH }
    private static final class OversizeRejection {
        static final String TEXT;
        static {
            // 仅用于把描述编码超额交给旧 Session 的 OVERSIZE 路径；从不成为成功正文。
            char[] text = new char[IslandCaptureSession.MAX_FIELD_BYTES + 1];
            java.util.Arrays.fill(text, ' ');
            TEXT = new String(text);
        }
    }

    /** 创建仅保存封闭输入的延迟源；不调用 read，不遍历/编码正文。 */
    public static Source source(Input input) { return new Source(input, false); }

    /** 已获授权的同步读取。只有存在岛参数才访问其他字段，且不把读取器交给工作线程。 */
    static Input readNotification(FocusParamKeys.ValueSource extras, String bodyKey,
            Predicate<Object> isRemoteViews) {
        return readNotification(extras, bodyKey, isRemoteViews, null, false);
    }

    /** 仅实际准入焦点链路后允许摘取可辨认的显示文本。 */
    static Input readFocus(FocusParamKeys.ValueSource extras, String bodyKey,
            Predicate<Object> isRemoteViews, String displayText) {
        try { return readNotification(extras, bodyKey, isRemoteViews, displayText, true); }
        catch (RuntimeException | LinkageError unavailable) {
            return new Input(null, null, Uneditable.UNREADABLE, shortText(displayText));
        }
    }

    private static Input readNotification(FocusParamKeys.ValueSource extras, String bodyKey,
            Predicate<Object> isRemoteViews, String displayText, boolean admitted) {
        Object primary = extras.get(Key.PRIMARY.text);
        Object custom = extras.get(Key.CUSTOM.text);
        boolean oversized = overScalarLimit(primary) || overScalarLimit(custom);
        boolean parameter = !oversized && (nonBlankParam(primary) || nonBlankParam(custom));
        if (!admitted && !parameter) return null;
        Object body = extras.get(bodyKey);
        String snippet = shortText(displayText);
        if (admitted && snippet.isEmpty()) snippet = shortText(body);
        if (admitted && snippet.isEmpty()) snippet = shortText(extras.get(Key.TICKER.text));
        if (admitted && snippet.isEmpty()) snippet = shortText(extras.get("android.title"));
        if (!parameter) {
            boolean wrongType = (primary != null && !(primary instanceof String)) || (custom != null && !(custom instanceof String));
            Uneditable reason = oversized ? Uneditable.LIMIT
                    : wrongType ? Uneditable.INVALID : Uneditable.NO_PARAMETER;
            return new Input(null, null, reason, snippet);
        }
        Builder builder = new Builder();
        // CharSequence 可能携带未知实现，捕获只接受真实 String。
        int inputBytes = 2 * snippet.length();
        if (body instanceof String) {
            int bytes = boundedBytes((String) body);
            if (admitted && bytes > IslandCaptureSession.MAX_FIELD_BYTES) return new Input(null, null, Uneditable.LIMIT, snippet);
            if (!overScalarLimit(body)) builder.bodyText(Value.of(body));
            inputBytes += bytes;
        }
        for (Key key : Key.values()) {
            Object value;
            switch (key) {
                case PRIMARY: value = primary; break;
                case CUSTOM: value = custom; break;
                default: value = extras.get(key.text); break;
            }
            if (admitted && value instanceof String) {
                int bytes = boundedBytes((String) value);
                inputBytes += bytes;
                if (bytes > IslandCaptureSession.MAX_FIELD_BYTES || inputBytes > IslandCaptureSession.MAX_RECORD_BYTES) {
                    return new Input(null, null, Uneditable.LIMIT, snippet);
                }
            }
            if (overScalarLimit(value)) return admitted
                    ? new Input(null, null, Uneditable.LIMIT, snippet) : null;
            if (value != null) builder.extra(key.text, isRemoteViews.test(value)
                    ? Value.remoteViewsMarker() : Value.of(value));
        }
        return new Input(builder.body, builder.extras, null, snippet);
    }

    private static boolean overScalarLimit(Object value) {
        return value instanceof String && ((String) value).length() > IslandCaptureSession.MAX_FIELD_BYTES;
    }

    private static boolean nonBlankParam(Object value) {
        return value instanceof String && !((String) value).trim().isEmpty();
    }

    /** 真实通知可以只携带岛参数，普通通知正文缺失时按未观测保留。 */
    static Source notificationSource(Input input) { return new Source(input, true); }

    public static final class Source implements IslandCaptureSession.BodySource {
        private final Input input;
        private final boolean notification;
        private volatile Summary summary = new Summary(Code.NOT_READ, "$", null);
        private int reads;
        private Source(Input input, boolean notification) {
            this.input = input;
            this.notification = notification;
        }
        public Summary summary() { return summary; }
        public synchronized int readCount() { return reads; }

        /** 仅 Session 执行后才应调用；直接调用不代表已经通过外部身份或隐私授权。 */
        @Override public synchronized IslandCaptureSession.BodyFields read() throws Exception {
            if (reads < Integer.MAX_VALUE) reads++;
            if (input == null) return failed(Code.MISSING_FIELD, "$", null);
            if (notification && input.reason != null) return IslandCaptureSession.recentOnlyFields();
            Value body = input.body;
            if (body.kind == Kind.READ_ERROR) return failed(Code.READ_FAILED, "$.bodyText", null);
            boolean missingBody = body.kind == Kind.ABSENT || body.kind == Kind.NULL;
            if (missingBody && !notification) return failed(Code.MISSING_FIELD, "$.bodyText", null);
            if (!missingBody && body.kind != Kind.STRING) return failed(Code.TYPE_MISMATCH, "$.bodyText", null);

            Key selected = null;
            boolean wrongType = false;
            // 与 FocusParamKeys 一致的优先级和 trim 判空；仅用原字符串保存捕获结果。
            for (Key key : new Key[] {Key.PRIMARY, Key.CUSTOM}) {
                Value value = input.extras[key.ordinal()];
                if (value.kind == Kind.READ_ERROR) return failed(Code.READ_FAILED, keyPath(key), null);
                if (value.kind == Kind.STRING) {
                    if (nonBlankParam(value.text)) { selected = key; break; }
                } else if (value.kind != Kind.ABSENT && value.kind != Kind.NULL) wrongType = true;
            }
            if (selected == null) return failed(wrongType ? Code.TYPE_MISMATCH : Code.MISSING_FIELD,
                    "$.rawParamJSON", null);
            String raw = input.extras[selected.ordinal()].text;
            Map<String, Object> descriptors = new LinkedHashMap<String, Object>();
            // 不接外部 JSON；固定枚举逐键构造，未批准的键在输入结构中根本不存在。
            for (Key key : Key.values()) descriptors.put(key.text, describe(key, input.extras[key.ordinal()]));
            String extras;
            try { extras = IslandExportJson.write(descriptors, IslandCaptureSession.MAX_FIELD_BYTES); }
            catch (IslandExportJson.Capacity capacity) {
                summary = new Summary(Code.OVERSIZE, "$.extrasJson", selected.text);
                if (notification) return IslandCaptureSession.recentOnlyFields();
                return new IslandCaptureSession.BodyFields(body.text, raw, OversizeRejection.TEXT);
            }
            // 正文/原串按与旧 Session 相同的 UTF-8 口径检查；超额仍交其既有整体拒绝分支。
            String tooLarge = body.text != null && oversized(body.text) ? "$.bodyText"
                    : oversized(raw) ? "$.rawParamJSON" : null;
            summary = new Summary(tooLarge == null ? Code.READY : Code.OVERSIZE,
                    tooLarge == null ? "$" : tooLarge, selected.text);
            if (notification && tooLarge != null) return IslandCaptureSession.recentOnlyFields();
            return new IslandCaptureSession.BodyFields(body.text, raw, extras,
                    notification ? selected.text : null);
        }
        private IslandCaptureSession.BodyFields failed(Code code, String path, String selectedKey) throws Exception {
            summary = new Summary(code, path, selectedKey);
            if (code == Code.READ_FAILED) throw new Exception("capture input read failed");
            if (code == Code.TYPE_MISMATCH)
                return new IslandCaptureSession.BodyFields(InvalidScalar.TYPE_MISMATCH, "", "");
            return new IslandCaptureSession.BodyFields(null, null, null);
        }
    }
    private static String keyPath(Key key) { return "$.extras." + key.text; }

    private static Map<String, Object> describe(Key key, Value value) {
        Map<String, Object> descriptor = new LinkedHashMap<String, Object>();
        if (value.kind != Kind.READ_ERROR) descriptor.put("present", value.kind != Kind.ABSENT);
        String type;
        switch (value.kind) {
            case ABSENT: type = "missing"; break;
            case NULL: type = "null"; break;
            case READ_ERROR: type = "read-error"; break;
            case BOOLEAN: type = key == Key.FOCUS ? "boolean" : "wrong-type"; break;
            case STRING:
                type = key == Key.PRIMARY || key == Key.CUSTOM || key == Key.TICKER ? "string" : "wrong-type";
                break;
            case REMOTE_VIEWS: type = key.ordinal() >= Key.RV.ordinal() ? "remote-views" : "wrong-type"; break;
            default: type = "wrong-type";
        }
        descriptor.put("valueType", type);
        if (type.equals("string")) {
            descriptor.put("length", value.text.length());
            if (key == Key.TICKER) descriptor.put("value", value.text);
        } else if (type.equals("boolean")) descriptor.put("value", value.bool);
        return descriptor;
    }
    /** 与 Session 对孤立代理项计1的 Java UTF-8 口径一致，首个超额点停止。 */
    private static boolean oversized(String text) { return boundedBytes(text) > IslandCaptureSession.MAX_FIELD_BYTES; }

    private static int boundedBytes(String text) {
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x7f) bytes++;
            else if (c <= 0x7ff) bytes += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4; i++;
            } else if (Character.isSurrogate(c)) bytes++;
            else bytes += 3;
            if (bytes > IslandCaptureSession.MAX_FIELD_BYTES) break;
        }
        return bytes;
    }
}
