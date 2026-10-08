/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/** Centralized persisted settings and compatibility defaults. */
public final class FocusRestoreSettings {
    public static final String PREFS_NAME = "com.hyperos3.focusrestore_preferences";

    public static final String KEY_LIMIT_WIDTH = "limit_text_width";
    public static final String KEY_WIDTH_DP = "text_width_dp";
    public static final String KEY_WIDTH_LANDSCAPE_DP = "text_width_landscape_dp";
    public static final String KEY_SPECIAL_BANNER_NORMAL_BACKGROUND = "special_banner_normal_background";
    public static final String KEY_NOTIFICATION_ICON_HIDE_MODE = "notification_icon_hide_mode";
    public static final String KEY_MEDIA_FOCUS_ENABLED = "media_focus_enabled";
    public static final String KEY_MEDIA_FOCUS_NATIVE_BANNER = "media_focus_native_banner";
    /** Which picker the media banner's seamless-transfer icon opens. */
    public static final String KEY_MEDIA_FOCUS_CAST_PICKER = "media_focus_cast_picker";
    /** Clicks a media focus straight into the cast UI instead of the media banner. */
    public static final String KEY_MEDIA_FOCUS_CAST_DIRECT = "media_focus_cast_direct";
    public static final String KEY_MARQUEE_DELAY_MS = "marquee_delay_ms";
    public static final String KEY_COMPAT_RETRY = "compat_retry";
    public static final String KEY_MARQUEE_BOUNCE = "marquee_bounce";
    public static final String KEY_ISLAND_COMPAT = "island_compat";
    public static final String KEY_ISLAND_SEPARATOR = "island_separator";
    public static final String KEY_ALLOW_FOCUS_CLICK = "allow_focus_click";
    public static final String KEY_ISLAND_GENERAL_SEPARATOR = "island_general_separator";
    public static final String KEY_ISLAND_SIDE_SEPARATOR = "island_side_separator";
    public static final String KEY_ISLAND_FORCE_PACKAGES = "island_force_packages";
    /** Apps that keep the system's own focus duration instead of the configured display limit. */
    public static final String KEY_FOCUS_TIMEOUT_EXEMPT_PACKAGES = "focus_timeout_exempt_packages";
    public static final String KEY_ISLAND_APP_CACHE = "island_app_cache";
    public static final String KEY_DISABLE_ISLAND_PROPERTY = "disable_island_property";
    public static final String KEY_DISABLE_ISLAND_FEATURE_CACHE = "disable_island_feature_cache";
    public static final String KEY_HOOK_MODE = "hook_mode";
    public static final String KEY_HIDE_NOTIFICATION_ICONS = "hide_notification_icons";
    public static final String KEY_SHOW_FOCUS_DIVIDER = "show_focus_divider";
    public static final String KEY_SHOW_ISLAND_ICON = "show_island_icon";
    public static final String KEY_TINT_ISLAND_ICON = "tint_island_icon";
    public static final String KEY_EXPAND_ISLAND_ON_CLICK = "expand_island_on_click";
    public static final String KEY_USE_SMALL_ICON_FALLBACK = "use_small_icon_fallback";
    public static final String KEY_NOTIFICATION_ROW_CLICK_FALLBACK = "notification_row_click_fallback";
    public static final String KEY_INDEPENDENT_FOCUS_BANNER = "independent_focus_banner";
    public static final String KEY_LONG_PRESS_NOTIFICATION_ROW_CLICK = "long_press_notification_row_click";
    public static final String KEY_LONG_PRESS_SECONDS = "long_press_seconds";
    public static final String KEY_ISLAND_TEXT_MODE = "island_text_mode";
    public static final String KEY_FOCUS_MAX_DISPLAY_SECONDS = "focus_max_display_seconds";
    static final String KEY_HOOK_SETTINGS_READY = "hook_settings_ready";
    static final String KEY_SETTINGS_GENERATION = "settings_generation";
    public static final String PACKAGE_SET_SEPARATOR = "\u001f";

    public static final int HOOK_MODE_OS3 = 3;
    public static final int HOOK_MODE_OS4 = 4;
    public static final int DEFAULT_HOOK_MODE = HOOK_MODE_OS3;

    public static final boolean DEFAULT_LIMIT_WIDTH = true;
    public static final int DEFAULT_WIDTH_DP = 160;
    public static final int MIN_WIDTH_DP = 80;
    public static final int MAX_WIDTH_DP = 400;
    public static final int MAX_LANDSCAPE_WIDTH_DP = 1600;
    public static final int DEFAULT_WIDTH_LANDSCAPE_DP = DEFAULT_WIDTH_DP;
    public static final boolean DEFAULT_SPECIAL_BANNER_NORMAL_BACKGROUND = true;
    public static final boolean DEFAULT_MEDIA_FOCUS_ENABLED = false;
    public static final boolean DEFAULT_MEDIA_FOCUS_NATIVE_BANNER = false;
    /** 1 uses the Android picker; 2 uses MIUI 妙播. */
    public static final int CAST_PICKER_NATIVE = 1;
    public static final int CAST_PICKER_MIPLAY = 2;
    public static final int DEFAULT_MEDIA_FOCUS_CAST_PICKER = CAST_PICKER_MIPLAY;
    public static final boolean DEFAULT_MEDIA_FOCUS_CAST_DIRECT = false;
    public static final int DEFAULT_MARQUEE_DELAY_MS = 200;
    public static final boolean DEFAULT_COMPAT_RETRY = false;
    public static final boolean DEFAULT_MARQUEE_BOUNCE = true;
    public static final boolean DEFAULT_ISLAND_COMPAT = false;
    public static final boolean DEFAULT_DISABLE_ISLAND_PROPERTY = true;
    public static final boolean DEFAULT_DISABLE_ISLAND_FEATURE_CACHE = true;
    public static final boolean DEFAULT_ALLOW_FOCUS_CLICK = false;
    public static final boolean DEFAULT_HIDE_NOTIFICATION_ICONS = true;
    public static final boolean DEFAULT_SHOW_FOCUS_DIVIDER = true;
    public static final boolean DEFAULT_SHOW_ISLAND_ICON = false;
    public static final boolean DEFAULT_TINT_ISLAND_ICON = true;
    public static final boolean DEFAULT_USE_SMALL_ICON_FALLBACK = false;
    public static final boolean DEFAULT_NOTIFICATION_ROW_CLICK_FALLBACK = false;
    public static final boolean DEFAULT_INDEPENDENT_FOCUS_BANNER = false;
    public static final boolean DEFAULT_LONG_PRESS_NOTIFICATION_ROW_CLICK = false;
    public static final float DEFAULT_LONG_PRESS_SECONDS = 0.5f;
    public static final float MIN_LONG_PRESS_SECONDS = 0.2f;
    public static final float MAX_LONG_PRESS_SECONDS = 10f;
    public static final String DEFAULT_ISLAND_SEPARATOR = "·";
    /** Seconds, so the settings input can accept two decimal places. Zero means unlimited. */
    public static final float DEFAULT_FOCUS_MAX_DISPLAY_SECONDS = 0f;
    public static final float MIN_FOCUS_MAX_DISPLAY_SECONDS = 2f;
    public static final float MAX_FOCUS_MAX_DISPLAY_SECONDS = 3600f;
    public static final int ISLAND_TEXT_MODE_FULL = 0;
    public static final int ISLAND_TEXT_MODE_COMPACT = 1;
    public static final int DEFAULT_ISLAND_TEXT_MODE = ISLAND_TEXT_MODE_FULL;

    public final int hookMode;
    public final boolean limitWidth;
    public final int widthDp;
    public final int marqueeDelayMs;
    public final int widthLandscapeDp;
    public final boolean specialBannerNormalBackground;
    public final boolean mediaFocusEnabled;
    public final boolean mediaFocusNativeBanner;
    public final int mediaFocusCastPicker;
    public final boolean mediaFocusCastDirect;
    public final boolean compatRetry;
    public final boolean marqueeBounce;
    public final boolean islandCompat;
    public final boolean disableIslandProperty;
    public final boolean disableIslandFeatureCache;
    public final boolean allowFocusClick;
    public final boolean hideNotificationIcons;
    public final boolean showFocusDivider;
    public final boolean showIslandIcon;
    public final boolean tintIslandIcon;
    public final boolean useSmallIconFallback;
    public final boolean notificationRowClickFallback;
    public final boolean independentFocusBanner;
    public final boolean longPressNotificationRowClick;
    public final float longPressSeconds;
    public final int islandTextMode;
    public final float focusMaxDisplaySeconds;
    public final String islandGeneralSeparator;
    public final String islandSideSeparator;
    public final Set<String> islandForcePackages;
    public final Set<String> focusTimeoutExemptPackages;

    private FocusRestoreSettings(Editor editor) {
        this.hookMode = normalizeHookMode(editor.hookMode);
        this.limitWidth = editor.limitWidth;
        this.widthDp = clamp(editor.widthDp, MIN_WIDTH_DP, MAX_WIDTH_DP);
        this.marqueeDelayMs = clamp(editor.marqueeDelayMs, 0, 5000);
        this.widthLandscapeDp = clamp(editor.widthLandscapeDp, MIN_WIDTH_DP,
                MAX_LANDSCAPE_WIDTH_DP);
        this.specialBannerNormalBackground = editor.specialBannerNormalBackground;
        this.mediaFocusEnabled = editor.mediaFocusEnabled;
        this.mediaFocusCastPicker = normalizeCastPicker(editor.mediaFocusCastPicker);
        // 二选一：直接展开流转界面时不再走媒体横幅。
        this.mediaFocusCastDirect = editor.mediaFocusCastDirect;
        this.mediaFocusNativeBanner = editor.mediaFocusNativeBanner && !this.mediaFocusCastDirect;
        this.compatRetry = editor.compatRetry;
        this.marqueeBounce = editor.marqueeBounce;
        this.islandCompat = editor.islandCompat;
        this.disableIslandProperty = editor.disableIslandProperty;
        this.disableIslandFeatureCache = editor.disableIslandFeatureCache;
        this.allowFocusClick = editor.allowFocusClick;
        this.hideNotificationIcons = editor.hideNotificationIcons;
        this.showFocusDivider = editor.showFocusDivider;
        this.showIslandIcon = editor.showIslandIcon;
        this.tintIslandIcon = editor.tintIslandIcon;
        this.useSmallIconFallback = editor.useSmallIconFallback;
        this.notificationRowClickFallback = editor.notificationRowClickFallback;
        this.independentFocusBanner = editor.independentFocusBanner;
        this.longPressNotificationRowClick = editor.longPressNotificationRowClick;
        this.longPressSeconds = normalizeLongPressSeconds(editor.longPressSeconds);
        this.islandTextMode = normalizeIslandTextMode(editor.islandTextMode);
        this.focusMaxDisplaySeconds = normalizeMaxDisplaySeconds(editor.focusMaxDisplaySeconds);
        this.islandGeneralSeparator = valueOrDefault(editor.islandGeneralSeparator);
        this.islandSideSeparator = valueOrDefault(editor.islandSideSeparator);
        this.islandForcePackages = immutablePackages(editor.islandForcePackages);
        this.focusTimeoutExemptPackages = immutablePackages(editor.focusTimeoutExemptPackages);
    }

    /**
     * Named-field editor for this immutable snapshot.
     *
     * <p>The previous API took twenty-two positional arguments, so a forgotten or swapped value
     * still compiled and silently produced a wrong snapshot; naming every field makes that class of
     * mistake impossible.
     */
    public static final class Editor {
        private int hookMode = DEFAULT_HOOK_MODE;
        private boolean limitWidth = DEFAULT_LIMIT_WIDTH;
        private int widthDp = DEFAULT_WIDTH_DP;
        private int widthLandscapeDp = DEFAULT_WIDTH_LANDSCAPE_DP;
        private boolean specialBannerNormalBackground = DEFAULT_SPECIAL_BANNER_NORMAL_BACKGROUND;
        private boolean mediaFocusEnabled = DEFAULT_MEDIA_FOCUS_ENABLED;
        private boolean mediaFocusNativeBanner = DEFAULT_MEDIA_FOCUS_NATIVE_BANNER;
        private int mediaFocusCastPicker = DEFAULT_MEDIA_FOCUS_CAST_PICKER;
        private boolean mediaFocusCastDirect = DEFAULT_MEDIA_FOCUS_CAST_DIRECT;
        private int marqueeDelayMs = DEFAULT_MARQUEE_DELAY_MS;
        private boolean compatRetry = DEFAULT_COMPAT_RETRY;
        private boolean marqueeBounce = DEFAULT_MARQUEE_BOUNCE;
        private boolean islandCompat = DEFAULT_ISLAND_COMPAT;
        private boolean disableIslandProperty = DEFAULT_DISABLE_ISLAND_PROPERTY;
        private boolean disableIslandFeatureCache = DEFAULT_DISABLE_ISLAND_FEATURE_CACHE;
        private boolean allowFocusClick = DEFAULT_ALLOW_FOCUS_CLICK;
        private boolean hideNotificationIcons = DEFAULT_HIDE_NOTIFICATION_ICONS;
        private boolean showFocusDivider = DEFAULT_SHOW_FOCUS_DIVIDER;
        private boolean showIslandIcon = DEFAULT_SHOW_ISLAND_ICON;
        private boolean tintIslandIcon = DEFAULT_TINT_ISLAND_ICON;
        private boolean useSmallIconFallback = DEFAULT_USE_SMALL_ICON_FALLBACK;
        private boolean notificationRowClickFallback = DEFAULT_NOTIFICATION_ROW_CLICK_FALLBACK;
        private boolean independentFocusBanner = DEFAULT_INDEPENDENT_FOCUS_BANNER;
        private boolean longPressNotificationRowClick = DEFAULT_LONG_PRESS_NOTIFICATION_ROW_CLICK;
        private float longPressSeconds = DEFAULT_LONG_PRESS_SECONDS;
        private int islandTextMode = DEFAULT_ISLAND_TEXT_MODE;
        private float focusMaxDisplaySeconds = DEFAULT_FOCUS_MAX_DISPLAY_SECONDS;
        private String islandGeneralSeparator = DEFAULT_ISLAND_SEPARATOR;
        private String islandSideSeparator = DEFAULT_ISLAND_SEPARATOR;
        private Set<String> islandForcePackages = Collections.emptySet();
        private Set<String> focusTimeoutExemptPackages = Collections.emptySet();

        private Editor() {
        }

        private Editor(FocusRestoreSettings source) {
            this.hookMode = source.hookMode;
            this.limitWidth = source.limitWidth;
            this.widthDp = source.widthDp;
            this.widthLandscapeDp = source.widthLandscapeDp;
            this.specialBannerNormalBackground = source.specialBannerNormalBackground;
            this.mediaFocusEnabled = source.mediaFocusEnabled;
            this.mediaFocusNativeBanner = source.mediaFocusNativeBanner;
            this.mediaFocusCastPicker = source.mediaFocusCastPicker;
            this.mediaFocusCastDirect = source.mediaFocusCastDirect;
            this.marqueeDelayMs = source.marqueeDelayMs;
            this.compatRetry = source.compatRetry;
            this.marqueeBounce = source.marqueeBounce;
            this.islandCompat = source.islandCompat;
            this.disableIslandProperty = source.disableIslandProperty;
            this.disableIslandFeatureCache = source.disableIslandFeatureCache;
            this.allowFocusClick = source.allowFocusClick;
            this.hideNotificationIcons = source.hideNotificationIcons;
            this.showFocusDivider = source.showFocusDivider;
            this.showIslandIcon = source.showIslandIcon;
            this.tintIslandIcon = source.tintIslandIcon;
            this.useSmallIconFallback = source.useSmallIconFallback;
            this.notificationRowClickFallback = source.notificationRowClickFallback;
            this.independentFocusBanner = source.independentFocusBanner;
            this.longPressNotificationRowClick = source.longPressNotificationRowClick;
            this.longPressSeconds = source.longPressSeconds;
            this.islandTextMode = source.islandTextMode;
            this.focusMaxDisplaySeconds = source.focusMaxDisplaySeconds;
            this.islandGeneralSeparator = source.islandGeneralSeparator;
            this.islandSideSeparator = source.islandSideSeparator;
            this.islandForcePackages = source.islandForcePackages;
            this.focusTimeoutExemptPackages = source.focusTimeoutExemptPackages;
        }

        public Editor hookMode(int value) { this.hookMode = value; return this; }
        public Editor limitWidth(boolean value) { this.limitWidth = value; return this; }
        public Editor widthDp(int value) { this.widthDp = value; return this; }
        public Editor marqueeDelayMs(int value) { this.marqueeDelayMs = value; return this; }
        public Editor widthLandscapeDp(int value) { this.widthLandscapeDp = value; return this; }
        public Editor specialBannerNormalBackground(boolean value) { this.specialBannerNormalBackground = value; return this; }
        public Editor mediaFocusEnabled(boolean value) { this.mediaFocusEnabled = value; return this; }
        public Editor mediaFocusNativeBanner(boolean value) { this.mediaFocusNativeBanner = value; return this; }
        public Editor mediaFocusCastPicker(int value) { this.mediaFocusCastPicker = value; return this; }
        public Editor mediaFocusCastDirect(boolean value) { this.mediaFocusCastDirect = value; return this; }
        public Editor compatRetry(boolean value) { this.compatRetry = value; return this; }
        public Editor marqueeBounce(boolean value) { this.marqueeBounce = value; return this; }
        public Editor islandCompat(boolean value) { this.islandCompat = value; return this; }
        public Editor disableIslandProperty(boolean value) {
            this.disableIslandProperty = value; return this;
        }
        public Editor disableIslandFeatureCache(boolean value) {
            this.disableIslandFeatureCache = value; return this;
        }
        public Editor allowFocusClick(boolean value) { this.allowFocusClick = value; return this; }
        public Editor hideNotificationIcons(boolean value) {
            this.hideNotificationIcons = value; return this;
        }
        public Editor showFocusDivider(boolean value) { this.showFocusDivider = value; return this; }
        public Editor showIslandIcon(boolean value) { this.showIslandIcon = value; return this; }
        public Editor tintIslandIcon(boolean value) { this.tintIslandIcon = value; return this; }
        public Editor useSmallIconFallback(boolean value) {
            this.useSmallIconFallback = value; return this;
        }
        public Editor notificationRowClickFallback(boolean value) {
            this.notificationRowClickFallback = value; return this;
        }
        public Editor independentFocusBanner(boolean value) {
            this.independentFocusBanner = value; return this;
        }
        public Editor longPressNotificationRowClick(boolean value) {
            this.longPressNotificationRowClick = value; return this;
        }
        public Editor longPressSeconds(float value) { this.longPressSeconds = value; return this; }
        public Editor islandTextMode(int value) { this.islandTextMode = value; return this; }
        public Editor focusMaxDisplaySeconds(float value) {
            this.focusMaxDisplaySeconds = value; return this;
        }
        public Editor islandGeneralSeparator(String value) {
            this.islandGeneralSeparator = value; return this;
        }
        public Editor islandSideSeparator(String value) {
            this.islandSideSeparator = value; return this;
        }
        public Editor islandForcePackages(Set<String> value) {
            this.islandForcePackages = value; return this;
        }
        public Editor focusTimeoutExemptPackages(Set<String> value) {
            this.focusTimeoutExemptPackages = value; return this;
        }

        public FocusRestoreSettings build() {
            return new FocusRestoreSettings(this);
        }
    }

    /** An editor pre-filled with the compatibility defaults. */
    public static Editor edit() {
        return new Editor();
    }

    /** An editor pre-filled with an existing snapshot. */
    public static Editor edit(FocusRestoreSettings source) {
        return new Editor(source);
    }

    public static FocusRestoreSettings defaults() {
        return edit().build();
    }

    public static SharedPreferences hookPreferences(Context context) {
        Context storage = context.createDeviceProtectedStorageContext();
        return storage.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static boolean hasHookSettings(SharedPreferences preferences) {
        return preferences.getBoolean(KEY_HOOK_SETTINGS_READY, false);
    }

    static long generation(SharedPreferences preferences) {
        return Math.max(0L, preferences.getLong(KEY_SETTINGS_GENERATION, 0L));
    }

    public static FocusRestoreSettings fromPreferences(SharedPreferences preferences) {
        String legacy = preferences.getString(KEY_ISLAND_SEPARATOR, DEFAULT_ISLAND_SEPARATOR);
        return edit()
                .hookMode(preferences.getInt(KEY_HOOK_MODE, DEFAULT_HOOK_MODE))
                .limitWidth(preferences.getBoolean(KEY_LIMIT_WIDTH, DEFAULT_LIMIT_WIDTH))
                .widthDp(preferences.getInt(KEY_WIDTH_DP, DEFAULT_WIDTH_DP))
                .widthLandscapeDp(preferences.getInt(KEY_WIDTH_LANDSCAPE_DP,
                        preferences.getInt(KEY_WIDTH_DP, DEFAULT_WIDTH_DP)))
                .specialBannerNormalBackground(preferences.getBoolean(KEY_SPECIAL_BANNER_NORMAL_BACKGROUND,
                        DEFAULT_SPECIAL_BANNER_NORMAL_BACKGROUND))
                .mediaFocusEnabled(preferences.getBoolean(KEY_MEDIA_FOCUS_ENABLED,
                        DEFAULT_MEDIA_FOCUS_ENABLED))
                .mediaFocusNativeBanner(preferences.getBoolean(KEY_MEDIA_FOCUS_NATIVE_BANNER,
                        DEFAULT_MEDIA_FOCUS_NATIVE_BANNER))
                .mediaFocusCastPicker(normalizeCastPicker(preferences.getInt(
                        KEY_MEDIA_FOCUS_CAST_PICKER, DEFAULT_MEDIA_FOCUS_CAST_PICKER)))
                .mediaFocusCastDirect(preferences.getBoolean(KEY_MEDIA_FOCUS_CAST_DIRECT,
                        DEFAULT_MEDIA_FOCUS_CAST_DIRECT))
                .marqueeDelayMs(preferences.getInt(KEY_MARQUEE_DELAY_MS, DEFAULT_MARQUEE_DELAY_MS))
                .compatRetry(preferences.getBoolean(KEY_COMPAT_RETRY, DEFAULT_COMPAT_RETRY))
                .marqueeBounce(preferences.getBoolean(KEY_MARQUEE_BOUNCE, DEFAULT_MARQUEE_BOUNCE))
                .islandCompat(preferences.getBoolean(KEY_ISLAND_COMPAT, DEFAULT_ISLAND_COMPAT))
                .disableIslandProperty(preferences.getBoolean(KEY_DISABLE_ISLAND_PROPERTY,
                        DEFAULT_DISABLE_ISLAND_PROPERTY))
                .disableIslandFeatureCache(preferences.getBoolean(KEY_DISABLE_ISLAND_FEATURE_CACHE,
                        DEFAULT_DISABLE_ISLAND_FEATURE_CACHE))
                .allowFocusClick(preferences.getBoolean(KEY_ALLOW_FOCUS_CLICK,
                        DEFAULT_ALLOW_FOCUS_CLICK))
                .hideNotificationIcons(preferences.getBoolean(KEY_HIDE_NOTIFICATION_ICONS,
                        DEFAULT_HIDE_NOTIFICATION_ICONS))
                .showFocusDivider(preferences.getBoolean(KEY_SHOW_FOCUS_DIVIDER,
                        DEFAULT_SHOW_FOCUS_DIVIDER))
                .showIslandIcon(preferences.getBoolean(KEY_SHOW_ISLAND_ICON,
                        DEFAULT_SHOW_ISLAND_ICON))
                .tintIslandIcon(preferences.getBoolean(KEY_TINT_ISLAND_ICON,
                        DEFAULT_TINT_ISLAND_ICON))
                .useSmallIconFallback(preferences.getBoolean(KEY_USE_SMALL_ICON_FALLBACK,
                        DEFAULT_USE_SMALL_ICON_FALLBACK))
                .notificationRowClickFallback(preferences.getBoolean(
                        KEY_NOTIFICATION_ROW_CLICK_FALLBACK,
                        DEFAULT_NOTIFICATION_ROW_CLICK_FALLBACK))
                .independentFocusBanner(preferences.getBoolean(KEY_INDEPENDENT_FOCUS_BANNER,
                        DEFAULT_INDEPENDENT_FOCUS_BANNER))
                // 旧配置没有长按字段时保持关闭，不从短按或旧点击开关推导。
                .longPressNotificationRowClick(preferences.getBoolean(
                        KEY_LONG_PRESS_NOTIFICATION_ROW_CLICK, DEFAULT_LONG_PRESS_NOTIFICATION_ROW_CLICK))
                .longPressSeconds(readLongPressSeconds(preferences))
                .islandTextMode(preferences.getInt(KEY_ISLAND_TEXT_MODE, DEFAULT_ISLAND_TEXT_MODE))
                .focusMaxDisplaySeconds(readMaxDisplaySeconds(preferences))
                .islandGeneralSeparator(preferences.getString(KEY_ISLAND_GENERAL_SEPARATOR, legacy))
                .islandSideSeparator(preferences.getString(KEY_ISLAND_SIDE_SEPARATOR, legacy))
                .islandForcePackages(preferences.getStringSet(KEY_ISLAND_FORCE_PACKAGES,
                        Collections.<String>emptySet()))
                .focusTimeoutExemptPackages(preferences.getStringSet(
                        KEY_FOCUS_TIMEOUT_EXEMPT_PACKAGES, Collections.<String>emptySet()))
                .build();
    }

    String describe() {
        return "hookMode=OS" + hookMode + " limit=" + limitWidth + " widthDp=" + widthDp
                + " widthLandscapeDp=" + widthLandscapeDp
                + " specialBannerNormalBackground=" + specialBannerNormalBackground
                + " delayMs=" + marqueeDelayMs + " compatRetry=" + compatRetry
                + " marqueeBounce=" + marqueeBounce + " islandCompat=" + islandCompat
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
                + " longPressNotificationRowClick=" + longPressNotificationRowClick
                + " longPressSeconds=" + longPressSeconds
                + " mediaFocusCastPicker=" + mediaFocusCastPicker
                + " mediaFocusCastDirect=" + mediaFocusCastDirect
                + " islandTextMode=" + islandTextMode
                + " focusMaxDisplaySeconds=" + focusMaxDisplaySeconds
                + " forcePackages=" + islandForcePackages
                + " timeoutExemptPackages=" + focusTimeoutExemptPackages
                + " islandSeparator=" + displaySeparator(islandGeneralSeparator)
                + " islandSideSeparator=" + displaySeparator(islandSideSeparator);
    }

    public boolean save(SharedPreferences preferences) {
        return save(preferences, generation(preferences) + 1L);
    }

    public boolean save(SharedPreferences preferences, long generation) {
        return preferences.edit()
                .putInt(KEY_HOOK_MODE, hookMode)
                .putBoolean(KEY_LIMIT_WIDTH, limitWidth)
                .putInt(KEY_WIDTH_DP, widthDp)
                .putInt(KEY_WIDTH_LANDSCAPE_DP, widthLandscapeDp)
                .putBoolean(KEY_SPECIAL_BANNER_NORMAL_BACKGROUND, specialBannerNormalBackground)
                .putBoolean(KEY_MEDIA_FOCUS_ENABLED, mediaFocusEnabled)
                .putBoolean(KEY_MEDIA_FOCUS_NATIVE_BANNER, mediaFocusNativeBanner)
                .putInt(KEY_MEDIA_FOCUS_CAST_PICKER, mediaFocusCastPicker)
                .putBoolean(KEY_MEDIA_FOCUS_CAST_DIRECT, mediaFocusCastDirect)
                .putInt(KEY_MARQUEE_DELAY_MS, marqueeDelayMs)
                .putBoolean(KEY_COMPAT_RETRY, compatRetry)
                .putBoolean(KEY_MARQUEE_BOUNCE, marqueeBounce)
                .putBoolean(KEY_ISLAND_COMPAT, islandCompat)
                .putBoolean(KEY_DISABLE_ISLAND_PROPERTY, disableIslandProperty)
                .putBoolean(KEY_DISABLE_ISLAND_FEATURE_CACHE, disableIslandFeatureCache)
                .putBoolean(KEY_ALLOW_FOCUS_CLICK, allowFocusClick)
                .putBoolean(KEY_HIDE_NOTIFICATION_ICONS, hideNotificationIcons)
                .putBoolean(KEY_SHOW_FOCUS_DIVIDER, showFocusDivider)
                .putBoolean(KEY_SHOW_ISLAND_ICON, showIslandIcon)
                .putBoolean(KEY_TINT_ISLAND_ICON, tintIslandIcon)
                .putBoolean(KEY_EXPAND_ISLAND_ON_CLICK, false)
                .putBoolean(KEY_USE_SMALL_ICON_FALLBACK, useSmallIconFallback)
                .putBoolean(KEY_NOTIFICATION_ROW_CLICK_FALLBACK, notificationRowClickFallback)
                .putBoolean(KEY_INDEPENDENT_FOCUS_BANNER, independentFocusBanner)
                .putBoolean(KEY_LONG_PRESS_NOTIFICATION_ROW_CLICK, longPressNotificationRowClick)
                .putFloat(KEY_LONG_PRESS_SECONDS, longPressSeconds)
                .putInt(KEY_ISLAND_TEXT_MODE, islandTextMode)
                .putFloat(KEY_FOCUS_MAX_DISPLAY_SECONDS, focusMaxDisplaySeconds)
                .putString(KEY_ISLAND_GENERAL_SEPARATOR, islandGeneralSeparator)
                .putString(KEY_ISLAND_SIDE_SEPARATOR, islandSideSeparator)
                .putString(KEY_ISLAND_SEPARATOR, islandGeneralSeparator)
                .putStringSet(KEY_ISLAND_FORCE_PACKAGES, islandForcePackages)
                .putStringSet(KEY_FOCUS_TIMEOUT_EXEMPT_PACKAGES, focusTimeoutExemptPackages)
                .putLong(KEY_SETTINGS_GENERATION, Math.max(0L, generation))
                .putBoolean(KEY_HOOK_SETTINGS_READY, true)
                .commit();
    }

    // 非有限值不能进入手势计时；有限越界值夹紧，避免立即触发或永不触发。
    static float normalizeLongPressSeconds(float seconds) {
        if (Float.isNaN(seconds) || Float.isInfinite(seconds)) return DEFAULT_LONG_PRESS_SECONDS;
        return Math.max(MIN_LONG_PRESS_SECONDS, Math.min(MAX_LONG_PRESS_SECONDS, seconds));
    }

    static float parseLongPressSeconds(String text) {
        if (text == null) return DEFAULT_LONG_PRESS_SECONDS;
        try {
            return normalizeLongPressSeconds(Float.parseFloat(text.trim()));
        } catch (NumberFormatException invalid) {
            return DEFAULT_LONG_PRESS_SECONDS;
        }
    }

    static float readLongPressSeconds(SharedPreferences preferences) {
        // 用原始类型读取，兼容手动配置或旧存储中的整数和字符串，且不回写迁移。
        Object raw = preferences.getAll().get(KEY_LONG_PRESS_SECONDS);
        if (raw instanceof Number) return normalizeLongPressSeconds(((Number) raw).floatValue());
        if (raw instanceof String) return parseLongPressSeconds((String) raw);
        return DEFAULT_LONG_PRESS_SECONDS;
    }

    static int normalizeIslandTextMode(int value) {
        return value == ISLAND_TEXT_MODE_COMPACT ? ISLAND_TEXT_MODE_COMPACT : ISLAND_TEXT_MODE_FULL;
    }

    /** Keeps the cast picker inside its two known states, falling back to the Android picker. */
    static int normalizeCastPicker(int value) {
        return value == CAST_PICKER_MIPLAY ? CAST_PICKER_MIPLAY : CAST_PICKER_NATIVE;
    }

    /**
     * True when an activity state snapshot predates the last committed write, so replaying it would
     * resurrect values the user already replaced.
     */
    static boolean snapshotIsStale(long snapshotGeneration, long persistedGeneration) {
        return snapshotGeneration >= 0L && persistedGeneration > snapshotGeneration;
    }

    /** True when a snapshot holds changes that never reached the store. */
    static boolean snapshotNeedsReplay(long snapshotGeneration, long persistedGeneration) {
        return snapshotGeneration >= 0L && persistedGeneration < snapshotGeneration;
    }

    /** Zero keeps the unlimited behaviour; anything else is clamped and rounded to two decimals. */
    static float normalizeMaxDisplaySeconds(float value) {
        if (Float.isNaN(value) || value <= 0f) return DEFAULT_FOCUS_MAX_DISPLAY_SECONDS;
        float clamped = Math.min(MAX_FOCUS_MAX_DISPLAY_SECONDS,
                Math.max(MIN_FOCUS_MAX_DISPLAY_SECONDS, value));
        return Math.round(clamped * 100f) / 100f;
    }

    /** Text as typed in the settings field; empty means unlimited, invalid text keeps the fallback. */
    static float parseMaxDisplaySeconds(String text, float fallback) {
        if (text == null) return fallback;
        String trimmed = text.trim();
        if (trimmed.length() == 0) return DEFAULT_FOCUS_MAX_DISPLAY_SECONDS;
        try {
            return normalizeMaxDisplaySeconds(Float.parseFloat(trimmed));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    static String formatMaxDisplaySeconds(float seconds) {
        float normalized = normalizeMaxDisplaySeconds(seconds);
        if (normalized <= 0f) return "0";
        if (normalized == Math.round(normalized)) return String.valueOf(Math.round(normalized));
        String text = String.format(Locale.US, "%.2f", normalized);
        while (text.endsWith("0")) text = text.substring(0, text.length() - 1);
        if (text.endsWith(".")) text = text.substring(0, text.length() - 1);
        return text;
    }

    /**
     * Reads the persisted value without assuming its boxed type so a value written by an older
     * build (int) or a hand-edited preference (string) cannot crash the settings screen.
     */
    static float readMaxDisplaySeconds(SharedPreferences preferences) {
        Object raw = preferences.getAll().get(KEY_FOCUS_MAX_DISPLAY_SECONDS);
        if (raw instanceof Number) return normalizeMaxDisplaySeconds(((Number) raw).floatValue());
        if (raw instanceof String) {
            return parseMaxDisplaySeconds((String) raw, DEFAULT_FOCUS_MAX_DISPLAY_SECONDS);
        }
        return DEFAULT_FOCUS_MAX_DISPLAY_SECONDS;
    }

    private static Set<String> immutablePackages(Set<String> packages) {
        return InputLimits.sanitizePackages(packages);
    }

    private static String valueOrDefault(String value) {
        return InputLimits.limitSeparator(
                value == null ? DEFAULT_ISLAND_SEPARATOR : value);
    }

    private static String displaySeparator(String value) {
        return value.length() == 0 ? "<empty>" : value;
    }

    static int normalizeHookMode(int value) {
        return value == HOOK_MODE_OS4 ? HOOK_MODE_OS4 : HOOK_MODE_OS3;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}