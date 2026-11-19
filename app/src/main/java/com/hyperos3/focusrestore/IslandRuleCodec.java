/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** C1 格式边界：成功不代表已通过 L2/L3 或可执行，无通知/Android 依赖。 */
public final class IslandRuleCodec {
    public static final int MAX_UTF8_BYTES = 65536;
    public static final int MAX_JSON_DEPTH = 8;
    public static final int MAX_OBJECT_KEYS = 64;
    public static final int MAX_ARRAY_ITEMS = 32;
    public static final int MAX_STRING_UNITS = 4096;
    public static final int MAX_KEY_UNITS = 256;
    public static final int MAX_PATH_SEGMENTS = 8;

    public enum Code {
        L1_FILE_SIZE, L1_ENCODING, L1_JSON, L1_DUPLICATE_KEY, L1_VERSION,
        L1_FORBIDDEN_FIELD, L1_UNKNOWN_FIELD, L1_REQUIRED, L1_TYPE, L1_CAPACITY,
        L1_PATH_ENUM, L1_SLOT_ENUM, L1_OPERATOR_ENUM, L1_TEMPLATE_PATH, L1_IO, L1_INTERNAL
    }
    public static final class Error {
        public final Code code;
        public final String path;
        public final int offset;
        public final String reason;
        private Error(Code code, String path, int offset, String reason) {
            this.code = code; this.path = path; this.offset = offset; this.reason = reason;
        }
    }
    public static final class DecodeResult {
        public final IslandRule rule;
        public final Error error;
        public final int utf8Bytes;
        private DecodeResult(IslandRule rule, Error error, int utf8Bytes) {
            this.rule = rule; this.error = error; this.utf8Bytes = utf8Bytes;
        }
        public boolean isSuccess() { return rule != null && error == null; }
    }
    private static final Set<String> FORBIDDEN = set("code", "script", "expression", "eval",
            "exec", "class", "method", "reflection", "url", "network", "file", "filePath",
            "intent", "Intent", "action", "Action", "pendingIntent", "PendingIntent",
            "layout", "style", "view", "color", "font", "html", "regex");
    private static final Set<String> META = set("packageName", "focusNotificationName",
            "notificationTag", "systemUiVersion", "hyperOsVersion", "deviceModel",
            "deviceCodename", "protocolVersion", "business", "templateType",
            "notificationChannelId", "notificationCategory", "ruleAuthor", "ruleDescription", "createdAt");
    private static final Set<String> TEMPLATES = set("baseInfo", "highlightInfo", "highlightInfoV3",
            "chatInfo", "iconTextInfo", "animTextInfo", "coverInfo", "hintInfo", "stepInfo",
            "progressInfo", "multiProgressInfo", "param_island", "fallback", "legacy", IslandRulePaths.MIXED_TEMPLATE);

    /** 不关闭调用方流；最多消费上限+1字节。有限读入不保证任意阻塞流的时间上限。 */
    public DecodeResult decode(InputStream input) {
        if (input == null) return failure(Code.L1_TYPE, "$", -1, "input required", 0);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[1024];
            while (true) {
                int count = input.read(buffer, 0, Math.min(buffer.length, MAX_UTF8_BYTES + 1 - bytes.size()));
                if (count < 0) break;
                if (count == 0) {
                    int next = input.read();
                    if (next < 0) break;
                    bytes.write(next);
                } else bytes.write(buffer, 0, count);
                if (bytes.size() > MAX_UTF8_BYTES) {
                    return failure(Code.L1_FILE_SIZE, "$", -1, "max UTF-8 bytes 65536", bytes.size());
                }
            }
            return decode(bytes.toByteArray());
        } catch (IOException e) {
            return failure(Code.L1_IO, "$", -1, "input read failed", bytes.size());
        } catch (RuntimeException e) {
            return failure(Code.L1_INTERNAL, "$", -1, "input boundary failed", bytes.size());
        }
    }
    public DecodeResult decode(byte[] bytes) {
        if (bytes == null) return failure(Code.L1_TYPE, "$", -1, "input required", 0);
        if (bytes.length > MAX_UTF8_BYTES) {
            return failure(Code.L1_FILE_SIZE, "$", -1, "max UTF-8 bytes 65536", bytes.length);
        }
        try {
            Map<String, Object> root = readDocument(bytes);
            int version = (int) integer(required(root, "ruleVersion", "$"), "$.ruleVersion", 1, 1, Code.L1_VERSION);
            inspectStructure(root);
            return new DecodeResult(model(root, version), null, bytes.length);
        } catch (CharacterCodingException e) {
            return failure(Code.L1_ENCODING, "$", -1, "invalid UTF-8", bytes.length);
        } catch (Rejected e) {
            return failure(e.code, e.path, e.offset, e.reason, bytes.length);
        } catch (RuntimeException e) {
            // 异常消息/未知键可能含私人数据，只输出固定摘要。
            return failure(Code.L1_INTERNAL, "$", -1, "decode failed", bytes.length);
        }
    }

    private static Map<String, Object> readDocument(byte[] bytes) throws CharacterCodingException, Rejected {
        String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (!json.isEmpty() && json.charAt(0) == '\ufeff') throw reject(Code.L1_ENCODING, "$", 0, "BOM forbidden");
        return object(new Reader(json).document(), "$");
    }

    static Map<String, Object> portableDocument(byte[] bytes) throws IOException {
        if (bytes.length > MAX_UTF8_BYTES) throw new IOException("PORTABLE_RULE_SIZE");
        try {
            return readDocument(bytes);
        } catch (CharacterCodingException | Rejected invalid) {
            throw new IOException("PORTABLE_RULE_DOCUMENT");
        }
    }

    private static IslandRule model(Map<String, Object> root, int version) throws Rejected {
        Map<String, Object> meta = object(required(root, "meta", "$"), "$.meta");
        String pkg = text(required(meta, "packageName", "$.meta"), "$.meta.packageName", 3, 255);
        if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) throw reject(Code.L1_TYPE, "$.meta.packageName", "package syntax");
        String template = choice(required(meta, "templateType", "$.meta"), "$.meta.templateType", TEMPLATES, Code.L1_TEMPLATE_PATH);
        IslandRule.SystemUiVersion ui = null;
        if (meta.get("systemUiVersion") != null) {
            Map<String, Object> system = object(meta.get("systemUiVersion"), "$.meta.systemUiVersion");
            ui = new IslandRule.SystemUiVersion(text(required(system, "name", "$.meta.systemUiVersion"),
                    "$.meta.systemUiVersion.name", 0, 128), integer(required(system, "code", "$.meta.systemUiVersion"),
                    "$.meta.systemUiVersion.code", 0, 9007199254740991L, Code.L1_CAPACITY));
        }
        Integer protocol = meta.get("protocolVersion") == null ? null : (int) integer(meta.get("protocolVersion"), "$.meta.protocolVersion", 0, Integer.MAX_VALUE, Code.L1_CAPACITY);
        String created = text(required(meta, "createdAt", "$.meta"), "$.meta.createdAt", 1, 64);
        Matcher date = Pattern.compile("([0-9]{4})-([0-9]{2})-([0-9]{2})[Tt]([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.[0-9]+)?(?:[Zz]|([+-])([0-9]{2}):([0-9]{2}))").matcher(created);
        if (!date.matches()) throw reject(Code.L1_TYPE, "$.meta.createdAt", "RFC3339 timestamp required");
        try {
            int year = Integer.parseInt(date.group(1));
            LocalDate.of(year, Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3)));
            if (year == 0 || Integer.parseInt(date.group(4)) > 23
                    || Integer.parseInt(date.group(5)) > 59 || Integer.parseInt(date.group(6)) > 59
                    || date.group(7) != null && (Integer.parseInt(date.group(8)) > 23 || Integer.parseInt(date.group(9)) > 59)) {
                throw reject(Code.L1_TYPE, "$.meta.createdAt", "date or offset range");
            }
        } catch (DateTimeException e) { throw reject(Code.L1_TYPE, "$.meta.createdAt", "invalid date"); }
        IslandRule.Metadata metadata = new IslandRule.Metadata(pkg, optionalText(meta, "focusNotificationName", 128),
                optionalText(meta, "notificationTag", 255), ui, optionalText(meta, "hyperOsVersion", 128),
                optionalText(meta, "deviceModel", 128), optionalText(meta, "deviceCodename", 128), protocol,
                optionalText(meta, "business", 128), template, optionalText(meta, "notificationChannelId", 255),
                optionalText(meta, "notificationCategory", 64), text(required(meta, "ruleAuthor", "$.meta"), "$.meta.ruleAuthor", 1, 64),
                text(required(meta, "ruleDescription", "$.meta"), "$.meta.ruleDescription", 0, 2048), created);
        Map<String, Object> match = object(required(root, "match", "$"), "$.match");
        List<Object> clauses = array(required(match, "all", "$.match"), "$.match.all", 1, 16);
        if (new HashSet<>(clauses).size() != clauses.size()) throw reject(Code.L1_CAPACITY, "$.match.all", "unique items required");
        List<MatchClause> matches = new ArrayList<>();
        for (int i = 0; i < clauses.size(); i++) {
            String at = "$.match.all[" + i + "]";
            Map<String, Object> clause = object(clauses.get(i), at);
            matches.add(new MatchClause(choice(required(clause, "field", at), at + ".field", set("packageName", "protocolVersion", "business", "scene"), Code.L1_PATH_ENUM),
                    choice(required(clause, "op", at), at + ".op", set("equals", "contains", "startsWith"), Code.L1_OPERATOR_ENUM),
                    text(required(clause, "value", at), at + ".value", 1, 256)));
        }
        List<Object> bindings = array(required(root, "extract", "$"), "$.extract", 1, 32);
        List<ExtractSpec> extracts = new ArrayList<>();
        for (int i = 0; i < bindings.size(); i++) {
            String at = "$.extract[" + i + "]";
            Map<String, Object> binding = object(bindings.get(i), at);
            String id = identifier(required(binding, "id", at), at + ".id");
            String path = text(required(binding, "path", at), at + ".path", 1, 512);
            if (path.split("/", -1).length - 1 > MAX_PATH_SEGMENTS) throw reject(Code.L1_PATH_ENUM, at + ".path", "max path segments 8");
            IslandRulePaths.Entry entry = IslandRulePaths.find(path);
            if (entry == null || !IslandRulePaths.isRuntimeAllowed(entry)) throw reject(Code.L1_PATH_ENUM, at + ".path", "path not allowlisted");
            if (!IslandRulePaths.matchesTemplate(template, entry)) throw reject(Code.L1_TEMPLATE_PATH, at + ".path", "path outside template group");
            List<ExtractSpec.Replacement> replacements = new ArrayList<>();
            if (binding.containsKey("replace")) {
                List<Object> replace = array(binding.get("replace"), at + ".replace", 0, 8);
                for (int j = 0; j < replace.size(); j++) {
                    String rat = at + ".replace[" + j + "]";
                    Map<String, Object> pair = object(replace.get(j), rat);
                    replacements.add(new ExtractSpec.Replacement(text(required(pair, "from", rat), rat + ".from", 1, 128), text(required(pair, "to", rat), rat + ".to", 0, 128)));
                }
            }
            extracts.add(new ExtractSpec(id, path, binding.containsKey("required") && bool(binding.get("required"), at + ".required"), replacements));
        }
        Map<String, Object> display = object(required(root, "display", "$"), "$.display");
        List<Object> ordered = array(required(display, "order", "$.display"), "$.display.order", 1, 16);
        List<DisplaySpec.Projection> projections = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            String at = "$.display.order[" + i + "]";
            Map<String, Object> projection = object(ordered.get(i), at);
            projections.add(new DisplaySpec.Projection(choice(required(projection, "slot", at), at + ".slot", set("title", "subtitle", "progress", "extra"), Code.L1_SLOT_ENUM), identifier(required(projection, "ref", at), at + ".ref")));
        }
        return new IslandRule(version, metadata, matches, extracts, new DisplaySpec(projections,
                display.containsKey("separator") ? text(display.get("separator"), "$.display.separator", 0, 32) : "·",
                display.containsKey("dedupe") && bool(display.get("dedupe"), "$.display.dedupe")));
    }
    private static void inspectStructure(Map<String, Object> root) throws Rejected {
        List<Rejected> findings = new ArrayList<>();
        scanObject(root, set("ruleVersion", "meta", "match", "extract", "display"), "$", findings);
        scanObject(root.get("meta"), META, "$.meta", findings);
        if (root.get("meta") instanceof Map) scanObject(((Map<?, ?>) root.get("meta")).get("systemUiVersion"), set("name", "code"), "$.meta.systemUiVersion", findings);
        scanObject(root.get("match"), set("all"), "$.match", findings);
        if (root.get("match") instanceof Map) scanArray(((Map<?, ?>) root.get("match")).get("all"), set("field", "op", "value"), "$.match.all", findings);
        scanArray(root.get("extract"), set("id", "path", "required", "replace"), "$.extract", findings);
        if (root.get("extract") instanceof List) {
            List<?> bindings = (List<?>) root.get("extract");
            for (int i = 0; i < bindings.size(); i++) if (bindings.get(i) instanceof Map) scanArray(((Map<?, ?>) bindings.get(i)).get("replace"), set("from", "to"), "$.extract[" + i + "].replace", findings);
        }
        scanObject(root.get("display"), set("order", "separator", "dedupe"), "$.display", findings);
        if (root.get("display") instanceof Map) scanArray(((Map<?, ?>) root.get("display")).get("order"), set("slot", "ref"), "$.display.order", findings);
        for (Rejected finding : findings) if (finding.code == Code.L1_FORBIDDEN_FIELD) throw finding;
        if (!findings.isEmpty()) throw findings.get(0);
    }
    private static void scanArray(Object value, Set<String> keys, String path, List<Rejected> findings) {
        if (value instanceof List) {
            List<?> items = (List<?>) value;
            for (int i = 0; i < items.size(); i++) scanObject(items.get(i), keys, path + "[" + i + "]", findings);
        }
    }
    private static void scanObject(Object value, Set<String> keys, String path, List<Rejected> findings) {
        if (!(value instanceof Map)) return;
        for (Object keyObject : ((Map<?, ?>) value).keySet()) {
            String key = (String) keyObject;
            if (keys.contains(key) || key.startsWith("x-")) continue;
            if (FORBIDDEN.contains(key)) findings.add(reject(Code.L1_FORBIDDEN_FIELD, path, "forbidden member"));
            else findings.add(reject(Code.L1_UNKNOWN_FIELD, path, "unknown member"));
        }
    }
    private static String optionalText(Map<String, Object> map, String key, int max) throws Rejected { return map.get(key) == null ? null : text(map.get(key), "$.meta." + key, 0, max); }
    private static Object required(Map<String, Object> object, String key, String path) throws Rejected {
        if (!object.containsKey(key)) throw reject(Code.L1_REQUIRED, path + "." + key, "required member");
        return object.get(key);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String path) throws Rejected {
        if (!(value instanceof Map)) throw reject(Code.L1_TYPE, path, "object required");
        return (Map<String, Object>) value;
    }
    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String path, int min, int max) throws Rejected {
        if (!(value instanceof List)) throw reject(Code.L1_TYPE, path, "array required");
        List<Object> list = (List<Object>) value;
        if (list.size() < min || list.size() > max) throw reject(Code.L1_CAPACITY, path, "array count out of range");
        return list;
    }
    private static String text(Object value, String path, int min, int max) throws Rejected {
        if (!(value instanceof String)) throw reject(Code.L1_TYPE, path, "string required");
        String text = (String) value;
        if (text.length() < min || text.length() > max) throw reject(Code.L1_CAPACITY, path, "string length out of range");
        return text;
    }
    private static String identifier(Object value, String path) throws Rejected {
        String id = text(value, path, 1, 32);
        if (!id.matches("[A-Za-z][A-Za-z0-9_]{0,31}")) throw reject(Code.L1_TYPE, path, "identifier syntax");
        return id;
    }
    private static String choice(Object value, String path, Set<String> allowed, Code code) throws Rejected {
        if (!(value instanceof String)) throw reject(Code.L1_TYPE, path, "string required");
        if (!allowed.contains(value)) throw reject(code, path, "unsupported enum value");
        return (String) value;
    }
    private static long integer(Object value, String path, long min, long max, Code code) throws Rejected {
        if (!(value instanceof JsonNumber)) throw reject(Code.L1_TYPE, path, "integer number required");
        JsonNumber number = (JsonNumber) value;
        if (number.digits.equals("0")) {
            if (min > 0 || max < 0) throw reject(code, path, "integer range");
            return 0;
        }
        if (number.exponent.signum() < 0) throw reject(Code.L1_TYPE, path, "integer number required");
        // 先按十进制位数拒绝超范围指数，绝不构造不受限的 10^exponent。
        if (number.exponent.compareTo(BigInteger.valueOf(19 - number.digits.length())) > 0) throw reject(code, path, "integer range");
        BigInteger integer = new BigInteger((number.negative ? "-" : "") + number.digits)
                .multiply(BigInteger.TEN.pow(number.exponent.intValue()));
        if (integer.compareTo(BigInteger.valueOf(min)) < 0 || integer.compareTo(BigInteger.valueOf(max)) > 0) throw reject(code, path, "integer range");
        return integer.longValue();
    }
    private static boolean bool(Object value, String path) throws Rejected {
        if (!(value instanceof Boolean)) throw reject(Code.L1_TYPE, path, "boolean required");
        return (Boolean) value;
    }
    private static Set<String> set(String... keys) { return new HashSet<>(Arrays.asList(keys)); }
    private static DecodeResult failure(Code code, String path, int offset, String reason, int size) { return new DecodeResult(null, new Error(code, path, offset, reason), size); }
    private static Rejected reject(Code code, String path, String reason) { return reject(code, path, -1, reason); }
    private static Rejected reject(Code code, String path, int offset, String reason) { return new Rejected(code, path, offset, reason); }
    private static final class Rejected extends Exception {
        private static final long serialVersionUID = 1L;
        final Code code; final String path; final int offset; final String reason;
        Rejected(Code code, String path, int offset, String reason) { this.code = code; this.path = path; this.offset = offset; this.reason = reason; }
    }

    /** 有界词法数字；只规范化系数与指数，不展开指数，注解可安全保留任意标准数字。 */
    private static final class JsonNumber {
        final String digits;
        final BigInteger exponent;
        final boolean negative;

        JsonNumber(String token) {
            int e = token.indexOf('e');
            if (e < 0) e = token.indexOf('E');
            String mantissa = e < 0 ? token : token.substring(0, e);
            boolean sign = mantissa.charAt(0) == '-';
            if (sign) mantissa = mantissa.substring(1);
            int dot = mantissa.indexOf('.');
            int fraction = dot < 0 ? 0 : mantissa.length() - dot - 1;
            String coefficient = mantissa.replace(".", "");
            int start = 0;
            while (start < coefficient.length() && coefficient.charAt(start) == '0') start++;
            if (start == coefficient.length()) {
                digits = "0";
                exponent = BigInteger.ZERO;
                negative = false;
                return;
            }
            int end = coefficient.length();
            while (coefficient.charAt(end - 1) == '0') end--;
            digits = coefficient.substring(start, end);
            BigInteger explicit = e < 0 ? BigInteger.ZERO : new BigInteger(token.substring(e + 1));
            exponent = explicit.add(BigInteger.valueOf(coefficient.length() - end - fraction));
            negative = sign;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof JsonNumber)) return false;
            JsonNumber number = (JsonNumber) other;
            return negative == number.negative && digits.equals(number.digits) && exponent.equals(number.exponent);
        }
        @Override public int hashCode() {
            return 31 * (31 * digits.hashCode() + exponent.hashCode()) + (negative ? 1 : 0);
        }
    }

    /** 注解与普通字段均走有界读入器；字符串转义后检查代理项和UTF-16长度。 */
    private static final class Reader {
        final String json;
        int at;
        Reader(String json) { this.json = json; }
        Object document() throws Rejected {
            Object result = value(0); space();
            if (at != json.length()) throw syntax();
            return result;
        }
        Object value(int depth) throws Rejected {
            space();
            if (at == json.length()) throw syntax();
            char c = json.charAt(at);
            if (c == '{' || c == '[') {
                if (depth == MAX_JSON_DEPTH) throw capacity("max JSON depth 8");
                return c == '{' ? objectValue(depth + 1) : arrayValue(depth + 1);
            }
            if (c == '"') return string(MAX_STRING_UNITS);
            if (c == 't' && literal("true")) return Boolean.TRUE;
            if (c == 'f' && literal("false")) return Boolean.FALSE;
            if (c == 'n' && literal("null")) return null;
            if (c == '-' || c >= '0' && c <= '9') return number();
            throw syntax();
        }
        Map<String, Object> objectValue(int depth) throws Rejected {
            at++;
            Map<String, Object> result = new LinkedHashMap<>(); space();
            if (take('}')) return result;
            do {
                space();
                if (result.size() == MAX_OBJECT_KEYS) throw capacity("max object keys 64");
                if (at == json.length() || json.charAt(at) != '"') throw syntax();
                String key = string(MAX_KEY_UNITS);
                if (result.containsKey(key)) throw reject(Code.L1_DUPLICATE_KEY, "$", at, "duplicate member");
                space(); if (!take(':')) throw syntax();
                result.put(key, value(depth)); space();
                if (take('}')) return result;
            } while (take(','));
            throw syntax();
        }
        List<Object> arrayValue(int depth) throws Rejected {
            at++;
            List<Object> result = new ArrayList<>(); space();
            if (take(']')) return result;
            do {
                if (result.size() == MAX_ARRAY_ITEMS) throw capacity("max array items 32");
                result.add(value(depth)); space();
                if (take(']')) return result;
            } while (take(','));
            throw syntax();
        }
        String string(int max) throws Rejected {
            at++;
            StringBuilder out = new StringBuilder();
            while (at < json.length()) {
                char c = json.charAt(at++);
                if (c == '"') {
                    for (int i = 0; i < out.length(); i++) {
                        char unit = out.charAt(i);
                        if (Character.isHighSurrogate(unit)) {
                            if (++i == out.length() || !Character.isLowSurrogate(out.charAt(i))) throw syntax();
                        } else if (Character.isLowSurrogate(unit)) throw syntax();
                    }
                    return out.toString();
                }
                if (c < 0x20) throw syntax();
                if (c == '\\') {
                    if (at == json.length()) throw syntax();
                    c = json.charAt(at++);
                    switch (c) {
                        case '"': case '\\': case '/': break;
                        case 'b': c = '\b'; break;
                        case 'f': c = '\f'; break;
                        case 'n': c = '\n'; break;
                        case 'r': c = '\r'; break;
                        case 't': c = '\t'; break;
                        case 'u':
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                if (at == json.length()) throw syntax();
                                char hex = json.charAt(at++);
                                int digit = hex >= '0' && hex <= '9' ? hex - '0' : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10 : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                                if (digit < 0) throw syntax();
                                code = code * 16 + digit;
                            }
                            c = (char) code; break;
                        default: throw syntax();
                    }
                }
                if (out.length() == max) throw capacity("string UTF-16 limit");
                out.append(c);
            }
            throw syntax();
        }
        JsonNumber number() throws Rejected {
            int start = at; take('-');
            if (!take('0')) {
                if (!digit()) throw syntax();
                while (digit()) at++;
            }
            if (take('.')) { if (!digit()) throw syntax(); while (digit()) at++; }
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                if (!digit()) throw syntax(); while (digit()) at++;
            }
            if (at - start > MAX_STRING_UNITS) throw capacity("number token limit");
            return new JsonNumber(json.substring(start, at));
        }
        boolean digit() { return at < json.length() && json.charAt(at) >= '0' && json.charAt(at) <= '9'; }
        boolean literal(String value) { if (!json.startsWith(value, at)) return false; at += value.length(); return true; }
        boolean take(char c) { if (at == json.length() || json.charAt(at) != c) return false; at++; return true; }
        void space() {
            while (at < json.length()) {
                char c = json.charAt(at);
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') break;
                at++;
            }
        }
        Rejected syntax() { return reject(Code.L1_JSON, "$", at, "invalid strict JSON"); }
        Rejected capacity(String reason) { return reject(Code.L1_CAPACITY, "$", at, reason); }
    }
}
