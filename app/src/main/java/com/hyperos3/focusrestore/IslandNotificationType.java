/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** 来源包由规则独立校验。此值只含协议标识和完整非空登记路径集合。 */
final class IslandNotificationType {
    static final int MAX_IDENTIFIER = 96;
    static final int MAX_PATHS = IslandRulePaths.all().size();
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:-]{1," + MAX_IDENTIFIER + "}");
    private static final Pattern PROTOCOL = Pattern.compile("(?:0|[1-9][0-9]{0,9})");
    final String protocol;
    final String business;
    final String scene;
    final List<String> paths;

    private IslandNotificationType(String protocol, String business, String scene, TreeSet<String> paths) {
        this.protocol = protocol;
        this.business = business;
        this.scene = scene;
        this.paths = Collections.unmodifiableList(new ArrayList<>(paths));
    }

    static IslandNotificationType create(String protocol, String business, String scene, List<String> paths) {
        if (protocol != null && (!PROTOCOL.matcher(protocol).matches()
                || protocol.length() == 10 && protocol.compareTo("2147483647") > 0)
                || business != null && !identifier(business) || scene != null && !identifier(scene)
                || paths.isEmpty() || paths.size() > MAX_PATHS) return null;
        TreeSet<String> validated = new TreeSet<>();
        for (String path : paths) {
            if (!IslandRulePaths.isRuntimeAllowed(IslandRulePaths.find(path)) || !validated.add(path)) return null;
        }
        return new IslandNotificationType(protocol, business, scene, validated);
    }

    static IslandNotificationType classify(Map<?, ?> root, String protocol, String business, String scene,
            Runnable checkpoint) {
        Map<?, ?> selected = IslandRuleExtractor.selectContainer(root, null);
        List<String> paths = new ArrayList<>();
        // 固定登记项遍历一次。此处不执行规则或释放值，提取时仍独立计费。
        for (IslandRulePaths.Entry entry : IslandRulePaths.all()) {
            if (checkpoint != null) checkpoint.run();
            if (IslandRulePaths.isRuntimeAllowed(entry) && hasContent(value(root, selected, entry), checkpoint)) {
                paths.add(entry.path);
            }
        }
        return create(protocol, identifier(business) ? business : null, identifier(scene) ? scene : null, paths);
    }

    static Object value(Map<?, ?> root, Map<?, ?> selected, IslandRulePaths.Entry entry) {
        Object value = "legacy".equals(entry.templateGroup) ? root : selected;
        for (String segment : entry.path.substring(1).split("/")) {
            if (!(value instanceof Map)) return null;
            value = ((Map<?, ?>) value).get(segment);
        }
        return value;
    }

    static boolean hasContent(Object value, Runnable checkpoint) {
        if (value instanceof Boolean || value instanceof IslandExportJson.NumberToken) return true;
        if (!(value instanceof String)) return false;
        String text = (String) value;
        for (int i = 0; i < text.length(); i++) {
            if ((i & 255) == 0 && checkpoint != null) checkpoint.run();
            char c = text.charAt(i);
            // 与呈现层一致地忽略纯空白或不可见控制符。
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c) && !Character.isISOControl(c)
                    && c != '\u200b' && c != '\u2060' && c != '\ufeff') return true;
        }
        return false;
    }

    private static boolean identifier(String value) {
        return value != null && value.length() <= MAX_IDENTIFIER && IDENTIFIER.matcher(value).matches();
    }

    Map<String, Object> json() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (protocol != null) result.put("protocol", protocol);
        if (business != null) result.put("business", business);
        if (scene != null) result.put("scene", scene);
        result.put("paths", paths);
        return result;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof IslandNotificationType)) return false;
        IslandNotificationType type = (IslandNotificationType) other;
        return Objects.equals(protocol, type.protocol) && Objects.equals(business, type.business)
                && Objects.equals(scene, type.scene) && paths.equals(type.paths);
    }

    @Override public int hashCode() { return Objects.hash(protocol, business, scene, paths); }
}
