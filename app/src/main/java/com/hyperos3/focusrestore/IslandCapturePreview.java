/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.hyperos3.focusrestore.IslandCaptureInputAdapter.Uneditable;
import static com.hyperos3.focusrestore.IslandCaptureInputAdapter.shortText;
import static com.hyperos3.focusrestore.IslandCaptureInputAdapter.wellFormed;

import java.util.regex.Pattern;

/** 可编辑捕获只保留登记路径下的标量。解析树只在工作线程的本次投影中使用。 */
final class IslandCapturePreview {
    static final int MAX_RECORDS = 30;
    static final int MAX_FIELDS = IslandRulePaths.all().size();
    static final int MAX_FIELD_UTF16 = 1024;
    static final int MAX_FIELD_UTF8 = 2048;
    static final int MAX_RECORD_BYTES = 64 * 1024;
    static final int MAX_PAGE_FIELDS = 12;
    static final int MAX_PAGE_BYTES = 8192;
    static final int PAGE_HEADER_BYTES = 64 + 8 + 2 * IslandRuleProjection.REGISTRY_BINDING.length();
    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    enum Type { STRING, BOOLEAN, NUMBER }

    static final class Summary {
        final long handle;
        final String packageName;
        final long capturedAt;
        final String sourceKey;
        final int fieldCount;
        final String reason;
        final Uneditable uneditable;

        Summary(long handle, String packageName, long capturedAt, String sourceKey, int fieldCount, Uneditable uneditable) {
            this.uneditable = uneditable;
            this.reason = uneditable == null ? null : uneditable.text;
            this.handle = handle;
            this.packageName = packageName;
            this.capturedAt = capturedAt;
            this.sourceKey = sourceKey;
            this.fieldCount = fieldCount;
        }
    }

    static final class Field {
        final IslandRulePaths.Entry path;
        final Type type;
        final String text;
        final Boolean booleanValue;
        final int wireBytes;

        private Field(IslandRulePaths.Entry path, Type type, String text, Boolean booleanValue, int wireBytes) {
            this.path = path;
            this.type = type;
            this.text = text;
            this.booleanValue = booleanValue;
            this.wireBytes = wireBytes;
        }

        static Field create(IslandRulePaths.Entry path, Type type, String text, Boolean bool) {
            if (!IslandRulePaths.isRuntimeAllowed(path) || type == null) return null;
            if (type == Type.BOOLEAN) return bool != null && text == null ? new Field(path, type, null, bool, 16) : null;
            if (bool != null || text == null || text.length() > MAX_FIELD_UTF16 || !wellFormed(text)) return null;
            int utf8 = text.getBytes(StandardCharsets.UTF_8).length;
            if (utf8 > MAX_FIELD_UTF8 || (type == Type.NUMBER && !finiteNumber(text))) return null;
            // 路径由登记索引传输。上界同时覆盖 UTF-8 和 Parcel 的 UTF-16 编码及对齐。
            int bytes = 16 + Math.max(utf8, 2 * text.length());
            return new Field(path, type, text, null, bytes);
        }
    }

    static final class Page {
        final int nextOffset;
        final List<Field> fields;
        final IslandNotificationType notificationType;

        Page(int nextOffset, List<Field> fields, IslandNotificationType notificationType) {
            this.nextOffset = nextOffset;
            this.fields = IslandRule.immutableList(fields, "fields");
            this.notificationType = notificationType;
        }
    }

    final Summary summary;
    final int user;
    final long generation;
    private final List<Field> fields;
    final IslandNotificationType notificationType;

    final long retainedBytes;
    private final String snippet;

    private IslandCapturePreview(IslandCaptureRecord record, long generation, List<Field> fields, Uneditable reason, String displayText, IslandNotificationType notificationType) {
        this.notificationType = notificationType;
        summary = new Summary(record.sequence, record.packageName, record.capturedAt, record.sourceKey, fields.size(), reason);
        user = record.userId;
        this.generation = generation;
        this.fields = fields;
        String projected = displayText.isEmpty() ? IslandCaptureEditor.snippet(fields) : displayText;
        snippet = projected.isEmpty() ? shortText(record.bodyText) : projected;
        long bytes = record.bodyBytes + 2L * snippet.length() + typeWireBytes(notificationType);
        for (Field field : fields) bytes += field.wireBytes;
        retainedBytes = bytes;
    }

    static boolean isSourceKey(String sourceKey) {
        return FocusParamKeys.PRIMARY.equals(sourceKey) || FocusParamKeys.CUSTOM.equals(sourceKey);
    }

    static IslandCapturePreview project(IslandCaptureRecord record, long generation) {
        return project(record, generation, null, "");
    }

    static IslandCapturePreview project(IslandCaptureRecord record, long generation, Uneditable reason, String snippet) {
        if (record.state != IslandCaptureRecord.State.CAPTURED || record.userId == null) return null;
        List<Field> empty = Collections.emptyList();
        if (reason != null) return new IslandCapturePreview(record, generation, empty, reason, snippet, null);
        if (!isSourceKey(record.sourceKey) || !InputLimits.isPayloadAllowed(record.rawParamJSON)) {
            return new IslandCapturePreview(record, generation, empty, Uneditable.LIMIT, snippet, null);
        }
        Object parsed;
        try { parsed = IslandExportJson.parse(record.rawParamJSON); }
        catch (IslandExportJson.Invalid invalid) { parsed = null; }
        if (!(parsed instanceof Map)) return new IslandCapturePreview(record, generation, empty, Uneditable.INVALID, snippet, null);
        Map<?, ?> root = (Map<?, ?>) parsed;
        Map<?, ?> selected = IslandRuleExtractor.selectContainer(root, null);
        IslandNotificationType type = IslandRuleContextAdapter.classify(root, null);
        List<Field> fields = new ArrayList<>();
        int bytes = 0;
        boolean limited = false;
        for (IslandRulePaths.Entry path : IslandRulePaths.all()) {
            if (!IslandRulePaths.isRuntimeAllowed(path)) continue;
            if (type == null || !type.paths.contains(path.path)) continue;
            Object value = IslandNotificationType.value(root, selected, path);
            Field field = projectField(path, value);
            if (field == null || field.wireBytes > MAX_RECORD_BYTES - bytes) {
                if (value instanceof String || value instanceof IslandExportJson.NumberToken) limited = true;
                continue;
            }
            fields.add(field);
            bytes += field.wireBytes;
        }
        return new IslandCapturePreview(record, generation, fields, fields.isEmpty()
                ? (limited ? Uneditable.LIMIT : Uneditable.NO_FIELDS) : null, snippet, type);
    }

    private static Field projectField(IslandRulePaths.Entry path, Object value) {
        if (value instanceof String) return Field.create(path, Type.STRING, (String) value, null);
        if (value instanceof Boolean) return Field.create(path, Type.BOOLEAN, null, (Boolean) value);
        if (value instanceof IslandExportJson.NumberToken) {
            return Field.create(path, Type.NUMBER, ((IslandExportJson.NumberToken) value).text, null);
        }
        return null;
    }

    String snippet() { return snippet; }

    boolean containsPaths(List<IslandRulePaths.Entry> paths) {
        for (IslandRulePaths.Entry path : paths) {
            boolean present = false;
            for (Field field : fields) {
                if (field.path == path) { present = true; break; }
            }
            if (!present) return false;
        }
        return true;
    }

    Page page(int offset) {
        if (offset < 0 || offset >= fields.size()) return null;
        List<Field> page = new ArrayList<>();
        int bytes = PAGE_HEADER_BYTES + typeWireBytes(notificationType);
        int next = offset;
        while (next < fields.size() && page.size() < MAX_PAGE_FIELDS) {
            Field field = fields.get(next);
            if (field.wireBytes > MAX_PAGE_BYTES - bytes) break;
            page.add(field);
            bytes += field.wireBytes;
            next++;
        }
        return new Page(next == fields.size() ? -1 : next, page, notificationType);
    }

    static int typeWireBytes(IslandNotificationType type) {
        // 各字符串长度、结束符、对齐和字段计数均计入原分页上限。
        return type == null ? 0 : 32 + 4 * type.paths.size()
                + 2 * (length(type.protocol) + length(type.business) + length(type.scene));
    }

    private static int length(String value) { return value == null ? 0 : value.length(); }

    private static boolean finiteNumber(String text) {
        if (!NUMBER.matcher(text).matches()) return false;
        try { return Double.isFinite(Double.parseDouble(text)); }
        catch (NumberFormatException invalid) { return false; }
    }

}
