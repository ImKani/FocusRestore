/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import org.json.JSONArray;
import org.json.JSONObject;

/** Package-private parser for HyperOS Dynamic Island payloads. */
final class IslandPayloadParser {
    /**
     * Nested payload containers, in priority order. Verified against the ROM: the SystemUI plugin
     * declares {@code param_v2}, {@code param_v3} and {@code param_voip_v2} in
     * {@code miui.systemui.notification.focus.Const$Param}. Only {@code param_v2} was read before,
     * so a notification that shipped its content solely under {@code param_v3} fell back to the
     * root object and lost every section.
     */
    private static final String[] CONTAINERS = {"param_v2", "param_v3", "param_voip_v2"};

    /** Content sections that carry user-visible text; reported by {@link #describeStructure}. */
    private static final String[] SECTIONS = {"baseInfo", "highlightInfo", "highlightInfoV3",
            "chatInfo", "iconTextInfo", "animTextInfo", "coverInfo", "hintInfo", "progressInfo",
            "multiProgressInfo", "stepInfo", "param_island"};

    private IslandPayloadParser() {
    }

    /** The nested container that holds the schema, or the root object when none is present. */
    private static JSONObject container(JSONObject root) {
        for (String key : CONTAINERS) {
            JSONObject nested = root.optJSONObject(key);
            if (nested != null) return nested;
        }
        return root;
    }

    /**
     * Structural summary of one payload for verbose diagnostics: byte length, which container was
     * used, protocol/scene and every section that is actually present. It never throws, so it is
     * safe to log for any payload the parser refused.
     */
    static String describeStructure(String payload) {
        if (payload == null) return "payload=null";
        if (!InputLimits.isPayloadAllowed(payload) || payload.trim().length() == 0) {
            return "bytes=" + payload.length() + " payload=empty";
        }
        try {
            JSONObject root = new JSONObject(payload);
            StringBuilder text = new StringBuilder("bytes=").append(payload.length());
            for (String key : CONTAINERS) {
                if (root.optJSONObject(key) != null) text.append(' ').append(key).append("=yes");
            }
            JSONObject v2 = container(root);
            text.append(" container=").append(v2 == root ? "root" : "nested");
            text.append(" protocol=").append(root.optString("protocol", "-"));
            text.append(" scene=").append(root.optString("scene", "-"));
            for (String key : SECTIONS) {
                if (v2.optJSONObject(key) != null) text.append(' ').append(key).append("=yes");
            }
            JSONObject island = v2.optJSONObject("param_island");
            if (island == null) {
                text.append(" island=none");
            } else if (island.optJSONObject("bigIslandArea") != null) {
                text.append(" island=big");
            } else if (island.optJSONObject("smallIslandArea") != null) {
                text.append(" island=small");
            } else {
                text.append(" island=empty");
            }
            if (!empty(root.optString("title", null))) text.append(" legacyTitle=yes");
            return text.toString();
        } catch (Throwable ignored) {
            return "bytes=" + payload.length() + " unparsable";
        }
    }

    static ParsedText parse(String payload, String generalSeparator, String sideSeparator) {
        if (!InputLimits.isPayloadAllowed(payload) || payload.trim().length() == 0) return null;
        String general = separator(generalSeparator);
        String side = separator(sideSeparator);
        try {
            JSONObject root = new JSONObject(payload);
            JSONObject v2 = container(root);

            // Legacy protocol-1 fields. They are only a last resort: payloads such as the MIUI
            // travel assistant use protocol=1 *and* ship a complete param_island block, so an
            // early return here would discard the only meaningful content.
            String legacyText = joinTexts(root, general, "title", "desc1", "desc2");
            String legacySource = "protocol1:" + root.optString("scene", "legacy");

            JSONObject base = v2.optJSONObject("baseInfo");
            String result = joinTexts(base, general, "title", "subTitle", "specialTitle",
                    "extraTitle", "content", "subContent");
            String source = empty(result) ? null : "baseInfo";

            if (empty(result)) {
                result = joinTexts(v2.optJSONObject("highlightInfo"), general,
                        "title", "content", "subContent");
                if (!empty(result)) source = "highlightInfo";
            }
            if (empty(result)) {
                result = joinTexts(v2.optJSONObject("highlightInfoV3"), general,
                        "primaryText", "secondaryText", "highLightText", "label");
                if (!empty(result)) source = "highlightInfoV3";
            }
            if (empty(result)) {
                result = joinTexts(v2.optJSONObject("chatInfo"), general, "title", "content");
                if (!empty(result)) source = "chatInfo";
            }
            if (empty(result)) {
                JSONObject icon = v2.optJSONObject("iconTextInfo");
                result = joinCompact(firstText(icon, "title"), firstText(icon, "content"), general);
                result = joinCompact(result, firstText(icon, "subContent"), general);
                if (!empty(result)) source = "iconTextInfo";
            }
            if (empty(result)) {
                result = joinTexts(v2.optJSONObject("animTextInfo"), general, "title", "content");
                if (!empty(result)) source = "animTextInfo";
            }
            if (empty(result)) {
                result = joinTexts(v2.optJSONObject("coverInfo"), general,
                        "title", "content", "subContent");
                if (!empty(result)) source = "coverInfo";
            }

            String hintText = joinTexts(v2.optJSONObject("hintInfo"), general,
                    "title", "subTitle", "content", "subContent");
            if (empty(result) && !empty(hintText)) source = "hintInfo";
            result = appendDistinctText(result, hintText, general);

            String multiProgress = progressText(v2.optJSONObject("multiProgressInfo"), general);
            if (empty(result) && !empty(multiProgress)) source = "multiProgressInfo";
            result = appendDistinctText(result, multiProgress, general);

            String progress = progressText(v2.optJSONObject("progressInfo"), general);
            if (empty(result) && !empty(progress)) source = "progressInfo";
            result = appendDistinctText(result, progress, general);

            String step = joinTexts(v2.optJSONObject("stepInfo"), general,
                    "title", "content", "subContent", "step");
            if (empty(result) && !empty(step)) source = "stepInfo";
            result = appendDistinctText(result, step, general);

            String islandText = findIslandText(v2.optJSONObject("param_island"), general, side);
            if (base != null && !empty(result)) {
                String islandTitle = findPrimaryIslandTitle(v2.optJSONObject("param_island"));
                if (!empty(islandTitle) && !result.startsWith(islandTitle)) {
                    result = joinText(islandTitle, result, general);
                }
            }
            if (empty(result) && !empty(islandText)) source = "param_island";
            result = appendDistinctText(result, islandText, general);

            if (empty(result)) {
                // aodTitle names the always-on-display surface, not the focus body. Appending it
                // unconditionally duplicated the island title in the visible text: the MIUI travel
                // payload carries island title "检票口" plus aodTitle "检票口 检票口", which produced
                // "D8396·检票口·检票口 检票口". Only use it when nothing else produced text.
                result = firstText(v2, "aodTitle");
                if (!empty(result)) source = "aodTitle";
            }
            if (empty(result)) {
                result = legacyText;
                if (!empty(result)) source = legacySource;
            }
            if (!empty(result)) return new ParsedText(result, source == null ? "composite" : source);

            result = clean(v2.optString("ticker", null));
            if (!empty(result)) return new ParsedText(result, "ticker");
            if (v2 != root) {
                result = clean(root.optString("ticker", null));
                if (!empty(result)) return new ParsedText(result, "custom.ticker");
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    static String findTickerPictureReference(String payload, boolean dark) {
        if (!InputLimits.isPayloadAllowed(payload) || payload.trim().length() == 0) return null;
        try {
            JSONObject root = new JSONObject(payload);
            JSONObject v2 = container(root);
            String result = cleanPictureReference(v2.opt(dark ? "tickerPicDark" : "tickerPic"));
            if (result == null && dark) {
                result = cleanPictureReference(v2.opt("tickerPic"));
            }
            if (result == null && v2 != root) {
                result = cleanPictureReference(root.opt(dark ? "tickerPicDark" : "tickerPic"));
                if (result == null && dark) result = cleanPictureReference(root.opt("tickerPic"));
            }
            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String findPictureReference(String payload, boolean dark) {
        if (!InputLimits.isPayloadAllowed(payload) || payload.trim().length() == 0) return null;
        try {
            JSONObject root = new JSONObject(payload);
            JSONObject v2 = container(root);
            String result = findKnownPictureReference(v2, dark);
            if (result == null && dark) result = findKnownPictureReference(v2, false);
            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String findKnownPictureReference(JSONObject v2, boolean dark) {
        JSONObject island = firstObject(v2, "param_island", "paramIsland");
        if (island != null) {
            String result = findPictureInNode(island.opt("smallIslandArea"), dark, 0, false);
            if (result != null) return result;
            result = findPictureInNode(island.opt("bigIslandArea"), dark, 0, false);
            if (result != null) return result;
        }
        String[] templateKeys = {"baseInfo", "highlightInfo", "highlightInfoV3", "chatInfo",
                "iconTextInfo", "animTextInfo", "bannerPicInfo", "hintInfo"};
        for (String key : templateKeys) {
            String result = findPictureInNode(v2.opt(key), dark, 0, false);
            if (result != null) return result;
        }
        return findPictureInNode(v2, dark, 0, false);
    }

    private static String findPictureInNode(Object value, boolean dark, int depth,
                                            boolean allowAnimationSource) {
        if (value == null || value == JSONObject.NULL || depth > 8) return null;
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                String result = findPictureInNode(array.opt(index), dark, depth + 1,
                        allowAnimationSource);
                if (result != null) return result;
            }
            return null;
        }
        if (!(value instanceof JSONObject)) return null;
        JSONObject object = (JSONObject) value;
        String[] directKeys = dark
                ? new String[]{"picDark", "picFunctionDark", "picProfileDark",
                "iconDark"}
                : new String[]{"pic", "picFunction", "picProfile", "icon"};
        for (String key : directKeys) {
            String result = cleanPictureReference(object.opt(key));
            if (result != null) return result;
        }
        if (allowAnimationSource && !dark) {
            String result = cleanPictureReference(object.opt("src"));
            if (result != null) return result;
        }
        String[] nestedKeys = {"picInfo", "combinePicInfo", "imageTextInfoLeft",
                "imageTextInfoRight", "animIconInfo"};
        for (String key : nestedKeys) {
            String result = findPictureInNode(object.opt(key), dark, depth + 1,
                    "animIconInfo".equals(key));
            if (result != null) return result;
        }
        return null;
    }

    /**
     * 把载荷引用名转成"可以拿去应用包里查 drawable 的名字"：
     * {@code miui.focus.pic_weather} → {@code weather}。
     *
     * <p>只认契约前缀，其余一律返回 null —— 不做模糊猜测。这条兜底本身很可能落空（第三方应用
     * 一般不会用 {@code miui.focus.pic_*} 这个名字定义资源），但推导规则必须可预测、可单测，
     * 失败时也应该干净地退到通知 smallIcon / 应用图标，而不是按一个猜出来的名字乱查。
     */
    static String drawableNameFromReference(String reference) {
        if (reference == null) return null;
        String trimmed = reference.trim();
        String prefix = "miui.focus.pic_";
        if (!trimmed.startsWith(prefix)) return null;
        String name = trimmed.substring(prefix.length());
        return name.length() == 0 ? null : name;
    }

    private static String cleanPictureReference(Object value) {
        // 来源：用户 0.29.2 设备日志中的出行载荷与 miui.focus.pics 键；协议所有者、
        // 版本及许可未确认。本项目独立按键精确读取，不复制外部实现。
        // Bundle 引用是键名，不是 drawable 名；island_pic_small 也属于有效的精确引用。
        // 仅接受标识符字符串，避免把 URL、对象或空值当成图标；资源名兜底仍单独限制前缀。
        if (!(value instanceof String)) return null;
        String reference = ((String) value).trim();
        return reference.matches("[A-Za-z0-9_.-]+") ? reference : null;
    }

    static ParsedText parseCompact(String payload, String sideSeparator) {
        if (!InputLimits.isPayloadAllowed(payload) || payload.trim().length() == 0) return null;
        try {
            JSONObject root = new JSONObject(payload);
            JSONObject v2 = container(root);
            JSONObject island = v2.optJSONObject("param_island");
            if (island == null) return null;
            String side = separator(sideSeparator);
            JSONObject big = island.optJSONObject("bigIslandArea");
            String left = islandSideText(big == null ? null : big.optJSONObject("imageTextInfoLeft"),
                    side);
            String right = islandSideText(big == null ? null : big.optJSONObject("imageTextInfoRight"),
                    side);
            if (empty(right)) {
                // The travel payload carries its primary label on bigIslandArea.textInfo instead of an
                // explicit imageTextInfoRight, and the ROM's own collapsed pill still shows it as the
                // right side (left image-text plus this label). Dropping it here lost half the pill.
                String areaText = islandSideText(big, side);
                if (!empty(areaText) && !areaText.equals(left)) right = areaText;
            }
            if (empty(left) && empty(right)) {
                left = islandSideText(island.optJSONObject("smallIslandArea"), side);
                right = null;
            }
            String result = joinText(left, right, side);
            return empty(result) ? null : new ParsedText(result, "param_island.compact");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String islandSideText(JSONObject area, String sideSeparator) {
        if (area == null) return null;
        JSONObject text = firstObject(area, "textInfo", "miui.focus.paramtextInfo");
        String result = joinTexts(text == null ? area : text, sideSeparator,
                "frontTitle", "title", "content", "subContent", "label");
        return empty(result) ? firstText(area, "title", "content", "subContent", "label") : result;
    }

    static final class ParsedText {
        final String text;
        final String source;

        ParsedText(String text, String source) {
            this.text = InputLimits.limitOutput(text);
            this.source = source;
        }
    }

    private static String separator(String value) {
        return InputLimits.limitSeparator(value == null ? "" : value);
    }

    private static String joinTexts(JSONObject object, String sep, String... keys) {
        if (object == null) return null;
        String result = null;
        for (String key : keys) result = joinText(result, firstText(object, key), sep);
        return result;
    }

    private static String progressText(JSONObject object, String sep) {
        if (object == null) return null;
        String result = joinText(firstText(object, "title", "content", "label"), percentageText(object), sep);
        if (!empty(result)) return result;
        JSONObject nested = object.optJSONObject("progressInfo");
        return nested == null ? null : joinText(firstText(nested, "title", "content", "label"), percentageText(nested), sep);
    }

    private static String percentageText(JSONObject object) {
        if (object == null || !object.has("progress")) return null;
        Object value = object.opt("progress");
        if (value == null || value == JSONObject.NULL) return null;
        String text = clean(String.valueOf(value));
        return empty(text) ? null : (text.endsWith("%") ? text : text + "%");
    }

    private static String findIslandText(JSONObject island, String general, String side) {
        if (island == null) return null;
        String result = joinTexts(island, general, "title", "content", "frontTitle");
        JSONObject big = island.optJSONObject("bigIslandArea");
        JSONObject left = big == null ? null : big.optJSONObject("imageTextInfoLeft");
        JSONObject text = left == null ? null : firstObject(left, "textInfo", "miui.focus.paramtextInfo");
        String leftText = joinTexts(text, general, "frontTitle", "title", "content", "subContent");
        JSONObject right = big == null ? null : big.optJSONObject("imageTextInfoRight");
        text = right == null ? null : firstObject(right, "textInfo", "miui.focus.paramtextInfo");
        String rightText = joinTexts(text, general, "frontTitle", "title", "content", "subContent");
        result = appendDistinctText(result, appendDistinctText(leftText, rightText, side), general);
        // Some ROM payloads put the primary label directly on bigIslandArea.textInfo instead of a
        // side area (field sample: personalassistant travel, title=检票口, left side=D8396).
        result = appendDistinctText(result, joinTexts(big == null ? null : firstObject(big,
                "textInfo", "miui.focus.paramtextInfo"), general,
                "frontTitle", "title", "content", "subContent"), general);
        result = appendDistinctText(result, progressText(big == null ? null : firstObject(big,
                "progressTextInfo", "fixedWidthDigitInfo", "sameWidthDigitInfo"), general), general);
        result = appendDistinctText(result, joinTexts(island.optJSONObject("smallIslandArea"), general,
                "title", "content", "subContent"), general);
        return result;
    }

    private static String findPrimaryIslandTitle(JSONObject island) {
        if (island == null) return null;
        JSONObject big = island.optJSONObject("bigIslandArea");
        JSONObject left = big == null ? null : big.optJSONObject("imageTextInfoLeft");
        JSONObject text = left == null ? null : firstObject(left, "textInfo", "miui.focus.paramtextInfo");
        String title = firstText(text, "title", "frontTitle", "content");
        if (empty(title)) {
            title = firstText(big == null ? null : firstObject(big, "textInfo",
                    "miui.focus.paramtextInfo"), "title", "frontTitle", "content");
        }
        return title;
    }

    private static JSONObject firstObject(JSONObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            JSONObject value = object.optJSONObject(key);
            if (value != null) return value;
        }
        return null;
    }

    private static String firstText(JSONObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            Object raw = object.opt(key);
            if (raw == null || raw == JSONObject.NULL
                    || raw instanceof JSONObject || raw instanceof org.json.JSONArray) continue;
            String value = clean(String.valueOf(raw));
            if (!empty(value)) return value;
        }
        return null;
    }

    private static String joinCompact(String first, String second, String sep) {
        return joinText(first, second, sep);
    }

    private static String joinText(String first, String second, String sep) {
        first = clean(first);
        second = clean(second);
        if (empty(first)) return second;
        if (empty(second) || first.equals(second)) return first;
        return first + sep + second;
    }

    private static String appendDistinctText(String first, String second, String sep) {
        first = clean(first);
        second = clean(second);
        if (empty(second)) return first;
        if (empty(first) || first.equals(second) || first.contains(second)) return empty(first) ? second : first;
        if (second.contains(first)) return second;
        return first + sep + second;
    }

    private static String clean(String value) {
        if (value == null) return null;
        value = value.trim();
        if (value.length() == 0 || "Copy".equalsIgnoreCase(value)
                || "稍后提醒".equals(value)) return null;
        return InputLimits.limitOutput(value);
    }

    private static boolean empty(String value) {
        return value == null || value.length() == 0;
    }
}