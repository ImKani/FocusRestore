/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.database.Cursor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Immutable SystemUI-side settings snapshot. */
final class HookSettings {
    final int hookMode;
    final boolean limitWidth;
    final int widthDp;
    final int marqueeDelayMs;
    final int widthLandscapeDp;
    final boolean specialBannerNormalBackground;
    final boolean mediaFocusEnabled;
    final boolean mediaFocusNativeBanner;
    final int mediaFocusCastPicker;
    final boolean mediaFocusCastDirect;
    final boolean compatRetry;
    final boolean marqueeBounce;
    final boolean islandCompat;
    final boolean disableIslandProperty;
    final boolean disableIslandFeatureCache;
    final boolean allowFocusClick;
    final boolean hideNotificationIcons;
    final boolean showFocusDivider;
    final int focusIconSizeDp;
    final int focusIconTextGapDp;
    final int focusDividerStartDp;
    final int focusDividerEndDp;
    final int focusDividerWidthDp;
    final int focusDividerStyle;

    final boolean showIslandIcon;
    final boolean tintIslandIcon;
    final boolean useSmallIconFallback;
    final int focusIconPriority;
    final boolean tintDeviceIcon;
    final boolean notificationRowClickFallback;
    final boolean independentFocusBanner;
    final boolean longPressNotificationRowClick;
    final float longPressSeconds;
    final boolean focusSwipeEnabled;
    final boolean customTouchWidth;
    final int touchWidthDp;
    final int touchWidthLandscapeDp;
    final int islandTextMode;
    final float focusMaxDisplaySeconds;
    final String generalSeparator;
    final String sideSeparator;
    final Set<String> islandForcePackages;
    final Set<String> focusTimeoutExemptPackages;

    private HookSettings(int hookMode, boolean limitWidth, int widthDp, int widthLandscapeDp,
                          boolean specialBannerNormalBackground,
                          boolean mediaFocusEnabled, boolean mediaFocusNativeBanner,
                          int mediaFocusCastPicker, boolean mediaFocusCastDirect, int marqueeDelayMs,
                         boolean compatRetry, boolean marqueeBounce, boolean islandCompat,
                         boolean disableIslandProperty, boolean disableIslandFeatureCache,
                         boolean allowFocusClick, boolean hideNotificationIcons,
                         boolean showFocusDivider,
                         int focusIconSizeDp, int focusIconTextGapDp, int focusDividerStartDp, int focusDividerEndDp,
                         int focusDividerWidthDp, int focusDividerStyle, boolean showIslandIcon,
                         boolean tintIslandIcon, boolean useSmallIconFallback,
                         int focusIconPriority, boolean tintDeviceIcon,
                         boolean notificationRowClickFallback, boolean independentFocusBanner,
                          boolean longPressNotificationRowClick, float longPressSeconds, boolean focusSwipeEnabled,
                         boolean customTouchWidth, int touchWidthDp, int touchWidthLandscapeDp,
                         int islandTextMode, float focusMaxDisplaySeconds,
                         String generalSeparator,
                         String sideSeparator, Set<String> forcePackages,
                         Set<String> timeoutExemptPackages) {
        this.hookMode = FocusRestoreSettings.normalizeHookMode(hookMode);
        this.limitWidth = limitWidth;
        this.widthDp = clamp(widthDp, FocusRestoreSettings.MIN_WIDTH_DP,
                FocusRestoreSettings.MAX_WIDTH_DP);
        this.widthLandscapeDp = clamp(widthLandscapeDp, FocusRestoreSettings.MIN_WIDTH_DP,
                FocusRestoreSettings.MAX_LANDSCAPE_WIDTH_DP);
        this.specialBannerNormalBackground = specialBannerNormalBackground;
        this.mediaFocusEnabled = mediaFocusEnabled;
        this.mediaFocusCastPicker = FocusRestoreSettings.normalizeCastPicker(mediaFocusCastPicker);
        this.mediaFocusCastDirect = mediaFocusCastDirect;
        // 二选一：直接展开流转界面时不再走媒体横幅。
        this.mediaFocusNativeBanner = mediaFocusNativeBanner && !mediaFocusCastDirect;
        this.marqueeDelayMs = clamp(marqueeDelayMs, 0, 5000);
        this.compatRetry = compatRetry;
        this.marqueeBounce = marqueeBounce;
        this.islandCompat = islandCompat;
        this.disableIslandProperty = disableIslandProperty;
        this.disableIslandFeatureCache = disableIslandFeatureCache;
        this.allowFocusClick = allowFocusClick;
        this.hideNotificationIcons = hideNotificationIcons;
        this.showFocusDivider = showFocusDivider;
        this.focusIconSizeDp = FocusRestoreSettings.normalizeFocusIconSizeDp(focusIconSizeDp);
        this.focusIconTextGapDp = FocusRestoreSettings.normalizeFocusIconTextGapDp(focusIconTextGapDp);
        this.focusDividerStartDp = FocusRestoreSettings.normalizeFocusDividerSpacingDp(focusDividerStartDp);
        this.focusDividerEndDp = FocusRestoreSettings.normalizeFocusDividerSpacingDp(focusDividerEndDp);
        this.focusDividerWidthDp = FocusRestoreSettings.normalizeFocusDividerWidthDp(focusDividerWidthDp);
        this.focusDividerStyle = FocusRestoreSettings.normalizeFocusDividerStyle(focusDividerStyle);

        this.showIslandIcon = showIslandIcon;
        this.tintIslandIcon = tintIslandIcon;
        this.useSmallIconFallback = useSmallIconFallback;
        this.focusIconPriority = FocusRestoreSettings.normalizeIconPriority(focusIconPriority);
        this.tintDeviceIcon = tintDeviceIcon;
        this.notificationRowClickFallback = notificationRowClickFallback;
        this.independentFocusBanner = independentFocusBanner;
        this.longPressNotificationRowClick = longPressNotificationRowClick;
        this.longPressSeconds = FocusRestoreSettings.normalizeLongPressSeconds(longPressSeconds);
        this.focusSwipeEnabled = focusSwipeEnabled;
        this.customTouchWidth = customTouchWidth;
        this.touchWidthDp = clamp(touchWidthDp, FocusRestoreSettings.MIN_WIDTH_DP,
                FocusRestoreSettings.MAX_WIDTH_DP);
        this.touchWidthLandscapeDp = clamp(touchWidthLandscapeDp, FocusRestoreSettings.MIN_WIDTH_DP,
                FocusRestoreSettings.MAX_LANDSCAPE_WIDTH_DP);
        this.islandTextMode = FocusRestoreSettings.normalizeIslandTextMode(islandTextMode);
        this.focusMaxDisplaySeconds =
                FocusRestoreSettings.normalizeMaxDisplaySeconds(focusMaxDisplaySeconds);
        this.generalSeparator = InputLimits.limitSeparator(generalSeparator == null
                ? FocusRestoreSettings.DEFAULT_ISLAND_SEPARATOR : generalSeparator);
        this.sideSeparator = InputLimits.limitSeparator(sideSeparator == null
                ? FocusRestoreSettings.DEFAULT_ISLAND_SEPARATOR : sideSeparator);
        this.islandForcePackages = immutablePackages(forcePackages);
        this.focusTimeoutExemptPackages = immutablePackages(timeoutExemptPackages);
    }

    static HookSettings defaults() {
        return fromSettings(FocusRestoreSettings.defaults());
    }

    /**
     * Maps a stored snapshot onto the SystemUI-side view. Keeping the single positional mapping
     * here means the hook defaults cannot drift away from the settings defaults.
     */
    static HookSettings fromSettings(FocusRestoreSettings source) {
        return new HookSettings(source.hookMode, source.limitWidth, source.widthDp,
                source.widthLandscapeDp, source.specialBannerNormalBackground,
                source.mediaFocusEnabled, source.mediaFocusNativeBanner,
                source.mediaFocusCastPicker, source.mediaFocusCastDirect,
                source.marqueeDelayMs, source.compatRetry, source.marqueeBounce,
                source.islandCompat, source.disableIslandProperty,
                source.disableIslandFeatureCache, source.allowFocusClick,
                source.hideNotificationIcons, source.showFocusDivider,
                source.focusIconSizeDp, source.focusIconTextGapDp, source.focusDividerStartDp, source.focusDividerEndDp,
                source.focusDividerWidthDp, source.focusDividerStyle, source.showIslandIcon,
                source.tintIslandIcon, source.useSmallIconFallback,
                source.focusIconPriority, source.tintDeviceIcon,
                source.notificationRowClickFallback, source.independentFocusBanner,
                source.longPressNotificationRowClick, source.longPressSeconds, source.focusSwipeEnabled,
                source.customTouchWidth, source.touchWidthDp, source.touchWidthLandscapeDp,
                source.islandTextMode, source.focusMaxDisplaySeconds,
                source.islandGeneralSeparator, source.islandSideSeparator,
                source.islandForcePackages, source.focusTimeoutExemptPackages);
    }

    static HookSettings fromCursor(Cursor cursor) {
        if (cursor == null) throw new IllegalArgumentException("settings cursor is null");
        // 旧、新 Provider 的列顺序不同；只用名称定位，缺少必需字段时拒绝整个快照。
        int limitWidthIndex = requiredColumn(cursor, SettingsContract.LIMIT_TEXT_WIDTH);
        int widthIndex = requiredColumn(cursor, SettingsContract.TEXT_WIDTH_DP);
        int delayIndex = requiredColumn(cursor, SettingsContract.MARQUEE_DELAY_MS);
        boolean limitWidth = cursor.getInt(limitWidthIndex) != 0;
        int widthDp = cursor.getInt(widthIndex);
        int marqueeDelayMs = cursor.getInt(delayIndex);
        String legacySeparator = readString(cursor, SettingsContract.LEGACY_ISLAND_SEPARATOR,
                FocusRestoreSettings.DEFAULT_ISLAND_SEPARATOR);
        int maxDisplayIndex = valueColumn(cursor, SettingsContract.FOCUS_MAX_DISPLAY_SECONDS);
        float focusMaxDisplaySeconds = maxDisplayIndex >= 0
                ? maxDisplaySeconds(cursor, maxDisplayIndex)
                : FocusRestoreSettings.DEFAULT_FOCUS_MAX_DISPLAY_SECONDS;

        return new HookSettings(
                readInt(cursor, SettingsContract.HOOK_MODE, FocusRestoreSettings.DEFAULT_HOOK_MODE),
                limitWidth, widthDp,
                // 旧配置没有横屏列时沿用竖屏宽度，而非重置为新版默认宽度。
                readInt(cursor, SettingsContract.WIDTH_LANDSCAPE_DP, widthDp),
                readBoolean(cursor, SettingsContract.SPECIAL_BANNER_NORMAL_BACKGROUND, false),
                readBoolean(cursor, SettingsContract.MEDIA_FOCUS_ENABLED, false),
                readBoolean(cursor, SettingsContract.MEDIA_FOCUS_NATIVE_BANNER,
                        FocusRestoreSettings.DEFAULT_MEDIA_FOCUS_NATIVE_BANNER),
                readInt(cursor, SettingsContract.MEDIA_FOCUS_CAST_PICKER,
                        FocusRestoreSettings.DEFAULT_MEDIA_FOCUS_CAST_PICKER),
                readBoolean(cursor, SettingsContract.MEDIA_FOCUS_CAST_DIRECT,
                        FocusRestoreSettings.DEFAULT_MEDIA_FOCUS_CAST_DIRECT),
                marqueeDelayMs,
                readBoolean(cursor, SettingsContract.COMPAT_RETRY, false),
                readBoolean(cursor, SettingsContract.MARQUEE_BOUNCE,
                        FocusRestoreSettings.DEFAULT_MARQUEE_BOUNCE),
                readBoolean(cursor, SettingsContract.ISLAND_COMPAT, FocusRestoreSettings.DEFAULT_ISLAND_COMPAT),
                readBoolean(cursor, SettingsContract.DISABLE_ISLAND_PROPERTY,
                        FocusRestoreSettings.DEFAULT_DISABLE_ISLAND_PROPERTY),
                readBoolean(cursor, SettingsContract.DISABLE_ISLAND_FEATURE_CACHE,
                        FocusRestoreSettings.DEFAULT_DISABLE_ISLAND_FEATURE_CACHE),
                readBoolean(cursor, SettingsContract.ALLOW_FOCUS_CLICK, false),
                readBoolean(cursor, SettingsContract.HIDE_NOTIFICATION_ICONS,
                        FocusRestoreSettings.DEFAULT_HIDE_NOTIFICATION_ICONS),
                readBoolean(cursor, SettingsContract.SHOW_FOCUS_DIVIDER,
                        FocusRestoreSettings.DEFAULT_SHOW_FOCUS_DIVIDER),
                // 缺外观列时使用明确默认值，不推导自其他设置。
                readInt(cursor, SettingsContract.FOCUS_ICON_SIZE_DP, FocusRestoreSettings.DEFAULT_FOCUS_ICON_SIZE_DP),
                readInt(cursor, SettingsContract.FOCUS_ICON_TEXT_GAP_DP, FocusRestoreSettings.DEFAULT_FOCUS_ICON_TEXT_GAP_DP),
                readDivider(cursor, true),
                readDivider(cursor, false),
                readInt(cursor, SettingsContract.FOCUS_DIVIDER_WIDTH_DP, FocusRestoreSettings.DEFAULT_FOCUS_DIVIDER_WIDTH_DP),
                readInt(cursor, SettingsContract.FOCUS_DIVIDER_STYLE, FocusRestoreSettings.DEFAULT_FOCUS_DIVIDER_STYLE),
                readBoolean(cursor, SettingsContract.SHOW_ISLAND_ICON,
                        FocusRestoreSettings.DEFAULT_SHOW_ISLAND_ICON),
                readBoolean(cursor, SettingsContract.TINT_ISLAND_ICON,
                        FocusRestoreSettings.DEFAULT_TINT_ISLAND_ICON),
                readBoolean(cursor, SettingsContract.USE_SMALL_ICON_FALLBACK,
                        FocusRestoreSettings.DEFAULT_USE_SMALL_ICON_FALLBACK),
                readInt(cursor, SettingsContract.FOCUS_ICON_PRIORITY,
                        FocusRestoreSettings.legacyIconPriority(
                                readBoolean(cursor, SettingsContract.USE_SMALL_ICON_FALLBACK, false),
                                readBoolean(cursor, SettingsContract.SHOW_ISLAND_ICON, false))),
                readBoolean(cursor, SettingsContract.TINT_DEVICE_ICON,
                        FocusRestoreSettings.DEFAULT_TINT_DEVICE_ICON),
                readBoolean(cursor, SettingsContract.NOTIFICATION_ROW_CLICK_FALLBACK,
                        FocusRestoreSettings.DEFAULT_NOTIFICATION_ROW_CLICK_FALLBACK),
                readBoolean(cursor, SettingsContract.INDEPENDENT_FOCUS_BANNER,
                        FocusRestoreSettings.DEFAULT_INDEPENDENT_FOCUS_BANNER),
                // 旧版缺长按字段时保持关闭，不能从短按开关推导开启。
                readBoolean(cursor, SettingsContract.LONG_PRESS_NOTIFICATION_ROW_CLICK, false),
                readFloat(cursor, SettingsContract.LONG_PRESS_SECONDS,
                        FocusRestoreSettings.DEFAULT_LONG_PRESS_SECONDS),
                // 缺新列的旧 Provider 保持关闭，滑动不能从短按或长按设置推导开启。
                readBoolean(cursor, SettingsContract.FOCUS_SWIPE_ENABLED, false),
                // 缺少独立触控列时沿用0.32.0实际边界，不能从显示宽度推导开启。
                readBoolean(cursor, SettingsContract.CUSTOM_TOUCH_WIDTH, false),
                readInt(cursor, SettingsContract.TOUCH_WIDTH_DP, FocusRestoreSettings.DEFAULT_TOUCH_WIDTH_DP),
                readInt(cursor, SettingsContract.TOUCH_WIDTH_LANDSCAPE_DP,
                        FocusRestoreSettings.DEFAULT_TOUCH_WIDTH_LANDSCAPE_DP),
                readInt(cursor, SettingsContract.ISLAND_TEXT_MODE,
                        FocusRestoreSettings.DEFAULT_ISLAND_TEXT_MODE),
                focusMaxDisplaySeconds,
                readString(cursor, SettingsContract.ISLAND_GENERAL_SEPARATOR, legacySeparator),
                readString(cursor, SettingsContract.ISLAND_SIDE_SEPARATOR, legacySeparator),
                splitPackages(readString(cursor, SettingsContract.ISLAND_FORCE_PACKAGES, "")),
                splitPackages(readString(cursor, SettingsContract.FOCUS_TIMEOUT_EXEMPT_PACKAGES, "")));
    }

    String describe() {
        return "hookMode=OS" + hookMode + " limit=" + limitWidth + " widthDp=" + widthDp
                + " delayMs=" + marqueeDelayMs + " compatRetry=" + compatRetry
                + " marqueeBounce=" + marqueeBounce
                + " islandCompat=" + islandCompat
                + " disableIslandProperty=" + disableIslandProperty
                + " disableIslandFeatureCache=" + disableIslandFeatureCache
                + " allowFocusClick=" + allowFocusClick
                + " hideNotificationIcons=" + hideNotificationIcons
                + " showFocusDivider=" + showFocusDivider
                + " focusIconSizeDp=" + focusIconSizeDp
                + " focusIconTextGapDp=" + focusIconTextGapDp
                + " focusDividerStartDp=" + focusDividerStartDp
                + " focusDividerEndDp=" + focusDividerEndDp
                + " focusDividerWidthDp=" + focusDividerWidthDp
                + " focusDividerStyle=" + focusDividerStyle
                + " showIslandIcon=" + showIslandIcon
                + " tintIslandIcon=" + tintIslandIcon
                + " expandIslandOnClick=false(deprecated)"
                + " focusIconPriority=" + focusIconPriority + " tintDeviceIcon=" + tintDeviceIcon
                + " useSmallIconFallback=" + useSmallIconFallback
                + " notificationRowClickFallback=" + notificationRowClickFallback
                + " independentFocusBanner=" + independentFocusBanner
                + " longPressNotificationRowClick=" + longPressNotificationRowClick
                + " longPressSeconds=" + longPressSeconds
                + " focusSwipeEnabled=" + focusSwipeEnabled
                + " customTouchWidth=" + customTouchWidth
                + " touchWidthDp=" + touchWidthDp + " touchWidthLandscapeDp=" + touchWidthLandscapeDp
                + " mediaFocusCastPicker=" + mediaFocusCastPicker
                + " mediaFocusCastDirect=" + mediaFocusCastDirect
                + " islandTextMode=" + islandTextMode
                + " focusMaxDisplaySeconds=" + focusMaxDisplaySeconds
                + " forcePackages=" + islandForcePackages
                + " timeoutExemptPackages=" + focusTimeoutExemptPackages
                + " islandSeparator=" + displaySeparator(generalSeparator)
                + " islandSideSeparator=" + displaySeparator(sideSeparator);
    }

    /** Reads the seconds column without assuming int/float/string storage from older builds. */
    private static float maxDisplaySeconds(Cursor cursor, int index) {
        try {
            return FocusRestoreSettings.normalizeMaxDisplaySeconds(cursor.getFloat(index));
        } catch (Throwable ignored) {
            // Column stored as int or string; fall through to the safer readers below.
        }
        try {
            return FocusRestoreSettings.normalizeMaxDisplaySeconds(cursor.getInt(index));
        } catch (Throwable ignored) {
            // Not an integer column either.
        }
        try {
            return FocusRestoreSettings.parseMaxDisplaySeconds(cursor.getString(index),
                    FocusRestoreSettings.DEFAULT_FOCUS_MAX_DISPLAY_SECONDS);
        } catch (Throwable ignored) {
            return FocusRestoreSettings.DEFAULT_FOCUS_MAX_DISPLAY_SECONDS;
        }
    }

    private static int readDivider(Cursor cursor, boolean start) {
        String current = start ? "focus_divider_start_offset_dp" : "focus_divider_end_offset_dp";
        int index = cursor.getColumnIndex(current);
        if (index >= 0 && !cursor.isNull(index)) {
            return FocusRestoreSettings.normalizeFocusDividerSpacingDp(cursor.getInt(index));
        }
        String legacy = start ? "focus_divider_start_dp" : "focus_divider_end_dp";
        index = cursor.getColumnIndex(legacy);
        if (index >= 0 && !cursor.isNull(index)) {
            return FocusRestoreSettings.migrateLegacyDividerSpacing(cursor.getInt(index));
        }
        return start ? FocusRestoreSettings.DEFAULT_FOCUS_DIVIDER_START_DP
                : FocusRestoreSettings.DEFAULT_FOCUS_DIVIDER_END_DP;
    }
    private static int valueColumn(Cursor cursor, int field) {
        int index = cursor.getColumnIndex(SettingsContract.COLUMNS[field]);
        return index >= 0 && !cursor.isNull(index) ? index : -1;
    }

    private static int requiredColumn(Cursor cursor, int field) {
        int index = valueColumn(cursor, field);
        if (index < 0) {
            throw new IllegalArgumentException("required settings column missing or null: "
                    + SettingsContract.COLUMNS[field]);
        }
        return index;
    }

    private static boolean readBoolean(Cursor cursor, int field, boolean fallback) {
        int index = valueColumn(cursor, field);
        return index >= 0 ? cursor.getInt(index) != 0 : fallback;
    }

    private static int readInt(Cursor cursor, int field, int fallback) {
        int index = valueColumn(cursor, field);
        return index >= 0 ? cursor.getInt(index) : fallback;
    }

    private static float readFloat(Cursor cursor, int field, float fallback) {
        int index = valueColumn(cursor, field);
        return index >= 0 ? cursor.getFloat(index) : fallback;
    }

    private static String readString(Cursor cursor, int field, String fallback) {
        int index = valueColumn(cursor, field);
        return index >= 0 ? cursor.getString(index) : fallback;
    }

    private static Set<String> splitPackages(String value) {
        if (value == null || value.length() == 0) return Collections.emptySet();
        HashSet<String> result = new HashSet<>();
        String[] parts = value.split(java.util.regex.Pattern.quote(
                FocusRestoreSettings.PACKAGE_SET_SEPARATOR));
        for (String part : parts) {
            if (part != null && part.trim().length() > 0) result.add(part.trim());
        }
        return result;
    }

    private static Set<String> immutablePackages(Set<String> packages) {
        return InputLimits.sanitizePackages(packages);
    }

    private static String displaySeparator(String value) {
        return value.length() == 0 ? "<empty>" : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
