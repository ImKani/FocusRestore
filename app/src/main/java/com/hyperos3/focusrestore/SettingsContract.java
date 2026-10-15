/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

/** Named Provider fields, grouped by function; indices only address the current encoded row. */
final class SettingsContract {
    // 模式与 ROM 兼容。
    static final int HOOK_MODE = 0;
    static final int COMPAT_RETRY = 1;
    static final int DISABLE_ISLAND_PROPERTY = 2;
    static final int DISABLE_ISLAND_FEATURE_CACHE = 3;

    // 文本宽度与滚动。
    static final int LIMIT_TEXT_WIDTH = 4;
    static final int TEXT_WIDTH_DP = 5;
    static final int WIDTH_LANDSCAPE_DP = 6;
    static final int MARQUEE_DELAY_MS = 7;
    static final int MARQUEE_BOUNCE = 8;

    // 岛内容转换与分隔符。
    static final int ISLAND_COMPAT = 9;
    static final int ISLAND_TEXT_MODE = 10;
    static final int ISLAND_GENERAL_SEPARATOR = 11;
    static final int ISLAND_SIDE_SEPARATOR = 12;
    static final int ISLAND_FORCE_PACKAGES = 13;

    // 图标与分隔线。
    static final int HIDE_NOTIFICATION_ICONS = 14;
    static final int SHOW_FOCUS_DIVIDER = 15;
    static final int FOCUS_ICON_SIZE_DP = 16;
    static final int FOCUS_ICON_TEXT_GAP_DP = 17;
    static final int FOCUS_DIVIDER_START_OFFSET_DP = 18;
    static final int FOCUS_DIVIDER_END_OFFSET_DP = 19;
    static final int FOCUS_DIVIDER_START_DP = FOCUS_DIVIDER_START_OFFSET_DP;
    static final int FOCUS_DIVIDER_END_DP = FOCUS_DIVIDER_END_OFFSET_DP;
    static final int FOCUS_DIVIDER_WIDTH_DP = 20;
    static final int FOCUS_DIVIDER_STYLE = 21;

    static final int SHOW_ISLAND_ICON = 22;
    static final int TINT_ISLAND_ICON = 23;
    static final int USE_SMALL_ICON_FALLBACK = 24;

    static final int FOCUS_ICON_PRIORITY = 25;
    static final int TINT_DEVICE_ICON = 26;

    // 横幅与点击手势。
    static final int INDEPENDENT_FOCUS_BANNER = 27;
    static final int SPECIAL_BANNER_NORMAL_BACKGROUND = 28;
    static final int ALLOW_FOCUS_CLICK = 29;
    static final int NOTIFICATION_ROW_CLICK_FALLBACK = 30;
    static final int LONG_PRESS_NOTIFICATION_ROW_CLICK = 31;
    static final int LONG_PRESS_SECONDS = 32;
    static final int FOCUS_SWIPE_ENABLED = 33;
    static final int CUSTOM_TOUCH_WIDTH = 34;
    static final int TOUCH_WIDTH_DP = 35;
    static final int TOUCH_WIDTH_LANDSCAPE_DP = 36;

    // 最大显示时间与独立豁免名单。
    static final int FOCUS_MAX_DISPLAY_SECONDS = 37;
    static final int FOCUS_TIMEOUT_EXEMPT_PACKAGES = 38;

    // 媒体焦点与流转。
    static final int MEDIA_FOCUS_ENABLED = 39;
    static final int MEDIA_FOCUS_NATIVE_BANNER = 40;
    static final int MEDIA_FOCUS_CAST_PICKER = 41;
    static final int MEDIA_FOCUS_CAST_DIRECT = 42;

    // 历史字段仍响应旧 projection；废弃功能不恢复，旧分隔符只供缺列回退。
    static final int LEGACY_ISLAND_SEPARATOR = 43;
    static final int EXPAND_ISLAND_ON_CLICK = 44;
    static final int NOTIFICATION_ICON_HIDE_MODE = 45;
    static final int ISLAND_CUSTOM_RULES = 46;

    static final String[] COLUMNS = {
            "hook_mode", "compat_retry", "disable_island_property", "disable_island_feature_cache",
            "limit_text_width", "text_width_dp", "width_landscape_dp", "marquee_delay_ms", "marquee_bounce",
            "island_compat", "island_text_mode", "island_general_separator", "island_side_separator",
            "island_force_packages",
            "hide_notification_icons", "show_focus_divider",
            "focus_icon_size_dp", "focus_icon_text_gap_dp", "focus_divider_start_offset_dp", "focus_divider_end_offset_dp", "focus_divider_width_dp", "focus_divider_style",
            "show_island_icon", "tint_island_icon",
            "use_small_icon_fallback", "focus_icon_priority", "tint_device_icon",
            "independent_focus_banner", "special_banner_normal_background", "allow_focus_click",
            "notification_row_click_fallback", "long_press_notification_row_click", "long_press_seconds",
            "focus_swipe_enabled",
            "custom_touch_width", "touch_width_dp", "touch_width_landscape_dp",
            "focus_max_display_seconds", "focus_timeout_exempt_packages",
            "media_focus_enabled", "media_focus_native_banner", "media_focus_cast_picker", "media_focus_cast_direct",
            "island_separator", "expand_island_on_click", "notification_icon_hide_mode", "island_custom_rules"
    };

    /** Null requests the grouped schema; explicit projections retain their requested order. */
    static String[] columnsForProjection(String[] projection) {
        String[] columns = (projection == null ? COLUMNS : projection).clone();
        for (String column : columns) columnIndex(column);
        return columns;
    }

    private static int columnIndex(String column) {
        if ("focus_divider_start_dp".equals(column)) return FOCUS_DIVIDER_START_OFFSET_DP;
        if ("focus_divider_end_dp".equals(column)) return FOCUS_DIVIDER_END_OFFSET_DP;
        for (int index = 0; index < COLUMNS.length; index++) {
            if (COLUMNS[index].equals(column)) return index;
        }
        throw new IllegalArgumentException("unknown settings column: " + column);
    }

    /** Encode the current grouped row; booleans and package sets retain their historical wire format. */
    static Object[] toRow(FocusRestoreSettings settings, String legacySeparator) {
        Object[] row = new Object[COLUMNS.length];
        row[HOOK_MODE] = settings.hookMode;
        row[COMPAT_RETRY] = settings.compatRetry ? 1 : 0;
        row[DISABLE_ISLAND_PROPERTY] = settings.disableIslandProperty ? 1 : 0;
        row[DISABLE_ISLAND_FEATURE_CACHE] = settings.disableIslandFeatureCache ? 1 : 0;

        row[LIMIT_TEXT_WIDTH] = settings.limitWidth ? 1 : 0;
        row[TEXT_WIDTH_DP] = settings.widthDp;
        row[WIDTH_LANDSCAPE_DP] = settings.widthLandscapeDp;
        row[MARQUEE_DELAY_MS] = settings.marqueeDelayMs;
        row[MARQUEE_BOUNCE] = settings.marqueeBounce ? 1 : 0;

        row[ISLAND_COMPAT] = settings.islandCompat ? 1 : 0;
        row[ISLAND_TEXT_MODE] = settings.islandTextMode;
        row[ISLAND_GENERAL_SEPARATOR] = settings.islandGeneralSeparator;
        row[ISLAND_SIDE_SEPARATOR] = settings.islandSideSeparator;
        row[ISLAND_FORCE_PACKAGES] = joinPackages(settings.islandForcePackages);

        row[HIDE_NOTIFICATION_ICONS] = settings.hideNotificationIcons ? 1 : 0;
        row[SHOW_FOCUS_DIVIDER] = settings.showFocusDivider ? 1 : 0;
        row[FOCUS_ICON_SIZE_DP] = settings.focusIconSizeDp;
        row[FOCUS_ICON_TEXT_GAP_DP] = settings.focusIconTextGapDp;
        row[FOCUS_DIVIDER_START_DP] = settings.focusDividerStartDp;
        row[FOCUS_DIVIDER_END_DP] = settings.focusDividerEndDp;
        row[FOCUS_DIVIDER_WIDTH_DP] = settings.focusDividerWidthDp;
        row[FOCUS_DIVIDER_STYLE] = settings.focusDividerStyle;

        row[SHOW_ISLAND_ICON] = settings.showIslandIcon ? 1 : 0;
        row[TINT_ISLAND_ICON] = settings.tintIslandIcon ? 1 : 0;
        row[USE_SMALL_ICON_FALLBACK] = settings.useSmallIconFallback ? 1 : 0;

        row[FOCUS_ICON_PRIORITY] = settings.focusIconPriority;
        row[TINT_DEVICE_ICON] = settings.tintDeviceIcon ? 1 : 0;

        row[INDEPENDENT_FOCUS_BANNER] = settings.independentFocusBanner ? 1 : 0;
        row[SPECIAL_BANNER_NORMAL_BACKGROUND] = settings.specialBannerNormalBackground ? 1 : 0;
        row[ALLOW_FOCUS_CLICK] = settings.allowFocusClick ? 1 : 0;
        row[NOTIFICATION_ROW_CLICK_FALLBACK] = settings.notificationRowClickFallback ? 1 : 0;
        row[LONG_PRESS_NOTIFICATION_ROW_CLICK] = settings.longPressNotificationRowClick ? 1 : 0;
        row[LONG_PRESS_SECONDS] = settings.longPressSeconds;
        row[FOCUS_SWIPE_ENABLED] = settings.focusSwipeEnabled ? 1 : 0;
        row[CUSTOM_TOUCH_WIDTH] = settings.customTouchWidth ? 1 : 0;
        row[TOUCH_WIDTH_DP] = settings.touchWidthDp;
        row[TOUCH_WIDTH_LANDSCAPE_DP] = settings.touchWidthLandscapeDp;

        row[FOCUS_MAX_DISPLAY_SECONDS] = settings.focusMaxDisplaySeconds;
        row[FOCUS_TIMEOUT_EXEMPT_PACKAGES] = joinPackages(settings.focusTimeoutExemptPackages);

        row[MEDIA_FOCUS_ENABLED] = settings.mediaFocusEnabled ? 1 : 0;
        row[MEDIA_FOCUS_NATIVE_BANNER] = settings.mediaFocusNativeBanner ? 1 : 0;
        row[MEDIA_FOCUS_CAST_PICKER] = settings.mediaFocusCastPicker;
        row[MEDIA_FOCUS_CAST_DIRECT] = settings.mediaFocusCastDirect ? 1 : 0;

        row[LEGACY_ISLAND_SEPARATOR] = legacySeparator;
        row[EXPAND_ISLAND_ON_CLICK] = 0;
        row[NOTIFICATION_ICON_HIDE_MODE] = 0;
        row[ISLAND_CUSTOM_RULES] = "";
        return row;
    }

    static Object[] toRow(FocusRestoreSettings settings, String legacySeparator, String[] columns) {
        Object[] currentRow = toRow(settings, legacySeparator);
        Object[] projectedRow = new Object[columns.length];
        // 覆盖升级后旧 Hook 仍按位置读取；按它发来的列名顺序返回，不能强塞新顺序。
        for (int index = 0; index < columns.length; index++) {
            String column = columns[index];
            if ("focus_divider_start_dp".equals(column)) {
                projectedRow[index] = FocusRestoreSettings.legacyDividerSpacing(settings.focusDividerStartDp);
            } else if ("focus_divider_end_dp".equals(column)) {
                projectedRow[index] = FocusRestoreSettings.legacyDividerSpacing(settings.focusDividerEndDp);
            } else {
                projectedRow[index] = currentRow[columnIndex(column)];
            }
        }
        return projectedRow;
    }

    private static String joinPackages(java.util.Set<String> packages) {
        if (packages == null || packages.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (String value : packages) {
            if (result.length() > 0) result.append(FocusRestoreSettings.PACKAGE_SET_SEPARATOR);
            result.append(value);
        }
        return result.toString();
    }

    private SettingsContract() {
    }
}
