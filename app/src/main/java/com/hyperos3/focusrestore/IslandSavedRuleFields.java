/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 可视编辑仅改变所用路径和显示次序，其余声明沿用原文档。 */
final class IslandSavedRuleFields {
    private final byte[] original;
    private final IslandNotificationType type;

    IslandSavedRuleFields(byte[] original, IslandRuleProjection.Row captured) {
        this.original = original.clone();
        type = captured.notificationType;
    }

    @SuppressWarnings("unchecked") // 仅接收已经过 L1 和 captureRow 校验的规则文档。
    byte[] update(List<IslandRulePaths.Entry> order) {
        if (order.isEmpty() || order.size() > IslandCaptureEditor.MAX_SELECTION) return null;
        Set<IslandRulePaths.Entry> selected = new HashSet<>();
        String template = null;
        for (IslandRulePaths.Entry field : order) {
            if (!IslandRulePaths.isRuntimeAllowed(field) || !selected.add(field)
                    || type != null && !type.paths.contains(field.path)) return null;
            if (template == null) template = field.templateGroup;
            else if (!template.equals(field.templateGroup)) template = IslandRulePaths.MIXED_TEMPLATE;
        }

        Map<String, Object> document = (Map<String, Object>) IslandExportJson.parse(new String(original, StandardCharsets.UTF_8));
        Map<String, Object> meta = (Map<String, Object>) document.get("meta");
        Map<String, Object> display = (Map<String, Object>) document.get("display");
        List<Map<String, Object>> extracts = (List<Map<String, Object>>) document.get("extract");
        List<Map<String, Object>> projections = (List<Map<String, Object>>) display.get("order");
        Map<IslandRulePaths.Entry, Map<String, Object>> originalBindings = new HashMap<>();
        Map<String, Map<String, Object>> originalProjections = new HashMap<>();
        Set<String> retainedIds = new HashSet<>();
        for (Map<String, Object> binding : extracts) {
            IslandRulePaths.Entry field = IslandRulePaths.find((String) binding.get("path"));
            originalBindings.put(field, binding);
            if (selected.contains(field)) retainedIds.add((String) binding.get("id"));
        }
        for (Map<String, Object> projection : projections) originalProjections.put((String) projection.get("ref"), projection);

        List<Map<String, Object>> nextBindings = new ArrayList<>(order.size());
        List<Map<String, Object>> nextProjections = new ArrayList<>(order.size());
        int nextId = 1;
        for (IslandRulePaths.Entry field : order) {
            Map<String, Object> binding = originalBindings.get(field);
            Map<String, Object> projection;
            if (binding == null) {
                while (retainedIds.contains("field" + nextId)) nextId++;
                String id = "field" + nextId++;
                binding = new LinkedHashMap<>();
                binding.put("id", id);
                binding.put("path", field.path);
                projection = new LinkedHashMap<>();
                projection.put("slot", "extra");
                projection.put("ref", id);
            } else {
                projection = originalProjections.get(binding.get("id"));
            }
            nextBindings.add(binding);
            nextProjections.add(projection);
        }
        meta.put("templateType", template);
        document.put("extract", nextBindings);
        display.put("order", nextProjections);
        try {
            return IslandExportJson.write(document, IslandRuleCodec.MAX_UTF8_BYTES).getBytes(StandardCharsets.UTF_8);
        } catch (IslandExportJson.Capacity tooLarge) {
            return null;
        }
    }

    static String validate(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "请选择字段或填写规则内容。";
        IslandRuleImportValidator.Result validation = new IslandRuleImportValidator().validate(bytes, null);
        if (validation.canApprove(false)) return null;
        for (IslandRuleImportValidator.Finding finding : validation.findings) {
            if (!finding.blocking) continue;
            // 只显示校验器生成的固定路径与代码，不回显原始输入。
            return "请检查规则格式或必填条件（" + finding.path + " · " + finding.code + "）。";
        }
        return "无法校验当前规则。";
    }
}
