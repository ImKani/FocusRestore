/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable, bounded data extracted for the custom focus banner renderer. */
final class FocusBannerModel {
    private static final int MAX_ACTIONS = 4;
    private static final int DEFAULT_PROGRESS_MAX = 100;

    final String title;
    final String text;
    final String iconKey;
    final List<ActionSpec> actions;
    final int progress;
    final int progressMax;
    final boolean progressIndeterminate;
    final TimerSpec timer;

    private FocusBannerModel(String title, String text, String iconKey,
                             List<ActionSpec> actions, int progress, int progressMax,
                             boolean progressIndeterminate, TimerSpec timer) {
        this.title = boundedText(title);
        this.text = boundedText(text);
        this.iconKey = boundedText(iconKey);
        this.actions = actions == null || actions.isEmpty()
                ? Collections.<ActionSpec>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(actions));
        this.progress = progress;
        this.progressMax = progressMax;
        this.progressIndeterminate = progressIndeterminate;
        this.timer = timer;
    }

    static FocusBannerModel parse(String payload) {
        if (!InputLimits.isPayloadAllowed(payload) || payload.trim().length() == 0
                || !hasBoundedNesting(payload)) return null;

        try {
            JSONObject root = new JSONObject(payload);
            JSONObject data = firstObject(root, "param_v2", "param_voip_v2");
            if (data == null) data = root;
            if (data == root && !looksLikeProtocolPayload(root)) return null;

            boolean legacy = isProtocolOne(root) || isProtocolOne(data)
                    || "verifyCode".equals(firstText(root, "scene"));
            String title = "";
            TextCollector text = new TextCollector(null);
            if (legacy) {
                title = firstText(root, "title");
                if (empty(title) && data != root) title = firstText(data, "title");
                text = new TextCollector(title);
                addFields(text, root, "desc1", "desc2", "content", "subContent");
                if (data != root) addFields(text, data, "desc1", "desc2", "content", "subContent");
            }

            if (!legacy || (empty(title) && text.isEmpty())) {
                title = firstTitle(data);
                text = new TextCollector(title);
                addStructuredText(data, text);
            }

            ProgressResult progressResult = readProgress(data);
            if (progressResult.display != null) text.add(progressResult.display);
            addProgressText(data, text);

            if (empty(title) && text.isEmpty()) {
                String ticker = firstText(data, "ticker");
                if (empty(ticker) && data != root) ticker = firstText(root, "ticker");
                if (!empty(ticker) && (data != root || hasProtocolMarker(root))) {
                    title = ticker;
                }
            }

            List<ActionSpec> actions = readActions(data);
            String iconKey = findIconKey(data);
            TimerSpec timer = findTimer(data);
            int progress = progressResult.found ? progressResult.progress : 0;
            int progressMax = progressResult.found ? progressResult.progressMax : 0;
            boolean indeterminate = !progressResult.found && progressResult.indeterminate;

            FocusBannerModel result = new FocusBannerModel(title, text.build(), iconKey, actions,
                    progress, progressMax, indeterminate, timer);
            return result.hasContent() ? result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hasBoundedNesting(String payload) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < payload.length(); i++) {
            char ch = payload.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') quoted = false;
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == '{' || ch == '[') {
                if (++depth > 64) return false;
            } else if (ch == '}' || ch == ']') {
                if (--depth < 0) return false;
            }
        }
        return !quoted && depth == 0;
    }

    boolean hasContent() {
        return !empty(title) || !empty(text) || !empty(iconKey)
                || !actions.isEmpty() || progressMax > 0 || progressIndeterminate || timer != null;
    }

    static final class ActionSpec {
        final String key;
        final String title;

        private ActionSpec(String key, String title) {
            this.key = boundedText(key);
            this.title = boundedText(title);
        }
    }

    static final class TimerSpec {
        final long when;
        final boolean countDown;
        final int clockType;
        final String prefix;
        final String suffix;

        private TimerSpec(long when, boolean countDown, int clockType,
                          String prefix, String suffix) {
            this.when = when;
            this.countDown = countDown;
            this.clockType = clockType;
            this.prefix = boundedText(prefix);
            this.suffix = boundedText(suffix);
        }
    }

    private static String firstTitle(JSONObject data) {
        String title = firstText(object(data, "baseInfo"), "title");
        if (!empty(title)) return title;
        title = firstText(object(data, "highlightInfo"), "title");
        if (!empty(title)) return title;
        title = firstText(object(data, "highlightInfoV3"), "primaryText");
        if (!empty(title)) return title;
        title = firstText(object(data, "chatInfo"), "title");
        if (!empty(title)) return title;
        title = firstText(object(data, "iconTextInfo"), "title");
        if (!empty(title)) return title;
        title = firstText(object(data, "animTextInfo"), "title");
        if (!empty(title)) return title;
        title = firstText(object(data, "coverInfo"), "title");
        if (!empty(title)) return title;
        title = firstText(object(data, "hintInfo"), "title");
        if (!empty(title)) return title;
        title = firstText(object(data, "stepInfo"), "title");
        if (!empty(title)) return title;
        title = firstText(data, "title");
        if (!empty(title)) return title;

        JSONObject island = firstObject(data, "param_island", "paramIsland");
        title = firstText(island, "title", "frontTitle");
        if (!empty(title)) return title;
        JSONObject big = object(island, "bigIslandArea");
        title = firstIslandTitle(big, "imageTextInfoLeft");
        if (!empty(title)) return title;
        title = firstIslandTitle(big, "imageTextInfoRight");
        if (!empty(title)) return title;
        // Some ROM payloads label the island on bigIslandArea.textInfo instead of a side area.
        return firstText(firstObject(big, "textInfo", "miui.focus.paramtextInfo"),
                "title", "frontTitle", "content");
    }

    private static String firstIslandTitle(JSONObject area, String key) {
        JSONObject side = object(area, key);
        JSONObject text = firstObject(side, "textInfo", "miui.focus.paramtextInfo");
        return firstText(text, "frontTitle", "title", "content");
    }

    private static void addStructuredText(JSONObject data, TextCollector text) {
        JSONObject base = object(data, "baseInfo");
        addFields(text, base, "subTitle", "specialTitle", "extraTitle", "content", "subContent");

        JSONObject highlight = object(data, "highlightInfo");
        addFields(text, highlight, "title", "content", "subContent");

        JSONObject highlightV3 = object(data, "highlightInfoV3");
        addFields(text, highlightV3, "primaryText", "secondaryText", "highLightText", "label");

        JSONObject chat = object(data, "chatInfo");
        addFields(text, chat, "title", "content");

        JSONObject iconText = object(data, "iconTextInfo");
        addFields(text, iconText, "title", "content", "subContent");

        JSONObject animText = object(data, "animTextInfo");
        addFields(text, animText, "title", "content");

        JSONObject cover = object(data, "coverInfo");
        addFields(text, cover, "title", "content", "subContent");

        JSONObject hint = object(data, "hintInfo");
        addFields(text, hint, "title", "subTitle", "content", "subContent");

        JSONObject step = object(data, "stepInfo");
        addFields(text, step, "title", "content", "subContent", "step");
        addStepText(text, step);

        addFields(text, data, "content", "subContent");
        addIslandText(text, firstObject(data, "param_island", "paramIsland"));
        // aodTitle is the always-on-display label, not the focus body. In the field sample it
        // duplicated the island title ("检票口 检票口"), so use it only when nothing else was found.
        if (text.isEmpty()) {
            addFields(text, data, "aodTitle");
        }
    }

    private static void addIslandText(TextCollector text, JSONObject island) {
        if (island == null) return;
        addFields(text, island, "title", "frontTitle", "content", "subContent");

        JSONObject big = object(island, "bigIslandArea");
        addIslandSideText(text, big, "imageTextInfoLeft");
        addIslandSideText(text, big, "imageTextInfoRight");
        addFields(text, big, "title", "content", "subContent");
        JSONObject bigText = firstObject(big, "textInfo", "miui.focus.paramtextInfo");
        addFields(text, bigText, "frontTitle", "title", "content", "subContent");

        JSONObject small = firstObject(island, "smallIslandArea", "smallIsland");
        addFields(text, small, "title", "frontTitle", "content", "subContent");
        JSONObject smallText = firstObject(small, "textInfo", "miui.focus.paramtextInfo");
        addFields(text, smallText, "frontTitle", "title", "content", "subContent");
    }

    private static void addIslandSideText(TextCollector text, JSONObject parent, String key) {
        JSONObject side = object(parent, key);
        addFields(text, side, "title", "frontTitle", "content", "subContent");
        JSONObject sideText = firstObject(side, "textInfo", "miui.focus.paramtextInfo");
        addFields(text, sideText, "frontTitle", "title", "content", "subContent");
    }

    private static void addStepText(TextCollector text, JSONObject step) {
        if (step == null) return;
        Long current = integerValue(step.opt("currentStep"));
        Long total = integerValue(step.opt("totalStep"));
        if (current != null && total != null && current >= 0 && total > 0 && current <= total) {
            text.add(current + "/" + total);
        }
    }

    private static void addProgressText(JSONObject data, TextCollector text) {
        addOneProgressText(data, text, "progressInfo");
        addOneProgressText(data, text, "multiProgressInfo");

        JSONObject island = firstObject(data, "param_island", "paramIsland");
        JSONObject big = object(island, "bigIslandArea");
        JSONObject progressText = object(big, "progressTextInfo");
        addFields(text, progressText, "title", "content", "label", "text");
        addOneProgressText(progressText, text, "progressInfo");
        addOneProgressText(big, text, "progressInfo");

        JSONObject small = firstObject(island, "smallIslandArea", "smallIsland");
        addOneProgressText(small, text, "progressInfo");
    }

    private static void addOneProgressText(JSONObject parent, TextCollector text, String key) {
        JSONObject progress = object(parent, key);
        addFields(text, progress, "title", "content", "label", "text", "progressText");
    }

    private static ProgressResult readProgress(JSONObject data) {
        ProgressResult result = new ProgressResult();
        readProgressObject(result, data, "progressInfo");
        readProgressObject(result, data, "multiProgressInfo");
        readProgressObject(result, data, "progress");

        JSONObject island = firstObject(data, "param_island", "paramIsland");
        JSONObject big = object(island, "bigIslandArea");
        JSONObject progressText = object(big, "progressTextInfo");
        readProgressObject(result, progressText, "progressInfo");
        readProgressObject(result, big, "progressInfo");

        JSONObject small = firstObject(island, "smallIslandArea", "smallIsland");
        readProgressObject(result, small, "progressInfo");
        readProgressObject(result, data, "stepInfo");
        return result;
    }

    private static void readProgressObject(ProgressResult result, JSONObject parent, String key) {
        Object raw = parent == null ? null : parent.opt(key);
        JSONObject object = raw instanceof JSONObject ? (JSONObject) raw : null;
        if ("progress".equals(key) && parent != null && raw != null && raw != JSONObject.NULL) {
            object = parent;
        }
        if (object == null) return;

        if (booleanValue(object.opt("indeterminate"))
                || booleanValue(object.opt("progressIndeterminate"))) {
            result.indeterminate = true;
        }

        Long progress = integerValue(object.opt("progress"));
        if (progress == null && object != parent) {
            progress = integerValue(object.opt("value"));
        }
        if (progress == null) return;

        Long maxValue = firstInteger(object, "progressMax", "maxProgress", "max", "total", "totalProgress");
        long max = maxValue == null ? DEFAULT_PROGRESS_MAX : maxValue;
        if (max <= 0 || max > Integer.MAX_VALUE || progress < 0 || progress > max) return;
        if (!result.found) {
            result.found = true;
            result.progress = (int) (long) progress;
            result.progressMax = (int) max;
            result.display = max == DEFAULT_PROGRESS_MAX
                    ? progress + "%"
                    : progress + "/" + max;
        }
    }

    private static List<ActionSpec> readActions(JSONObject data) {
        ArrayList<ActionSpec> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        addActionsFromValue(result, keys, data == null ? null : data.opt("actions"));
        addActionInfo(result, keys, data);

        String[] componentKeys = {"baseInfo", "highlightInfo", "highlightInfoV3", "chatInfo",
                "iconTextInfo", "animTextInfo", "coverInfo", "hintInfo", "stepInfo"};
        for (String componentKey : componentKeys) {
            JSONObject component = object(data, componentKey);
            if (component == null) continue;
            addActionsFromValue(result, keys, component.opt("actions"));
            addActionInfo(result, keys, component);
        }

        JSONObject island = firstObject(data, "param_island", "paramIsland");
        addActionInfo(result, keys, island);
        addActionInfo(result, keys, object(island, "bigIslandArea"));
        return result;
    }

    private static void addActionInfo(List<ActionSpec> result, Set<String> keys, JSONObject object) {
        if (object == null || result.size() >= MAX_ACTIONS) return;
        addActionsFromValue(result, keys, object.opt("actionInfo"));
    }

    private static void addActionsFromValue(List<ActionSpec> result, Set<String> keys, Object raw) {
        if (raw == null || raw == JSONObject.NULL || result.size() >= MAX_ACTIONS) return;
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int index = 0; index < array.length() && result.size() < MAX_ACTIONS; index++) {
                addAction(result, keys, array.opt(index));
            }
            return;
        }
        addAction(result, keys, raw);
    }

    private static void addAction(List<ActionSpec> result, Set<String> keys, Object raw) {
        if (raw == null || raw == JSONObject.NULL || result.size() >= MAX_ACTIONS) return;
        String key = null;
        String title = null;
        if (raw instanceof JSONObject) {
            JSONObject object = (JSONObject) raw;
            key = firstKey(object, "action", "key", "actionKey");
            title = firstText(object, "actionTitle", "title");
        } else {
            key = cleanKey(raw);
        }
        if (empty(key) || keys.contains(key)) return;
        keys.add(key);
        result.add(new ActionSpec(key, title));
    }

    private static TimerSpec findTimer(JSONObject data) {
        String[] components = {"baseInfo", "highlightInfo", "highlightInfoV3", "chatInfo",
                "iconTextInfo", "animTextInfo", "coverInfo", "hintInfo"};
        TimerSpec timer = timerFrom(data == null ? null : data.opt("timerInfo"));
        if (timer != null) return timer;
        for (String key : components) {
            timer = timerFrom(object(data, key) == null ? null : object(data, key).opt("timerInfo"));
            if (timer != null) return timer;
        }

        JSONObject island = firstObject(data, "param_island", "paramIsland");
        JSONObject big = object(island, "bigIslandArea");
        timer = timerFrom(object(big, "sameWidthDigitInfo") == null
                ? null : object(big, "sameWidthDigitInfo").opt("timerInfo"));
        if (timer != null) return timer;
        timer = timerFrom(object(big, "progressTextInfo") == null
                ? null : object(big, "progressTextInfo").opt("timerInfo"));
        if (timer != null) return timer;

        return timerFrom(object(island, "timerInfo"));
    }

    private static TimerSpec timerFrom(Object raw) {
        if (!(raw instanceof JSONObject)) return null;
        JSONObject object = (JSONObject) raw;
        Object rawWhen = object.opt("timerWhen");
        Object rawType = object.opt("timerType");
        Long when = rawWhen instanceof Long || rawWhen instanceof Integer
                ? ((Number) rawWhen).longValue() : null;
        Long type = rawType instanceof Long || rawType instanceof Integer
                ? ((Number) rawType).longValue() : null;
        if (when == null || type == null || when <= 0 || (type != -1 && type != 1)) return null;
        // The documented timerWhen is wall-clock epoch milliseconds. clockType=0 is
        // our model's normalized time base; timerType only selects the direction.
        // Paused/unknown modes and guessed elapsedRealtime fields are not rendered.
        return new TimerSpec(when, type == -1, 0,
                firstText(object, "prefix", "timerPrefix"),
                firstText(object, "suffix", "timerSuffix"));
    }

    private static String findIconKey(JSONObject data) {
        JSONObject island = firstObject(data, "param_island", "paramIsland");
        JSONObject small = firstObject(island, "smallIslandArea", "smallIsland");
        String icon = iconFromPicInfo(small);
        if (!empty(icon)) return icon;
        JSONObject big = object(island, "bigIslandArea");
        icon = iconFromPicInfo(object(big, "imageTextInfoLeft"));
        if (!empty(icon)) return icon;
        icon = iconFromPicInfo(object(big, "imageTextInfoRight"));
        if (!empty(icon)) return icon;
        icon = iconFromPicInfo(big);
        if (!empty(icon)) return icon;
        icon = iconFromPicInfo(island);
        if (!empty(icon)) return icon;
        icon = iconFromPicInfo(data);
        if (!empty(icon)) return icon;

        JSONObject iconText = object(data, "iconTextInfo");
        icon = iconFromPicInfo(iconText);
        if (!empty(icon)) return icon;
        icon = firstIcon(iconText, "iconKey", "icon", "picFunction");
        if (!empty(icon)) return icon;
        JSONObject animIcon = object(iconText, "animIconInfo");
        icon = firstIcon(animIcon, "src", "srcDark");
        if (!empty(icon)) return icon;

        String[] components = {"baseInfo", "highlightInfo", "highlightInfoV3", "chatInfo",
                "animTextInfo", "coverInfo", "hintInfo"};
        for (String key : components) {
            JSONObject component = object(data, key);
            icon = firstIcon(component, "picFunction", "picFunctionDark", "picProfile", "iconKey", "icon");
            if (!empty(icon)) return icon;
            icon = iconFromPicInfo(component);
            if (!empty(icon)) return icon;
        }
        icon = firstIcon(data, "tickerPic", "tickerPicDark");
        return empty(icon) ? "" : icon;
    }

    private static String iconFromPicInfo(JSONObject object) {
        if (object == null) return null;
        JSONObject picInfo = object(object, "picInfo");
        String icon = firstIcon(picInfo, "pic", "icon", "iconKey");
        if (!empty(icon)) return icon;
        JSONObject combine = object(object, "combinePicInfo");
        return iconFromPicInfo(combine);
    }

    private static String firstIcon(JSONObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            String value = cleanKey(object.opt(key));
            if (!empty(value) && !looksLikeExternalImage(value)) return value;
        }
        return null;
    }

    private static boolean looksLikeExternalImage(String value) {
        return value.indexOf("://") >= 0 || value.startsWith("/") || value.startsWith("data:");
    }

    private static boolean looksLikeProtocolPayload(JSONObject root) {
        if (root == null) return false;
        if (hasProtocolMarker(root)) return true;
        String[] keys = {"baseInfo", "highlightInfo", "highlightInfoV3", "chatInfo", "iconTextInfo",
                "animTextInfo", "coverInfo", "hintInfo", "progressInfo", "multiProgressInfo",
                "actions", "actionInfo", "param_island", "paramIsland", "smallIsland", "picInfo"};
        for (String key : keys) if (root.has(key)) return true;
        return false;
    }

    private static boolean hasProtocolMarker(JSONObject root) {
        return root != null && (root.has("protocol") || root.has("scene")
                || root.has("param_v2") || root.has("param_voip_v2"));
    }

    private static boolean isProtocolOne(JSONObject object) {
        Long protocol = object == null ? null : integerValue(object.opt("protocol"));
        return protocol != null && protocol == 1;
    }

    private static JSONObject object(JSONObject parent, String key) {
        return parent == null ? null : parent.optJSONObject(key);
    }

    private static JSONObject firstObject(JSONObject parent, String... keys) {
        if (parent == null) return null;
        for (String key : keys) {
            JSONObject value = parent.optJSONObject(key);
            if (value != null) return value;
        }
        return null;
    }

    private static void addFields(TextCollector target, JSONObject object, String... keys) {
        if (object == null) return;
        for (String key : keys) target.add(firstText(object, key));
    }

    private static String firstText(JSONObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            String value = cleanText(object.opt(key));
            if (!empty(value)) return value;
        }
        return null;
    }

    private static String cleanText(Object value) {
        if (value == null || value == JSONObject.NULL || value instanceof JSONObject
                || value instanceof JSONArray || value instanceof Boolean) return null;
        String result = String.valueOf(value).trim();
        if (result.length() == 0) return null;
        return InputLimits.limitOutput(result);
    }

    private static String firstKey(JSONObject object, String... names) {
        for (String name : names) {
            String value = cleanKey(object.opt(name));
            if (value != null) return value;
        }
        return null;
    }

    private static String cleanKey(Object value) {
        if (!(value instanceof String)) return null;
        String result = (String) value;
        // A reference must stay exact: trimming or truncation could select another action.
        return result.isEmpty() || result.length() > InputLimits.MAX_OUTPUT_CHARS
                ? null : result;
    }

    private static Long firstInteger(JSONObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            Long value = integerValue(object.opt(key));
            if (value != null) return value;
        }
        return null;
    }

    private static Long integerValue(Object value) {
        if (value == null || value == JSONObject.NULL || value instanceof JSONObject
                || value instanceof JSONArray || value instanceof Boolean) return null;
        String text = String.valueOf(value).trim();
        if (text.endsWith("%")) text = text.substring(0, text.length() - 1).trim();
        if (text.length() == 0) return null;
        try {
            if (text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) return null;
            return Long.valueOf(text);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Boolean booleanObjectValue(Object value) {
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(booleanObjectValue(value));
    }

    private static String boundedText(String value) {
        return value == null ? "" : InputLimits.limitOutput(value);
    }

    private static boolean empty(String value) {
        return value == null || value.length() == 0;
    }

    private static final class TextCollector {
        private final String primary;
        private final ArrayList<String> lines = new ArrayList<>();

        TextCollector(String primary) {
            this.primary = primary;
        }

        void add(String value) {
            value = cleanText(value);
            if (empty(value) || (!empty(primary) && primary.equals(value))) return;
            for (int index = 0; index < lines.size(); index++) {
                String old = lines.get(index);
                if (old.equals(value) || old.contains(value)) return;
                if (value.contains(old)) {
                    lines.set(index, value);
                    return;
                }
            }
            lines.add(value);
        }

        boolean isEmpty() {
            return lines.isEmpty();
        }

        String build() {
            if (lines.isEmpty()) return "";
            StringBuilder result = new StringBuilder();
            for (String line : lines) {
                if (result.length() > 0) result.append('\n');
                result.append(line);
                if (result.length() >= InputLimits.MAX_OUTPUT_CHARS) break;
            }
            return InputLimits.limitOutput(result.toString());
        }
    }

    private static final class ProgressResult {
        boolean found;
        boolean indeterminate;
        int progress;
        int progressMax;
        String display;
    }
}