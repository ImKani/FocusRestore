/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 兼容端只接收按优先顺序排列的包名和登记路径。 */
final class IslandRuleProjection {
    private static final int MAGIC = 0x4652504a; // FRPJ
    private static final int SINGLE_GROUP_VERSION = 1;
    private static final int MIXED_GROUP_VERSION = 2;
    private static final int MAX_BYTES = 128 * 1024;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    static final class Row {
        final String packageName;
        final List<String> paths;

        private Row(String packageName, List<String> paths) {
            this.packageName = packageName;
            this.paths = IslandRule.immutableList(paths, "paths");
        }
    }

    static final class Snapshot {
        final long revision;
        final List<Row> rows;
        final String binding;
        private final byte[] payload;
        private final List<IslandRuleRepository.Entry> projectedEntries;

        private Snapshot(long revision, List<Row> rows, List<IslandRuleRepository.Entry> projectedEntries,
                int version) throws IOException {
            this.revision = revision;
            this.rows = IslandRule.immutableList(rows, "rows");
            this.projectedEntries = IslandRule.immutableList(projectedEntries, "projectedEntries");
            payload = encode(rows, version);
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
            int next = 0;
            for (int old = 0; old < previous.rows.size(); old++) {
                IslandRuleRepository.Entry source = previous.projectedEntries.get(old);
                if (source.id.equals(targetId)) continue;
                if (next >= rows.size()) return false;
                IslandRuleRepository.Entry retained = projectedEntries.get(next);
                Row before = previous.rows.get(old);
                Row after = rows.get(next++);
                if (!source.id.equals(retained.id) || !source.sha256.equals(retained.sha256)
                        || !before.packageName.equals(after.packageName) || !before.paths.equals(after.paths)) return false;
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
        int version = SINGLE_GROUP_VERSION;
        IslandRuleImportValidator validator = new IslandRuleImportValidator();
        for (IslandRuleRepository.Entry entry : entries) {
            if (!entry.enabled || entry.forced) continue;
            // ReadView 校验原始字节摘要，预览与读取均从同一已验证清单生成。
            byte[] bytes = view.read(entry);
            IslandRuleImportValidator.Result result = validator.validate(bytes, null);
            if (!result.canApprove(false)) continue;
            Row row = captureRow(bytes, result.rule);
            if (row != null) {
                // 仅在实际投影包含跨组行时升级，旧单组快照的授权绑定及字节保持稳定。
                if (IslandRulePaths.MIXED_TEMPLATE.equals(result.rule.meta.templateType)) version = MIXED_GROUP_VERSION;
                rows.add(row);
                projectedEntries.add(entry);
            }
        }
        return new Snapshot(view.revision, rows, projectedEntries, version);
    }

    static Row captureRow(byte[] bytes, IslandRule rule) throws IOException {
        List<IslandRulePaths.Entry> registered = new ArrayList<>();
        for (ExtractSpec binding : rule.extract) registered.add(IslandRulePaths.find(binding.path));
        IslandCaptureEditor.Candidate canonical;
        try {
            long timestamp = Instant.parse(rule.meta.createdAt).toEpochMilli();
            canonical = IslandCaptureEditor.candidate(rule.meta.packageName, registered, timestamp);
        } catch (DateTimeException | ArithmeticException invalidTimestamp) {
            return null;
        }
        // 用编辑器生成的完整结构作白名单。容许规则包显式写出默认值，拒绝附注及额外条件。
        if (canonical == null || !normalizedDocument(bytes).equals(normalizedDocument(canonical.bytes()))) return null;
        List<String> paths = new ArrayList<>();
        for (IslandRulePaths.Entry path : canonical.paths) paths.add(path.path);
        return new Row(canonical.packageName, paths);
    }

    private static Map<String, Object> normalizedDocument(byte[] bytes) throws IOException {
        Map<String, Object> document = IslandRuleCodec.portableDocument(bytes);
        IslandPortableRule.normalizeDefaults(document);
        return document;
    }

    private static byte[] encode(List<Row> rows, int version) throws IOException {
        if (rows.size() > IslandRuleRepository.MAX_ACTIVE) throw invalid();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeByte(version);
            out.writeByte(rows.size());
            for (Row row : rows) {
                out.writeUTF(row.packageName);
                out.writeByte(row.paths.size());
                for (String path : row.paths) out.writeUTF(path);
            }
        }
        if (bytes.size() > MAX_BYTES) throw invalid();
        return bytes.toByteArray();
    }

    static List<Row> decode(byte[] payload) throws IOException {
        if (payload == null || payload.length > MAX_BYTES) throw invalid();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != MAGIC) throw invalid();
            int version = in.readUnsignedByte();
            if (version != SINGLE_GROUP_VERSION && version != MIXED_GROUP_VERSION) throw invalid();
            int count = in.readUnsignedByte();
            if (count > IslandRuleRepository.MAX_ACTIVE) throw invalid();
            List<Row> rows = new ArrayList<>(count);
            for (int index = 0; index < count; index++) rows.add(readRow(in, version));
            if (in.read() != -1) throw invalid();
            return IslandRule.immutableList(rows, "rows");
        }
    }

    private static Row readRow(DataInputStream in, int version) throws IOException {
        String name = in.readUTF();
        int count = in.readUnsignedByte();
        if (name.length() > 255 || !PACKAGE.matcher(name).matches()
                || count == 0 || count > IslandCaptureEditor.MAX_SELECTION) throw invalid();
        List<String> paths = new ArrayList<>(count);
        Set<String> seen = new HashSet<>();
        String group = null;
        for (int index = 0; index < count; index++) {
            String path = in.readUTF();
            IslandRulePaths.Entry registered = IslandRulePaths.find(path);
            if (!IslandRulePaths.isRuntimeAllowed(registered) || !seen.add(path)) throw invalid();
            if (group == null) group = registered.templateGroup;
            else if (version == SINGLE_GROUP_VERSION && !group.equals(registered.templateGroup)) throw invalid();
            paths.add(path);
        }
        return new Row(name, paths);
    }

    private static IOException invalid() { return new IOException("RULE_PROJECTION_FORMAT"); }

    private IslandRuleProjection() { }
}
