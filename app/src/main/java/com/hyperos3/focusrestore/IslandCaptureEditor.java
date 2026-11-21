/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从登记路径和通知类型生成规则。样本值只在当前预览使用。 */
final class IslandCaptureEditor {
    static final int MAX_SELECTION = 16;
    static final int MAX_SNIPPET_UNITS = 96;
    static final int MAX_VISIBLE_BATCH = 8;
    private static final String SEPARATOR = " · ";

    static final class Lifetime {
        final IslandCaptureEditor editor = new IslandCaptureEditor();
        private volatile boolean revoked;
        private final long deadline;

        Lifetime(long deadline) { this.deadline = deadline; }
        boolean live(long now) { return !revoked && now < deadline; }
        boolean acceptPage(long now, IslandCapturePreview.Page page) {
            return live(now) && editor.append(page);
        }
        synchronized void revoke() { revoked = true; editor.clear(); }

        synchronized void publish(java.util.function.LongSupplier clock,
                IslandRuleRepository.ManifestPublication publication) throws java.io.IOException {
            if (!live(clock.getAsLong())) throw new java.io.IOException("CAPTURE_SAVE_REVOKED");
            publication.publish();
        }
    }

    private final List<IslandCapturePreview.Field> fields = new ArrayList<>();
    private final List<IslandRulePaths.Entry> selected = new ArrayList<>();
    private IslandNotificationType notificationType;
    private boolean complete;

    boolean append(IslandCapturePreview.Page page) {
        if (complete || page == null || page.notificationType == null || page.fields.isEmpty()
                || notificationType != null && !notificationType.equals(page.notificationType)
                || fields.size() + page.fields.size() > IslandCapturePreview.MAX_FIELDS) return false;
        int previous = fields.isEmpty() ? -1 : IslandRulePaths.all().indexOf(fields.get(fields.size() - 1).path);
        for (IslandCapturePreview.Field field : page.fields) {
            int index = IslandRulePaths.all().indexOf(field.path);
            if (index <= previous || !IslandRulePaths.isRuntimeAllowed(field.path)
                    || !page.notificationType.paths.contains(field.path.path)) return false;
            previous = index;
        }
        notificationType = page.notificationType;
        complete = page.nextOffset == -1;
        fields.addAll(page.fields);
        return true;
    }

    List<IslandCapturePreview.Field> fields() { return Collections.unmodifiableList(fields); }
    List<IslandRulePaths.Entry> order() { return Collections.unmodifiableList(selected); }

    boolean toggle(IslandRulePaths.Entry path) {
        if (selected.remove(path)) return true;
        if (selected.size() >= MAX_SELECTION || !contains(path)) return false;
        selected.add(path);
        return true;
    }

    private boolean contains(IslandRulePaths.Entry path) {
        for (IslandCapturePreview.Field field : fields) if (field.path == path) return true;
        return false;
    }

    boolean move(int from, int to) {
        if (from < 0 || to < 0 || from >= selected.size() || to >= selected.size() || from == to) return false;
        selected.add(to, selected.remove(from));
        return true;
    }

    IslandNotificationType notificationType() { return complete ? notificationType : null; }

    void clear() {
        selected.clear();
        fields.clear();
        notificationType = null;
        complete = false;
    }

    String preview() {
        if (selected.isEmpty()) return "";
        Map<String, String> bindings = new LinkedHashMap<>();
        List<DisplaySpec.Projection> order = new ArrayList<>();
        for (IslandRulePaths.Entry path : selected) {
            for (IslandCapturePreview.Field field : fields) {
                if (field.path != path) continue;
                String ref = "field" + (order.size() + 1);
                bindings.put(ref, displayValue(field));
                order.add(new DisplaySpec.Projection("extra", ref));
                break;
            }
        }
        IslandRulePostProcessor.Result result = new IslandRulePostProcessor().processBindings(
                bindings, new DisplaySpec(order, SEPARATOR, false), new IslandRuleBudget());
        return result.isSuccess() ? result.text : "";
    }

    Candidate candidate(String packageName, long timestamp) {
        if (!complete) return null;
        Candidate proposal = candidate(packageName, selected, timestamp, notificationType);
        return proposal != null && new IslandRuleImportValidator().validate(proposal.bytes(), null).canApprove(false)
                ? proposal : null;
    }

    static String displayValue(IslandCapturePreview.Field field) {
        return field.type == IslandCapturePreview.Type.BOOLEAN ? (field.booleanValue ? "true" : "false") : field.text;
    }

    static String snippet(List<IslandCapturePreview.Field> fields) {
        StringBuilder result = new StringBuilder();
        // 优先显示自由文本。纯结构样本仍显示登记标量。
        appendSnippets(fields, IslandRulePaths.SecurityLevel.FREE_TEXT, result);
        if (result.length() == 0) appendSnippets(fields, IslandRulePaths.SecurityLevel.STRUCTURAL, result);
        return result.toString();
    }

    private static void appendSnippets(List<IslandCapturePreview.Field> fields, IslandRulePaths.SecurityLevel level,
            StringBuilder result) {
        int count = 0;
        for (IslandCapturePreview.Field field : fields) {
            if (field.path.securityLevel != level) continue;
            String value = displayValue(field).replaceAll("[\\p{Cc}\\p{Cf}\\s]+", " ").trim();
            if (value.isEmpty()) continue;
            if (result.length() != 0) result.append(SEPARATOR);
            int end = Math.min(value.length(), MAX_SNIPPET_UNITS - result.length());
            if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
            result.append(value, 0, end);
            if (++count == 2 || result.length() > MAX_SNIPPET_UNITS - SEPARATOR.length()) return;
        }
    }

    static final class LocalApproval {
        private final Object previewIdentity;
        private final long baseline;
        private final String digest;
        private boolean used;

        LocalApproval(Object previewIdentity, long baseline, Candidate candidate) {
            this.previewIdentity = java.util.Objects.requireNonNull(previewIdentity);
            this.baseline = baseline;
            this.digest = candidate.digest;
        }

        synchronized void consume(Object currentPreview, IslandRuleRepository.Pending pending,
                IslandRuleRepository.ManifestPublication verifyPreview) throws java.io.IOException {
            if (used) throw new java.io.IOException("CAPTURE_SAVE_APPROVAL_USED");
            // 拒绝也消耗批准。失效的候选、预览或基线须重新由用户确认。
            used = true;
            if (currentPreview != previewIdentity || pending.baselineRevision != baseline
                    || pending.entry == null || !digest.equals(pending.entry.sha256)) {
                throw new java.io.IOException("CAPTURE_SAVE_APPROVAL_MISMATCH");
            }
            verifyPreview.publish();
        }
    }

    static final class Candidate {
        private final byte[] bytes;
        final String packageName;
        final List<IslandRulePaths.Entry> paths;
        final String digest;
        Candidate(byte[] bytes, String packageName, List<IslandRulePaths.Entry> paths) {
            this.bytes = bytes;
            this.packageName = packageName;
            this.paths = Collections.unmodifiableList(new ArrayList<>(paths));
            digest = IslandRuleRepository.digest(bytes);
        }
        byte[] bytes() { return Arrays.copyOf(bytes, bytes.length); }
    }

    static Candidate candidate(String packageName, List<IslandRulePaths.Entry> paths, long timestamp) {
        return candidate(packageName, paths, timestamp, null);
    }

    static Candidate candidate(String packageName, List<IslandRulePaths.Entry> paths, long timestamp,
            IslandNotificationType notificationType) {
        if (!IslandCaptureRuntime.validPackage(packageName) || packageName.indexOf('.') < 0
                || paths.isEmpty() || paths.size() > MAX_SELECTION || paths.get(0) == null) return null;
        String group = paths.get(0).templateGroup;
        Set<IslandRulePaths.Entry> seen = new HashSet<>();
        StringBuilder binding = new StringBuilder();
        StringBuilder order = new StringBuilder();
        for (IslandRulePaths.Entry path : paths) {
            if (!IslandRulePaths.isRuntimeAllowed(path) || !seen.add(path)
                    || notificationType != null && !notificationType.paths.contains(path.path)) return null;
            if (!group.equals(path.templateGroup)) group = IslandRulePaths.MIXED_TEMPLATE;
            String ref = "field" + seen.size();
            if (seen.size() > 1) { binding.append(','); order.append(','); }
            // 包名、模板和路径均来自受限集合，不拼入样本字段值。
            binding.append("{\"id\":\"").append(ref).append("\",\"path\":\"").append(path.path).append("\"}");
            order.append("{\"slot\":\"extra\",\"ref\":\"").append(ref).append("\"}");
        }
        String type = notificationType == null ? "" : ",\"notificationType\":"
                + IslandExportJson.write(notificationType.json(), IslandRuleCodec.MAX_UTF8_BYTES);
        String json = "{\"ruleVersion\":" + IslandRule.CURRENT_VERSION + ",\"meta\":{\"packageName\":\"" + packageName
                + "\",\"templateType\":\"" + group + "\",\"ruleAuthor\":\"FocusRestore\",\"ruleDescription\":\"\",\"createdAt\":\""
                + Instant.ofEpochMilli(timestamp) + "\"},\"match\":{\"all\":[{\"field\":\"packageName\",\"op\":\"equals\",\"value\":\""
                + packageName + "\"}]" + type + "},\"extract\":[" + binding + "],\"display\":{\"order\":[" + order
                + "],\"separator\":\"" + SEPARATOR + "\"}}";
        return new Candidate(json.getBytes(StandardCharsets.UTF_8), packageName, paths);
    }
}
