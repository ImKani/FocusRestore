/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Versioned user settings only; never reads or writes either preference store. */
final class SettingsBackupCodec {
    static final String FORMAT = "FocusRestore.settings";
    static final int FORMAT_VERSION = 1;
    // 两份最大包名集合即使全部使用 Unicode 转义也能往返；读取仍受字节和结构配额约束。
    static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    // 导入集合会进入 Activity saved state；两组共同限制，给 Bundle 条目开销和其他设置留余量。
    static final int MAX_PACKAGE_SET_CHARS = 64 * 1024;
    private static final Pattern NUMBER = Pattern.compile(
            "-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private static final Pattern INTEGER = Pattern.compile("-?(?:0|[1-9][0-9]*)");
    private static final Pattern DECIMAL_TEXT = Pattern.compile(
            "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private static final Set<String> INTEGER_FIELDS = new HashSet<>(Arrays.asList("formatVersion",
            "hook_mode", "island_text_mode", "text_width_dp", "text_width_landscape_dp",
            "marquee_delay_ms", "media_focus_cast_picker", "focus_icon_priority", "touch_width_dp", "touch_width_landscape_dp",
            "focus_icon_size_dp", "focus_icon_text_gap_dp", "focus_divider_start_offset_dp", "focus_divider_end_offset_dp",
            "focus_divider_start_dp", "focus_divider_end_dp", "focus_divider_width_dp", "focus_divider_style"));
    private SettingsBackupCodec() { }

    static final class InvalidBackupException extends IOException {
        InvalidBackupException(String reason) { super(reason); }
        InvalidBackupException(String reason, Throwable cause) { super(reason, cause); }
    }

    static FocusRestoreSettings read(InputStream input) throws IOException {
        if (input == null) throw invalid("无法打开配置文件");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkInterrupted();
            if (bytes.size() > MAX_FILE_BYTES - count) throw invalid("配置文件超过 16 MiB");
            bytes.write(buffer, 0, count);
        }
        checkInterrupted();
        return decode(bytes.toByteArray());
    }

    static FocusRestoreSettings decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw invalid("配置文件为空");
        if (bytes.length > MAX_FILE_BYTES) throw invalid("配置文件超过 16 MiB");
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new InvalidBackupException("配置文件不是有效 UTF-8", malformed);
        }
        // 平台 org.json 会接受注释、单引号、重复键等宽松语法；先校验再解码，JVM/Android行为一致。
        try {
            new StrictJson(text).validate();
            JSONObject root = new JSONObject(text);
            keys(root, "root", "format", "formatVersion", "appVersion", "settings");
            // 软件版本仅说明导出来源；手工修改不影响格式判断，也不能改变设置解析规则。
            validateAppVersion(root.opt("appVersion"));
            if (!FORMAT.equals(root.opt("format"))) throw invalid("无法识别配置文件格式");
            if (!root.has("formatVersion") || integer(root, "formatVersion", -1) != FORMAT_VERSION) {
                throw invalid("不支持此配置文件版本");
            }
            JSONObject groups = object(root, "settings", false);
            keys(groups, "settings", "system", "island", "focus", "media", "interaction",
                    "icons", "marquee", "compatibility");
            JSONObject system = group(groups, "system", "hook_mode");
            JSONObject island = group(groups, "island", "island_compat", "island_text_mode",
                    "island_general_separator", "island_side_separator", "island_separator",
                    "island_force_packages");
            JSONObject focus = group(groups, "focus", "limit_text_width", "text_width_dp",
                    "text_width_landscape_dp", "focus_max_display_seconds",
                    "focus_timeout_exempt_packages", "hide_notification_icons", "show_focus_divider",
                    "focus_divider_start_offset_dp", "focus_divider_end_offset_dp", "focus_divider_start_dp", "focus_divider_end_dp", "focus_divider_width_dp", "focus_divider_style");
            JSONObject media = group(groups, "media", "media_focus_enabled", "media_focus_native_banner",
                    "media_focus_cast_picker", "media_focus_cast_direct");
            JSONObject interaction = group(groups, "interaction", "independent_focus_banner",
                    "special_banner_normal_background", "allow_focus_click",
                    "notification_row_click_fallback", "long_press_notification_row_click",
                    "long_press_seconds", "focus_swipe_enabled",
                    "custom_touch_width", "touch_width_dp", "touch_width_landscape_dp");
            JSONObject icons = group(groups, "icons", "show_island_icon", "tint_island_icon",
                    "use_small_icon_fallback", "focus_icon_priority", "tint_device_icon", "focus_icon_size_dp", "focus_icon_text_gap_dp");
            JSONObject marquee = group(groups, "marquee", "marquee_bounce", "marquee_delay_ms");
            JSONObject compatibility = group(groups, "compatibility", "compat_retry",
                    "disable_island_property", "disable_island_feature_cache");
            FocusRestoreSettings defaults = FocusRestoreSettings.defaults();
            int mode = integer(system, "hook_mode", defaults.hookMode);
            int picker = integer(media, "media_focus_cast_picker", defaults.mediaFocusCastPicker);
            int textMode = integer(island, "island_text_mode", defaults.islandTextMode);
            if (mode != 3 && mode != 4) throw invalid("hook_mode 必须为 3 或 4");
            if (picker != 1 && picker != 2) throw invalid("media_focus_cast_picker 必须为 1 或 2");
            if (textMode != 0 && textMode != 1) throw invalid("island_text_mode 必须为 0 或 1");
            int iconPriority = integer(icons, "focus_icon_priority",
                    FocusRestoreSettings.legacyIconPriority(bool(icons, "use_small_icon_fallback", false),
                            bool(icons, "show_island_icon", false)));
            if (iconPriority < 0 || iconPriority >= FocusRestoreSettings.ICON_ORDER_COUNT) {
                throw invalid("focus_icon_priority 必须为 0～5 的整数");
            }
            int iconSize = integer(icons, "focus_icon_size_dp", defaults.focusIconSizeDp);
            if (iconSize < 0 || iconSize > 32) throw invalid("focus_icon_size_dp 必须为 0 或 1～32");
            int iconTextGap = rangedInteger(icons, "focus_icon_text_gap_dp", defaults.focusIconTextGapDp, -1, 24);
            int dividerStart = backupDivider(focus, "focus_divider_start_offset_dp", "focus_divider_start_dp", defaults.focusDividerStartDp);
            int dividerEnd = backupDivider(focus, "focus_divider_end_offset_dp", "focus_divider_end_dp", defaults.focusDividerEndDp);
            int dividerWidth = rangedInteger(focus, "focus_divider_width_dp", defaults.focusDividerWidthDp, 0, 4);
            int dividerStyle = rangedInteger(focus, "focus_divider_style", defaults.focusDividerStyle, 0, 3);
            int width = integer(focus, "text_width_dp", defaults.widthDp);
            String legacySeparator = separator(island, "island_separator", defaults.islandGeneralSeparator);
            Set<String> forcePackages = packages(island, "island_force_packages");
            Set<String> timeoutExemptPackages = packages(focus, "focus_timeout_exempt_packages");
            checkPackageBudget(forcePackages, timeoutExemptPackages);
            // 旧字段迁移与持久设置读取一致：横屏继承竖屏，两种连接符分别回退旧连接符。
            return FocusRestoreSettings.edit()
                    .hookMode(mode)
                    .limitWidth(bool(focus, "limit_text_width", defaults.limitWidth))
                    .widthDp(width)
                    .widthLandscapeDp(integer(focus, "text_width_landscape_dp",
                            focus.has("text_width_dp") ? width : defaults.widthLandscapeDp))
                    .marqueeDelayMs(integer(marquee, "marquee_delay_ms", defaults.marqueeDelayMs))
                    .marqueeBounce(bool(marquee, "marquee_bounce", defaults.marqueeBounce))
                    .compatRetry(bool(compatibility, "compat_retry", defaults.compatRetry))
                    .disableIslandProperty(bool(compatibility, "disable_island_property", defaults.disableIslandProperty))
                    .disableIslandFeatureCache(bool(compatibility, "disable_island_feature_cache", defaults.disableIslandFeatureCache))
                    .islandCompat(bool(island, "island_compat", defaults.islandCompat))
                    .islandTextMode(textMode)
                    .islandGeneralSeparator(separator(island, "island_general_separator", legacySeparator))
                    .islandSideSeparator(separator(island, "island_side_separator", legacySeparator))
                    .islandForcePackages(forcePackages)
                    .focusMaxDisplaySeconds(seconds(focus, "focus_max_display_seconds", defaults.focusMaxDisplaySeconds))
                    .focusTimeoutExemptPackages(timeoutExemptPackages)
                    .hideNotificationIcons(bool(focus, "hide_notification_icons", defaults.hideNotificationIcons))
                    .showFocusDivider(bool(focus, "show_focus_divider", defaults.showFocusDivider))
                    .focusIconSizeDp(iconSize).focusIconTextGapDp(iconTextGap).focusDividerStartDp(dividerStart).focusDividerEndDp(dividerEnd)
                    .focusDividerWidthDp(dividerWidth).focusDividerStyle(dividerStyle)
                    .mediaFocusEnabled(bool(media, "media_focus_enabled", defaults.mediaFocusEnabled))
                    .mediaFocusNativeBanner(bool(media, "media_focus_native_banner", defaults.mediaFocusNativeBanner))
                    .mediaFocusCastPicker(picker)
                    .mediaFocusCastDirect(bool(media, "media_focus_cast_direct", defaults.mediaFocusCastDirect))
                    .independentFocusBanner(bool(interaction, "independent_focus_banner", defaults.independentFocusBanner))
                    .specialBannerNormalBackground(bool(interaction, "special_banner_normal_background", defaults.specialBannerNormalBackground))
                    .allowFocusClick(bool(interaction, "allow_focus_click", defaults.allowFocusClick))
                    .notificationRowClickFallback(bool(interaction, "notification_row_click_fallback", defaults.notificationRowClickFallback))
                    .longPressNotificationRowClick(bool(interaction, "long_press_notification_row_click", defaults.longPressNotificationRowClick))
                    .longPressSeconds(seconds(interaction, "long_press_seconds", defaults.longPressSeconds))
                    .focusSwipeEnabled(bool(interaction, "focus_swipe_enabled", defaults.focusSwipeEnabled))
                    .customTouchWidth(bool(interaction, "custom_touch_width", defaults.customTouchWidth))
                    .touchWidthDp(integer(interaction, "touch_width_dp", defaults.touchWidthDp))
                    .touchWidthLandscapeDp(integer(interaction, "touch_width_landscape_dp", defaults.touchWidthLandscapeDp))
                    .focusIconPriority(iconPriority)
                    .tintDeviceIcon(bool(icons, "tint_device_icon", defaults.tintDeviceIcon))
                    .showIslandIcon(bool(icons, "show_island_icon", defaults.showIslandIcon))
                    .tintIslandIcon(bool(icons, "tint_island_icon", defaults.tintIslandIcon))
                    .useSmallIconFallback(bool(icons, "use_small_icon_fallback", defaults.useSmallIconFallback))
                    .build();
        } catch (JSONException malformed) {
            throw new InvalidBackupException("配置 JSON 结构无效", malformed);
        }
    }

    static byte[] encode(FocusRestoreSettings settings) throws IOException {
        if (settings == null) throw invalid("没有可导出的设置");
        checkPackageBudget(settings.islandForcePackages, settings.focusTimeoutExemptPackages);
        for (String name : settings.islandForcePackages) validatePackageName(name, "island_force_packages");
        for (String name : settings.focusTimeoutExemptPackages) validatePackageName(name, "focus_timeout_exempt_packages");
        Map<String, Object> groups = entries(
                "system", entries("hook_mode", settings.hookMode),
                "island", entries("island_compat", settings.islandCompat,
                        "island_text_mode", settings.islandTextMode,
                        "island_general_separator", settings.islandGeneralSeparator,
                        "island_side_separator", settings.islandSideSeparator,
                        "island_force_packages", new TreeSet<>(settings.islandForcePackages)),
                "focus", entries("limit_text_width", settings.limitWidth,
                        "text_width_dp", settings.widthDp, "text_width_landscape_dp", settings.widthLandscapeDp,
                        "focus_max_display_seconds", settings.focusMaxDisplaySeconds,
                        "focus_timeout_exempt_packages", new TreeSet<>(settings.focusTimeoutExemptPackages),
                        "hide_notification_icons", settings.hideNotificationIcons,
                        "show_focus_divider", settings.showFocusDivider,
                         "focus_divider_start_offset_dp", settings.focusDividerStartDp,
                         "focus_divider_end_offset_dp", settings.focusDividerEndDp,
                         "focus_divider_width_dp", settings.focusDividerWidthDp,
                         "focus_divider_style", settings.focusDividerStyle),
                "media", entries("media_focus_enabled", settings.mediaFocusEnabled,
                        "media_focus_native_banner", settings.mediaFocusNativeBanner,
                        "media_focus_cast_picker", settings.mediaFocusCastPicker,
                        "media_focus_cast_direct", settings.mediaFocusCastDirect),
                "interaction", entries("independent_focus_banner", settings.independentFocusBanner,
                        "special_banner_normal_background", settings.specialBannerNormalBackground,
                        "allow_focus_click", settings.allowFocusClick,
                        "notification_row_click_fallback", settings.notificationRowClickFallback,
                        "long_press_notification_row_click", settings.longPressNotificationRowClick,
                        "long_press_seconds", settings.longPressSeconds,
                        "focus_swipe_enabled", settings.focusSwipeEnabled,
                        "custom_touch_width", settings.customTouchWidth,
                        "touch_width_dp", settings.touchWidthDp,
                        "touch_width_landscape_dp", settings.touchWidthLandscapeDp),
                "icons", entries("show_island_icon", settings.showIslandIcon,
                        "tint_island_icon", settings.tintIslandIcon,
                        "use_small_icon_fallback", settings.useSmallIconFallback,
                        "focus_icon_priority", settings.focusIconPriority,
                        "tint_device_icon", settings.tintDeviceIcon, "focus_icon_size_dp", settings.focusIconSizeDp,
                        "focus_icon_text_gap_dp", settings.focusIconTextGapDp),
                "marquee", entries("marquee_bounce", settings.marqueeBounce,
                        "marquee_delay_ms", settings.marqueeDelayMs),
                "compatibility", entries("compat_retry", settings.compatRetry,
                        "disable_island_property", settings.disableIslandProperty,
                        "disable_island_feature_cache", settings.disableIslandFeatureCache));
        String appVersion = encodedAppVersion();
        StringBuilder text = new StringBuilder();
        append(text, entries("format", FORMAT, "formatVersion", FORMAT_VERSION,
                "appVersion", appVersion,
                "settings", groups), 0);
        text.append('\n');
        final byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException malformed) {
            throw new InvalidBackupException("设置包含无效 Unicode 文本", malformed);
        }
        if (bytes.length > MAX_FILE_BYTES) throw invalid("导出配置超过 16 MiB");
        return bytes;
    }

    static String defaultFileName(long timeMillis) {
        // 使用设备本地时间，固定数字格式避免系统语言改变文件名字符。
        return "FocusRestore-" + new SimpleDateFormat("yyyy-MM-dd-HH_mm_ss", Locale.US)
                .format(new Date(timeMillis)) + ".json";
    }

    private static void validateAppVersion(Object value) throws IOException {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()
                || ((String) value).length() > 64) {
            throw invalid("appVersion 必须是非空且最多 64 字符的软件版本文本");
        }
        String version = (String) value;
        for (int index = 0; index < version.length(); index++) {
            if (Character.isISOControl(version.charAt(index))) throw invalid("appVersion 不能包含控制符");
        }
    }

    /**
     * 导出必须满足自己的导入校验（非空、≤64 字符、无控制符），否则一次版本名改动就会
     * 导出自己无法读回的备份。当前版本名合法时行为与原来完全一致；越界时降级为版本号文本，
     * 不因诊断字段使整次导出失败。
     */
    private static String encodedAppVersion() {
        String name = BuildConfig.VERSION_NAME;
        // 校验对象必须是最终写进备份的文本：名称本身合法、但拼上 "(versionCode)" 后超过上限时，
        // 同样会被自己的导入校验拒绝，所以这里先拼再判长度与控制符。
        String encoded = name == null || name.trim().isEmpty()
                ? null : name + "(" + BuildConfig.VERSION_CODE + ")";
        if (encoded != null && encoded.length() <= 64) {
            boolean clean = true;
            for (int index = 0; index < encoded.length(); index++) {
                if (Character.isISOControl(encoded.charAt(index))) {
                    clean = false;
                    break;
                }
            }
            if (clean) return encoded;
        }
        android.util.Log.w("HyperOS3FocusRestore",
                "backup appVersion fallback to versionCode=" + BuildConfig.VERSION_CODE);
        return String.valueOf(BuildConfig.VERSION_CODE);
    }

    private static Map<String, Object> entries(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }

    private static void append(StringBuilder text, Object value, int depth) throws IOException {
        checkInterrupted();
        if (value instanceof Map) {
            text.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) text.append(',');
                text.append('\n');
                indent(text, depth + 1);
                text.append(JSONObject.quote((String) entry.getKey())).append(": ");
                append(text, entry.getValue(), depth + 1);
                first = false;
            }
            text.append('\n');
            indent(text, depth);
            text.append('}');
        } else if (value instanceof Set) {
            text.append('[');
            boolean first = true;
            for (Object item : (Set<?>) value) {
                if (!first) text.append(',');
                text.append('\n');
                indent(text, depth + 1);
                append(text, item, depth + 1);
                first = false;
            }
            if (!first) { text.append('\n'); indent(text, depth); }
            text.append(']');
        } else if (value instanceof String) {
            text.append(JSONObject.quote((String) value));
        } else {
            text.append(value);
        }
    }

    private static void indent(StringBuilder text, int depth) {
        for (int index = 0; index < depth * 2; index++) text.append(' ');
    }

    private static JSONObject group(JSONObject groups, String name, String... allowed) throws IOException, JSONException {
        JSONObject result = object(groups, name, true);
        keys(result, name, allowed);
        return result;
    }

    private static JSONObject object(JSONObject parent, String key, boolean optional) throws IOException, JSONException {
        if (!parent.has(key) && optional) return new JSONObject();
        Object value = parent.opt(key);
        if (!(value instanceof JSONObject)) throw invalid(key + " 必须是对象");
        return (JSONObject) value;
    }

    private static void keys(JSONObject object, String path, String... allowed) throws IOException {
        Set<String> known = new HashSet<>(Arrays.asList(allowed));
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!known.contains(key)) throw invalid(path + " 包含未知字段：" + key);
        }
    }

    private static boolean bool(JSONObject object, String key, boolean fallback) throws IOException {
        if (!object.has(key)) return fallback;
        Object value = object.opt(key);
        if (!(value instanceof Boolean)) throw invalid(key + " 必须是布尔值");
        return (Boolean) value;
    }

    private static int integer(JSONObject object, String key, int fallback) throws IOException {
        if (!object.has(key)) return fallback;
        Object value = object.opt(key);
        if (!(value instanceof Number)) throw invalid(key + " 必须是整数");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE
                || number != Math.rint(number)) throw invalid(key + " 不是有效整数");
        return (int) number;
    }

    private static int backupDivider(JSONObject object, String currentKey, String legacyKey,
                                     int fallback) throws IOException {
        // 新字段优先，但两种编码并存时仍逐项校验，不能绕过严格导入。
        int legacy = rangedInteger(object, legacyKey, -1, -1, 24);
        if (object.has(currentKey)) {
            return rangedInteger(object, currentKey, fallback,
                    FocusRestoreSettings.AUTO_FOCUS_DIVIDER_SPACING_DP, 24);
        }
        return object.has(legacyKey) ? FocusRestoreSettings.migrateLegacyDividerSpacing(legacy) : fallback;
    }

    private static int rangedInteger(JSONObject object, String key, int fallback, int min, int max) throws IOException {
        int value = integer(object, key, fallback);
        if (value < min || value > max) throw invalid(key + " 必须为 " + min + "～" + max + " 的整数");
        return value;
    }

    private static float seconds(JSONObject object, String key, float fallback) throws IOException {
        if (!object.has(key)) return fallback;
        Object value = object.opt(key);
        String text;
        if (value instanceof Number || value instanceof String) text = value.toString().trim();
        else throw invalid(key + " 必须是秒数");
        if (text.length() > 128 || !DECIMAL_TEXT.matcher(text).matches()) throw invalid(key + " 不是有效秒数");
        try {
            float seconds = Float.parseFloat(text);
            if (!Float.isFinite(seconds)) throw invalid(key + " 秒数溢出");
            return seconds;
        } catch (NumberFormatException malformed) {
            throw new InvalidBackupException(key + " 不是有效秒数", malformed);
        }
    }

    private static String separator(JSONObject object, String key, String fallback) throws IOException {
        if (!object.has(key)) return fallback;
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).length() > InputLimits.MAX_SEPARATOR_CHARS) {
            throw invalid(key + " 必须是最多 32 字符的文本");
        }
        return (String) value;
    }

    private static Set<String> packages(JSONObject object, String key) throws IOException, JSONException {
        if (!object.has(key)) return Collections.emptySet();
        Object value = object.opt(key);
        if (!(value instanceof JSONArray)) throw invalid(key + " 必须是包名数组");
        JSONArray array = (JSONArray) value;
        if (array.length() > InputLimits.MAX_FORCE_PACKAGES) throw invalid(key + " 应用数量超限");
        Set<String> packages = new TreeSet<>();
        for (int index = 0; index < array.length(); index++) {
            checkInterrupted();
            Object item = array.get(index);
            if (!(item instanceof String)) throw invalid(key + " 包含非文本包名");
            String name = (String) item;
            validatePackageName(name, key);
            packages.add(name.trim());
        }
        return packages;
    }

    private static void checkPackageBudget(Set<String> forcePackages, Set<String> exemptPackages) throws IOException {
        int characters = 0;
        for (Set<String> packages : Arrays.asList(forcePackages, exemptPackages)) {
            for (String name : packages) {
                checkInterrupted();
                characters += name.length();
                if (characters > MAX_PACKAGE_SET_CHARS) {
                    throw invalid("两组应用包名合计超过 65536 字符，请减少所选应用");
                }
            }
        }
    }

    private static void validatePackageName(String name, String key) throws IOException {
        if (name == null || name.length() > InputLimits.MAX_PACKAGE_NAME_CHARS || name.trim().isEmpty()) {
            throw invalid(key + " 包名为空或超长");
        }
        // Provider 用控制字符连接集合；任何控制符都不能作为包名内容导入或导出。
        for (int index = 0; index < name.length(); index++) {
            if (Character.isISOControl(name.charAt(index))) throw invalid(key + " 包名包含控制符");
        }
    }

    private static InvalidBackupException invalid(String reason) { return new InvalidBackupException(reason); }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("配置操作已取消");
    }

    /** Syntax/quotas only; org.json continues to own JSON value decoding. */
    private static final class StrictJson {
        private final String text;
        private int position;

        StrictJson(String text) { this.text = text; }

        void validate() throws IOException, JSONException {
            whitespace();
            if (peek() != '{') throw invalid("配置 JSON 根必须是对象");
            value(0);
            whitespace();
            if (position != text.length()) throw invalid("配置 JSON 包含尾随内容");
        }

        private void value(int depth) throws IOException, JSONException {
            value(depth, null);
        }

        private void value(int depth, String field) throws IOException, JSONException {
            checkInterrupted();
            if (depth > 4) throw invalid("配置 JSON 嵌套过深");
            whitespace();
            char next = peek();
            if (next == '{') {
                position++;
                Set<String> names = new HashSet<>();
                whitespace();
                if (take('}')) return;
                do {
                    whitespace();
                    String name = string();
                    if (!names.add(name)) throw invalid("配置 JSON 包含重复字段：" + name);
                    if (names.size() > 64) throw invalid("配置 JSON 字段数量超限");
                    whitespace();
                    require(':');
                    value(depth + 1, name);
                    whitespace();
                } while (take(','));
                require('}');
            } else if (next == '[') {
                position++;
                int count = 0;
                whitespace();
                if (take(']')) return;
                do {
                    if (++count > InputLimits.MAX_FORCE_PACKAGES) throw invalid("配置 JSON 数组数量超限");
                    value(depth + 1);
                    whitespace();
                } while (take(','));
                require(']');
            } else if (next == '"') {
                string();
            } else {
                int start = position;
                while (position < text.length() && ",]} \t\r\n".indexOf(text.charAt(position)) < 0) {
                    if (position - start >= 128) throw invalid("配置 JSON 数字或字面量超长");
                    position++;
                }
                String token = text.substring(start, position);
                if (!token.equals("true") && !token.equals("false") && !token.equals("null")
                        && !NUMBER.matcher(token).matches()) throw invalid("配置 JSON 字面量无效");
                if (INTEGER_FIELDS.contains(field) && NUMBER.matcher(token).matches()
                        && !INTEGER.matcher(token).matches()) throw invalid(field + " 必须使用整数");
            }
        }

        private String string() throws IOException, JSONException {
            int start = position;
            require('"');
            boolean ended = false;
            while (position < text.length()) {
                char current = text.charAt(position++);
                if (current == '"') { ended = true; break; }
                if (current < 0x20) throw invalid("配置 JSON 文本含未转义控制符");
                if (current == '\\') {
                    if (position >= text.length()) throw invalid("配置 JSON 转义未完成");
                    char escape = text.charAt(position++);
                    if (escape == 'u') {
                        for (int index = 0; index < 4; index++) {
                            if (position >= text.length() || "0123456789abcdefABCDEF".indexOf(text.charAt(position++)) < 0) {
                                throw invalid("配置 JSON Unicode 转义无效");
                            }
                        }
                    } else if ("\"\\/bfnrt".indexOf(escape) < 0) throw invalid("配置 JSON 转义无效");
                }
                if (position - start > 6 * InputLimits.MAX_PACKAGE_NAME_CHARS + 2) {
                    throw invalid("配置 JSON 文本超长");
                }
            }
            if (!ended) throw invalid("配置 JSON 文本未结束");
            String decoded = (String) new JSONTokener(text.substring(start, position)).nextValue();
            if (decoded.length() > InputLimits.MAX_PACKAGE_NAME_CHARS) throw invalid("配置 JSON 文本超长");
            // 转义也可能产生孤立代理项；不能在再次导出时被 UTF-8 编码器静默替换。
            for (int index = 0; index < decoded.length(); index++) {
                char current = decoded.charAt(index);
                if (Character.isHighSurrogate(current)) {
                    if (++index >= decoded.length() || !Character.isLowSurrogate(decoded.charAt(index))) {
                        throw invalid("配置 JSON Unicode 代理项无效");
                    }
                } else if (Character.isLowSurrogate(current)) throw invalid("配置 JSON Unicode 代理项无效");
            }
            return decoded;
        }

        private void whitespace() throws IOException {
            while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) {
                if ((position & 1023) == 0) checkInterrupted();
                position++;
            }
        }

        private char peek() { return position < text.length() ? text.charAt(position) : '\0'; }
        private boolean take(char expected) {
            if (peek() != expected) return false;
            position++;
            return true;
        }
        private void require(char expected) throws IOException {
            if (!take(expected)) throw invalid("配置 JSON 语法无效，位置 " + position);
        }
    }
}
