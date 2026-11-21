/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 兼容端只接收按优先顺序排列的应用、通知类型和所选登记路径。 */
final class IslandRuleProjection {
    private static final int MAGIC = 0x4652504a; // FRPJ
    private static final int VERSION = 3;
    static final String REGISTRY_BINDING = registryBinding();
    private static final int MAX_BYTES = 128 * 1024;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    static final class Row {
        final String packageName;
        final List<String> paths;
        final IslandNotificationType notificationType;

        private Row(String packageName, List<String> paths, IslandNotificationType notificationType) {
            this.packageName = packageName;
            this.paths = IslandRule.immutableList(paths, "paths");
            this.notificationType = notificationType;
        }
    }

    static final class Snapshot {
        final long revision;
        final List<Row> rows;
        final String binding;
        private final byte[] payload;
        private final List<IslandRuleRepository.Entry> projectedEntries;

        private Snapshot(long revision, List<Row> rows,
                List<IslandRuleRepository.Entry> projectedEntries) throws IOException {
            this.revision = revision;
            this.rows = IslandRule.immutableList(rows, "rows");
            this.projectedEntries = IslandRule.immutableList(projectedEntries, "projectedEntries");
            payload = encode(rows);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeLong(revision);
                for (IslandRuleRepository.Entry entry : projectedEntries) output.writeUTF(entry.sha256);
                output.write(payload);
            }
            binding = IslandRuleRepository.digest(bytes.toByteArray());
        }

        byte[] payload() { return payload.clone(); }

        boolean retainsAllExcept(Snapshot previous, String targetId) {
            return retainsAllExcept(previous, java.util.Collections.singleton(targetId));
        }

        boolean retainsAllExcept(Snapshot previous, Set<String> targets) {
            int next = 0;
            for (int old = 0; old < previous.rows.size(); old++) {
                IslandRuleRepository.Entry source = previous.projectedEntries.get(old);
                if (targets.contains(source.id)) continue;
                if (next >= rows.size()) return false;
                IslandRuleRepository.Entry retained = projectedEntries.get(next);
                Row before = previous.rows.get(old);
                Row after = rows.get(next++);
                if (!source.id.equals(retained.id) || !source.sha256.equals(retained.sha256)
                        || !before.packageName.equals(after.packageName) || !before.paths.equals(after.paths)
                        || !Objects.equals(before.notificationType, after.notificationType)) return false;
            }
            return next == rows.size();
        }
    }

    static Snapshot snapshot(IslandRuleRepository.ReadView view) throws IOException {
        List<IslandRuleRepository.Entry> entries = new ArrayList<>(view.entries);
        entries.sort(Comparator.comparingInt((IslandRuleRepository.Entry entry) -> entry.priority).reversed()
                .thenComparingLong(entry -> entry.order));
        List<Row> rows = new ArrayList<>();
        List<IslandRuleRepository.Entry> projectedEntries = new ArrayList<>();
        IslandRuleImportValidator validator = new IslandRuleImportValidator();
        for (IslandRuleRepository.Entry entry : entries) {
            if (!entry.enabled || entry.forced) continue;
            // ReadView 校验原始字节摘要，预览与读取均从同一已验证清单生成。
            byte[] bytes = view.read(entry);
            IslandRuleImportValidator.Result result = validator.validate(bytes, null);
            if (!result.canApprove(false)) continue;
            Row row = captureRow(bytes, result.rule);
            if (row != null) {
                rows.add(row);
                projectedEntries.add(entry);
            }
        }
        return new Snapshot(view.revision, rows, projectedEntries);
    }

    static Row captureRow(byte[] bytes, IslandRule rule) throws IOException {
        List<IslandRulePaths.Entry> registered = new ArrayList<>();
        for (ExtractSpec binding : rule.extract) registered.add(IslandRulePaths.find(binding.path));
        IslandCaptureEditor.Candidate canonical;
        try {
            long timestamp = Instant.parse(rule.meta.createdAt).toEpochMilli();
            canonical = IslandCaptureEditor.candidate(rule.meta.packageName, registered, timestamp, rule.notificationType);
        } catch (DateTimeException | ArithmeticException invalidTimestamp) {
            return null;
        }
        // 用编辑器生成的完整结构作白名单。容许规则包显式写出默认值，拒绝附注及额外条件。
        Map<String, Object> document = normalizedDocument(bytes);
        // 编辑顺序后绑定 ID 仍指向原字段；仅在投影比较的副本中按位置重编号。
        // 一对一、无重复和其他完整结构仍须通过规范文档检查。
        if (!normalizeBindingIds(document, rule)
                || canonical == null || !document.equals(normalizedDocument(canonical.bytes()))) return null;
        List<String> paths = new ArrayList<>();
        for (IslandRulePaths.Entry path : canonical.paths) paths.add(path.path);
        return new Row(canonical.packageName, paths, rule.notificationType);
    }

    @SuppressWarnings("unchecked") // 通过 L1 的结构，下面只改比较副本中的 ID。
    private static boolean normalizeBindingIds(Map<String, Object> document, IslandRule rule) {
        if (rule.extract.size() != rule.display.order.size()) return false;
        Set<String> unique = new HashSet<>();
        List<Map<String, Object>> bindings = (List<Map<String, Object>>) document.get("extract");
        Map<String, Object> display = (Map<String, Object>) document.get("display");
        List<Map<String, Object>> order = (List<Map<String, Object>>) display.get("order");
        for (int i = 0; i < bindings.size(); i++) {
            String id = rule.extract.get(i).id;
            if (!IslandPortableRule.isFieldRef(id) || !unique.add(id)
                    || !id.equals(rule.display.order.get(i).ref)) return false;
            String canonical = "field" + (i + 1);
            bindings.get(i).put("id", canonical);
            order.get(i).put("ref", canonical);
        }
        return true;
    }

    private static Map<String, Object> normalizedDocument(byte[] bytes) throws IOException {
        Map<String, Object> document = IslandRuleCodec.portableDocument(bytes);
        IslandPortableRule.normalizeDefaults(document);
        return document;
    }

    private static byte[] encode(List<Row> rows) throws IOException {
        if (rows.size() > IslandRuleRepository.MAX_ACTIVE) throw invalid();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeByte(rows.size());
            out.writeUTF(REGISTRY_BINDING);
            for (Row row : rows) {
                out.writeUTF(row.packageName);
                out.writeByte(row.paths.size());
                for (String path : row.paths) out.writeUTF(path);
                writeType(out, row.notificationType);
            }
        }
        if (bytes.size() > MAX_BYTES) throw invalid();
        return bytes.toByteArray();
    }

    static List<Row> decode(byte[] payload) throws IOException {
        if (payload == null || payload.length > MAX_BYTES) throw invalid();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != MAGIC) throw invalid();
            if (in.readUnsignedByte() != VERSION) throw invalid();
            int count = in.readUnsignedByte();
            if (count > IslandRuleRepository.MAX_ACTIVE) throw invalid();
            if (!REGISTRY_BINDING.equals(in.readUTF())) throw invalid();
            List<Row> rows = new ArrayList<>(count);
            for (int index = 0; index < count; index++) rows.add(readRow(in));
            if (in.read() != -1) throw invalid();
            return IslandRule.immutableList(rows, "rows");
        }
    }

    private static Row readRow(DataInputStream in) throws IOException {
        String name = in.readUTF();
        int count = in.readUnsignedByte();
        if (name.length() > 255 || !PACKAGE.matcher(name).matches()
                || count == 0 || count > IslandCaptureEditor.MAX_SELECTION) throw invalid();
        List<String> paths = new ArrayList<>(count);
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < count; index++) {
            String path = in.readUTF();
            IslandRulePaths.Entry registered = IslandRulePaths.find(path);
            if (!IslandRulePaths.isRuntimeAllowed(registered) || !seen.add(path)) throw invalid();
            paths.add(path);
        }
        IslandNotificationType type = readType(in);
        if (type != null && !type.paths.containsAll(paths)) throw invalid();
        return new Row(name, paths, type);
    }

    private static void writeType(DataOutputStream out, IslandNotificationType type) throws IOException {
        out.writeByte(type == null ? 0 : 1);
        if (type == null) return;
        out.writeUTF(type.protocol == null ? "" : type.protocol);
        out.writeUTF(type.business == null ? "" : type.business);
        out.writeUTF(type.scene == null ? "" : type.scene);
        out.writeShort(type.paths.size());
        for (String path : type.paths) out.writeShort(IslandRulePaths.all().indexOf(IslandRulePaths.find(path)));
    }

    private static IslandNotificationType readType(DataInputStream in) throws IOException {
        int present = in.readUnsignedByte();
        if (present == 0) return null;
        if (present != 1) throw invalid();
        String protocol = absentWhenEmpty(in.readUTF());
        String business = absentWhenEmpty(in.readUTF());
        String scene = absentWhenEmpty(in.readUTF());
        int count = in.readUnsignedShort();
        if (count == 0 || count > IslandNotificationType.MAX_PATHS) throw invalid();
        List<String> paths = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int registeredIndex = in.readUnsignedShort();
            if (registeredIndex >= IslandNotificationType.MAX_PATHS) throw invalid();
            paths.add(IslandRulePaths.all().get(registeredIndex).path);
        }
        IslandNotificationType type = IslandNotificationType.create(protocol, business, scene, paths);
        if (type == null) throw invalid();
        return type;
    }

    private static String absentWhenEmpty(String value) { return value.isEmpty() ? null : value; }

    private static String registryBinding() {
        // v3 用登记索引压缩完整签名。不同登记表的旧 Hook 必须拒读，不能把值解码成另一条路径。
        StringBuilder ordered = new StringBuilder();
        for (IslandRulePaths.Entry entry : IslandRulePaths.all()) ordered.append(entry.path).append('\n');
        return IslandRuleRepository.digest(ordered.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static IOException invalid() { return new IOException("RULE_PROJECTION_FORMAT"); }

    private IslandRuleProjection() { }
}
