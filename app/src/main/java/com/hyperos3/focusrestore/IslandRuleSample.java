/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 仅供 App 编辑预览，单独保存标量目录和可选示例值。 */
final class IslandRuleSample {
    static final int MAX_BYTES = 128 * 1024;

    static final class Field {
        final String path;
        final IslandCapturePreview.Type type;
        final String value;

        private Field(String path, IslandCapturePreview.Type type, String value) {
            this.path = path;
            this.type = type;
            this.value = value;
        }
    }

    final List<Field> fields;

    private IslandRuleSample(List<Field> fields) { this.fields = Collections.unmodifiableList(fields); }

    static IslandRuleSample capture(List<IslandCapturePreview.Field> fields) throws IOException {
        if (fields.isEmpty() || fields.size() > IslandCapturePreview.MAX_FIELDS) throw invalid();
        List<Field> captured = new ArrayList<>(fields.size());
        for (IslandCapturePreview.Field field : fields) {
            captured.add(new Field(field.path.path, field.type,
                    field.booleanValue == null ? field.text : field.booleanValue.toString()));
        }
        return decode(new IslandRuleSample(captured).encode(true));
    }

    boolean hasValues() {
        for (Field field : fields) if (field.value != null) return true;
        return false;
    }

    byte[] encode(boolean includeValues) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeShort(fields.size());
            for (Field field : fields) {
                out.writeUTF(field.path);
                // 固定编码，枚举的声明顺序不属于包格式。
                switch (field.type) {
                    case STRING: out.writeByte(1); break;
                    case BOOLEAN: out.writeByte(2); break;
                    case NUMBER: out.writeByte(3); break;
                    default: throw invalid();
                }
                if (!includeValues || field.value == null) {
                    out.writeShort(-1);
                } else {
                    byte[] value = field.value.getBytes(StandardCharsets.UTF_8);
                    if (value.length > IslandCapturePreview.MAX_FIELD_UTF8) throw invalid();
                    out.writeShort(value.length);
                    out.write(value);
                }
                if (bytes.size() > MAX_BYTES) throw invalid();
            }
        }
        return bytes.toByteArray();
    }

    static IslandRuleSample decode(byte[] encoded) throws IOException {
        if (encoded.length > MAX_BYTES) throw invalid();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int count = in.readUnsignedShort();
            if (count == 0 || count > IslandCapturePreview.MAX_FIELDS) throw invalid();
            List<Field> fields = new ArrayList<>(count);
            Set<String> paths = new HashSet<>();
            int budget = IslandCapturePreview.MAX_RECORD_BYTES;
            for (int index = 0; index < count; index++) {
                String path = in.readUTF();
                IslandRulePaths.Entry registered = IslandRulePaths.find(path);
                if (!IslandRulePaths.isRuntimeAllowed(registered) || !paths.add(path)) throw invalid();
                IslandCapturePreview.Type type;
                switch (in.readUnsignedByte()) {
                    case 1: type = IslandCapturePreview.Type.STRING; break;
                    case 2: type = IslandCapturePreview.Type.BOOLEAN; break;
                    case 3: type = IslandCapturePreview.Type.NUMBER; break;
                    default: throw invalid();
                }
                int length = in.readShort();
                String value = null;
                if (length != -1) {
                    if (length < 0 || length > IslandCapturePreview.MAX_FIELD_UTF8 || length > in.available()) throw invalid();
                    byte[] data = new byte[length];
                    in.readFully(data);
                    value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
                    IslandCapturePreview.Field field = validateValue(registered, type, value);
                    if (field == null || field.wireBytes > budget) throw invalid();
                    budget -= field.wireBytes;
                }
                fields.add(new Field(path, type, value));
            }
            if (in.read() != -1) throw invalid();
            return new IslandRuleSample(fields);
        }
    }

    private static IslandCapturePreview.Field validateValue(IslandRulePaths.Entry path, IslandCapturePreview.Type type,
            String value) {
        if (type != IslandCapturePreview.Type.BOOLEAN) return IslandCapturePreview.Field.create(path, type, value, null);
        if (!"true".equals(value) && !"false".equals(value)) return null;
        return IslandCapturePreview.Field.create(path, type, null, Boolean.valueOf(value));
    }

    private static IOException invalid() { return new IOException("RULE_SAMPLE_INVALID"); }
}
