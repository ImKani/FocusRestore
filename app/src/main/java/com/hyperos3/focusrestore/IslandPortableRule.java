/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 可移植 v1 的封闭规则子集。只从已解码声明重建，保留支持字段的完整语义。 */
final class IslandPortableRule {
    private static final Pattern REF = Pattern.compile("field(?:[1-9]|1[0-6])");
    private static final Pattern SYSTEM_UI = Pattern.compile("[0-9]{1,9}(?:\\.[0-9]{1,9}){1,4}(?:\\.[a-z])?");

    private IslandPortableRule() {}

    static byte[] rebuild(byte[] original, IslandRule rule) throws IOException {
        IslandRule.Metadata meta = rule.meta;
        if (rule.match.size() != 1 || rule.extract.size() > 16 || !meta.ruleDescription.isEmpty()
                || !("FocusRestore".equals(meta.ruleAuthor) || "ImKani".equals(meta.ruleAuthor))) throw unsupported();
        MatchClause match = rule.match.get(0);
        if (!"packageName".equals(match.field) || !"equals".equals(match.op)
                || !meta.packageName.equals(match.value)) throw unsupported();
        String separator = rule.display.separator;
        if (!("".equals(separator) || "·".equals(separator) || " · ".equals(separator))) throw unsupported();

        StringBuilder json = new StringBuilder("{\"ruleVersion\":1,\"meta\":{\"packageName\":\"")
                .append(meta.packageName).append("\",\"templateType\":\"").append(meta.templateType)
                .append("\",\"ruleAuthor\":\"").append(meta.ruleAuthor)
                .append("\",\"ruleDescription\":\"\",\"createdAt\":\"").append(meta.createdAt).append('"');
        if (meta.systemUiVersion != null) {
            if (!SYSTEM_UI.matcher(meta.systemUiVersion.name).matches()) throw unsupported();
            json.append(",\"systemUiVersion\":{\"name\":\"").append(meta.systemUiVersion.name)
                    .append("\",\"code\":").append(meta.systemUiVersion.code).append('}');
        }
        if (meta.protocolVersion != null) json.append(",\"protocolVersion\":").append(meta.protocolVersion);
        json.append("},\"match\":{\"all\":[{\"field\":\"packageName\",\"op\":\"equals\",\"value\":\"")
                .append(meta.packageName).append("\"}]},\"extract\":[");
        for (int index = 0; index < rule.extract.size(); index++) {
            ExtractSpec binding = rule.extract.get(index);
            if (!REF.matcher(binding.id).matches() || !binding.replace.isEmpty()) throw unsupported();
            if (index != 0) json.append(',');
            json.append("{\"id\":\"").append(binding.id).append("\",\"path\":\"").append(binding.path)
                    .append("\",\"required\":").append(binding.required).append('}');
        }
        json.append("],\"display\":{\"order\":[");
        for (int index = 0; index < rule.display.order.size(); index++) {
            DisplaySpec.Projection projection = rule.display.order.get(index);
            if (!REF.matcher(projection.ref).matches()) throw unsupported();
            if (index != 0) json.append(',');
            json.append("{\"slot\":\"").append(projection.slot).append("\",\"ref\":\"")
                    .append(projection.ref).append("\"}");
        }
        json.append("],\"separator\":\"").append(separator).append("\",\"dedupe\":")
                .append(rule.display.dedupe).append("}}");
        byte[] rebuilt = json.toString().getBytes(StandardCharsets.UTF_8);

        // 核对原始结构，防止解码器忽略的注解或可选字段被悄悄省略。
        Map<String, Object> source = IslandRuleCodec.portableDocument(original);
        normalizeDefaults(source);
        if (!source.equals(IslandRuleCodec.portableDocument(rebuilt))) throw unsupported();
        return rebuilt;
    }

    @SuppressWarnings("unchecked") // 已通过严格 L1，容器类型已经验证。
    static void normalizeDefaults(Map<String, Object> source) {
        for (Map<String, Object> binding : (List<Map<String, Object>>) source.get("extract")) {
            binding.putIfAbsent("required", false);
        }
        Map<String, Object> display = (Map<String, Object>) source.get("display");
        display.putIfAbsent("separator", "·");
        display.putIfAbsent("dedupe", false);
    }

    private static IOException unsupported() { return new IOException("PORTABLE_RULE_UNSUPPORTED"); }
}
