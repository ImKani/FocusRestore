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
    final boolean showIslandIcon;
    final boolean tintIslandIcon;
    final boolean useSmallIconFallback;
    final boolean notificationRowClickFallback;
    final boolean independentFocusBanner;
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
                         boolean showFocusDivider, boolean showIslandIcon,
                         boolean tintIslandIcon, boolean useSmallIconFallback,
                         boolean notificationRowClickFallback, boolean independentFocusBanner,
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
        this.showIslandIcon = showIslandIcon;
        this.tintIslandIcon = tintIslandIcon;
        this.useSmallIconFallback = useSmallIconFallback;
        this.notificationRowClickFallback = notificationRowClickFallback;
        this.independentFocusBanner = independentFocusBanner;
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
                source.hideNotificationIcons, source.showFocusDivider, source.showIslandIcon,
                source.tintIslandIcon, source.useSmallIconFallback,
                source.notificationRowClickFallback, source.independentFocusBanner,
                source.islandTextMode, source.focusMaxDisplaySeconds,
                source.islandGeneralSeparator, source.islandSideSeparator,
                source.islandForcePackages, source.focusTimeoutExemptPackages);
    }

    static HookSettings fromCursor(Cursor cursor) {
        if (cursor == null || cursor.getColumnCount() <= SettingsContract.MARQUEE_DELAY_MS) {
            throw new IllegalArgumentException("settings cursor requires at least 3 columns");
        }
        if (cursor.isNull(SettingsContract.LIMIT_TEXT_WIDTH)
                || cursor.isNull(SettingsContract.TEXT_WIDTH_DP)
                || cursor.isNull(SettingsContract.MARQUEE_DELAY_MS)) {
            throw new IllegalArgumentException("required settings column is null");
        }

        int columnCount = cursor.getColumnCount();
        boolean limitWidth = cursor.getInt(SettingsContract.LIMIT_TEXT_WIDTH) != 0;
        int widthDp = cursor.getInt(SettingsContract.TEXT_WIDTH_DP);
        int marqueeDelayMs = cursor.getInt(SettingsContract.MARQUEE_DELAY_MS);
        boolean compatRetry = hasValue(cursor, columnCount, SettingsContract.COMPAT_RETRY)
                && cursor.getInt(SettingsContract.COMPAT_RETRY) != 0;
        boolean marqueeBounce = hasValue(cursor, columnCount, SettingsContract.MARQUEE_BOUNCE)
                ? cursor.getInt(SettingsContract.MARQUEE_BOUNCE) != 0
                : FocusRestoreSettings.DEFAULT_MARQUEE_BOUNCE;
        boolean islandCompat = hasValue(cursor, columnCount, SettingsContract.ISLAND_COMPAT)
                && cursor.getInt(SettingsContract.ISLAND_COMPAT) != 0;
        boolean disableIslandProperty = hasValue(cursor, columnCount,
                SettingsContract.DISABLE_ISLAND_PROPERTY)
                ? cursor.getInt(SettingsContract.DISABLE_ISLAND_PROPERTY) != 0
                : FocusRestoreSettings.DEFAULT_DISABLE_ISLAND_PROPERTY;
        boolean disableIslandFeatureCache = hasValue(cursor, columnCount,
                SettingsContract.DISABLE_ISLAND_FEATURE_CACHE)
                ? cursor.getInt(SettingsContract.DISABLE_ISLAND_FEATURE_CACHE) != 0
                : FocusRestoreSettings.DEFAULT_DISABLE_ISLAND_FEATURE_CACHE;
        String legacySeparator = hasValue(cursor, columnCount,
                SettingsContract.LEGACY_ISLAND_SEPARATOR)
                ? cursor.getString(SettingsContract.LEGACY_ISLAND_SEPARATOR)
                : FocusRestoreSettings.DEFAULT_ISLAND_SEPARATOR;
        boolean allowFocusClick = hasValue(cursor, columnCount,
                SettingsContract.ALLOW_FOCUS_CLICK)
                && cursor.getInt(SettingsContract.ALLOW_FOCUS_CLICK) != 0;
        String generalSeparator = hasValue(cursor, columnCount,
                SettingsContract.ISLAND_GENERAL_SEPARATOR)
                ? cursor.getString(SettingsContract.ISLAND_GENERAL_SEPARATOR) : legacySeparator;
        String sideSeparator = hasValue(cursor, columnCount,
                SettingsContract.ISLAND_SIDE_SEPARATOR)
                ? cursor.getString(SettingsContract.ISLAND_SIDE_SEPARATOR) : legacySeparator;
        Set<String> forcePackages = hasValue(cursor, columnCount,
                SettingsContract.ISLAND_FORCE_PACKAGES)
                ? splitPackages(cursor.getString(SettingsContract.ISLAND_FORCE_PACKAGES))
                : Collections.<String>emptySet();
        Set<String> timeoutExemptPackages = hasValue(cursor, columnCount,
                SettingsContract.FOCUS_TIMEOUT_EXEMPT_PACKAGES)
                ? splitPackages(cursor.getString(SettingsContract.FOCUS_TIMEOUT_EXEMPT_PACKAGES))
                : Collections.<String>emptySet();

        int islandTextMode = hasValue(cursor, columnCount, SettingsContract.ISLAND_TEXT_MODE)
                ? cursor.getInt(SettingsContract.ISLAND_TEXT_MODE)
                : FocusRestoreSettings.DEFAULT_ISLAND_TEXT_MODE;
        float focusMaxDisplaySeconds = hasValue(cursor, columnCount,
                SettingsContract.FOCUS_MAX_DISPLAY_SECONDS)
                ? maxDisplaySeconds(cursor, SettingsContract.FOCUS_MAX_DISPLAY_SECONDS)
                : FocusRestoreSettings.DEFAULT_FOCUS_MAX_DISPLAY_SECONDS;
        int widthLandscapeDp = hasValue(cursor, columnCount, SettingsContract.WIDTH_LANDSCAPE_DP)
                ? cursor.getInt(SettingsContract.WIDTH_LANDSCAPE_DP) : widthDp;
        boolean specialBannerNormalBackground = hasValue(cursor, columnCount,
                SettingsContract.SPECIAL_BANNER_NORMAL_BACKGROUND)
                && cursor.getInt(SettingsContract.SPECIAL_BANNER_NORMAL_BACKGROUND) != 0;
        boolean mediaFocusEnabled = hasValue(cursor, columnCount, SettingsContract.MEDIA_FOCUS_ENABLED)
                && cursor.getInt(SettingsContract.MEDIA_FOCUS_ENABLED) != 0;
        boolean mediaFocusNativeBanner = hasValue(cursor, columnCount,
                SettingsContract.MEDIA_FOCUS_NATIVE_BANNER)
                ? cursor.getInt(SettingsContract.MEDIA_FOCUS_NATIVE_BANNER) != 0
                : FocusRestoreSettings.DEFAULT_MEDIA_FOCUS_NATIVE_BANNER;
        int mediaFocusCastPicker = hasValue(cursor, columnCount,
                SettingsContract.MEDIA_FOCUS_CAST_PICKER)
                ? cursor.getInt(SettingsContract.MEDIA_FOCUS_CAST_PICKER)
                : FocusRestoreSettings.DEFAULT_MEDIA_FOCUS_CAST_PICKER;
        boolean mediaFocusCastDirect = hasValue(cursor, columnCount,
                SettingsContract.MEDIA_FOCUS_CAST_DIRECT)
                ? cursor.getInt(SettingsContract.MEDIA_FOCUS_CAST_DIRECT) != 0
                : FocusRestoreSettings.DEFAULT_MEDIA_FOCUS_CAST_DIRECT;
        boolean hideNotificationIcons = hasValue(cursor, columnCount,
                SettingsContract.HIDE_NOTIFICATION_ICONS)
                ? cursor.getInt(SettingsContract.HIDE_NOTIFICATION_ICONS) != 0
                : FocusRestoreSettings.DEFAULT_HIDE_NOTIFICATION_ICONS;
        boolean showFocusDivider = hasValue(cursor, columnCount,
                SettingsContract.SHOW_FOCUS_DIVIDER)
                ? cursor.getInt(SettingsContract.SHOW_FOCUS_DIVIDER) != 0
                : FocusRestoreSettings.DEFAULT_SHOW_FOCUS_DIVIDER;
        boolean showIslandIcon = hasValue(cursor, columnCount,
                SettingsContract.SHOW_ISLAND_ICON)
                ? cursor.getInt(SettingsContract.SHOW_ISLAND_ICON) != 0
                : FocusRestoreSettings.DEFAULT_SHOW_ISLAND_ICON;
        boolean tintIslandIcon = hasValue(cursor, columnCount,
                SettingsContract.TINT_ISLAND_ICON)
                ? cursor.getInt(SettingsContract.TINT_ISLAND_ICON) != 0
                : FocusRestoreSettings.DEFAULT_TINT_ISLAND_ICON;
        boolean useSmallIconFallback = hasValue(cursor, columnCount,
                SettingsContract.USE_SMALL_ICON_FALLBACK)
                ? cursor.getInt(SettingsContract.USE_SMALL_ICON_FALLBACK) != 0
                : FocusRestoreSettings.DEFAULT_USE_SMALL_ICON_FALLBACK;
        boolean notificationRowClickFallback = hasValue(cursor, columnCount,
                SettingsContract.NOTIFICATION_ROW_CLICK_FALLBACK)
                ? cursor.getInt(SettingsContract.NOTIFICATION_ROW_CLICK_FALLBACK) != 0
                : FocusRestoreSettings.DEFAULT_NOTIFICATION_ROW_CLICK_FALLBACK;
        boolean independentFocusBanner = hasValue(cursor, columnCount,
                SettingsContract.INDEPENDENT_FOCUS_BANNER)
                ? cursor.getInt(SettingsContract.INDEPENDENT_FOCUS_BANNER) != 0
                : FocusRestoreSettings.DEFAULT_INDEPENDENT_FOCUS_BANNER;

        int hookMode = hasValue(cursor, columnCount, SettingsContract.HOOK_MODE)
                ? cursor.getInt(SettingsContract.HOOK_MODE) : FocusRestoreSettings.DEFAULT_HOOK_MODE;
        return new HookSettings(hookMode, limitWidth, widthDp, widthLandscapeDp,
                specialBannerNormalBackground,
                mediaFocusEnabled, mediaFocusNativeBanner, mediaFocusCastPicker,
                mediaFocusCastDirect, marqueeDelayMs,
                compatRetry, marqueeBounce,
                islandCompat, disableIslandProperty, disableIslandFeatureCache,
                allowFocusClick, hideNotificationIcons, showFocusDivider,
                showIslandIcon, tintIslandIcon, useSmallIconFallback,
                notificationRowClickFallback, independentFocusBanner,
                islandTextMode, focusMaxDisplaySeconds,
                generalSeparator, sideSeparator, forcePackages, timeoutExemptPackages);
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
                + " showIslandIcon=" + showIslandIcon
                + " tintIslandIcon=" + tintIslandIcon
                + " expandIslandOnClick=false(deprecated)"
                + " useSmallIconFallback=" + useSmallIconFallback
                + " notificationRowClickFallback=" + notificationRowClickFallback
                + " independentFocusBanner=" + independentFocusBanner
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

    private static boolean hasValue(Cursor cursor, int columnCount, int index) {
        return columnCount > index && !cursor.isNull(index);
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