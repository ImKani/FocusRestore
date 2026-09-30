package com.hyperos3.focusrestore;

/** Append-only Provider schema shared by producer, consumer and tests. */
final class SettingsContract {
    static final int LIMIT_TEXT_WIDTH = 0;
    static final int TEXT_WIDTH_DP = 1;
    static final int MARQUEE_DELAY_MS = 2;
    static final int COMPAT_RETRY = 3;
    static final int ISLAND_COMPAT = 4;
    static final int LEGACY_ISLAND_SEPARATOR = 5;
    static final int ALLOW_FOCUS_CLICK = 6;
    static final int ISLAND_GENERAL_SEPARATOR = 7;
    static final int ISLAND_SIDE_SEPARATOR = 8;
    static final int ISLAND_FORCE_PACKAGES = 9;
    static final int DISABLE_ISLAND_PROPERTY = 10;
    static final int DISABLE_ISLAND_FEATURE_CACHE = 11;
    static final int MARQUEE_BOUNCE = 12;
    static final int HOOK_MODE = 13;
    static final int HIDE_NOTIFICATION_ICONS = 14;
    static final int SHOW_FOCUS_DIVIDER = 15;
    static final int SHOW_ISLAND_ICON = 16;
    static final int TINT_ISLAND_ICON = 17;
    static final int EXPAND_ISLAND_ON_CLICK = 18;
    static final int USE_SMALL_ICON_FALLBACK = 19;
    static final int NOTIFICATION_ROW_CLICK_FALLBACK = 20;
    static final int INDEPENDENT_FOCUS_BANNER = 21;
    static final int ISLAND_TEXT_MODE = 22;
    static final int FOCUS_MAX_DISPLAY_SECONDS = 23;
    static final int FOCUS_TIMEOUT_EXEMPT_PACKAGES = 24;
    static final int WIDTH_LANDSCAPE_DP = 25;
    static final int SPECIAL_BANNER_NORMAL_BACKGROUND = 26;
    static final int NOTIFICATION_ICON_HIDE_MODE = 27;
    static final int ISLAND_CUSTOM_RULES = 28;
    static final int MEDIA_FOCUS_ENABLED = 29;

    static final String[] COLUMNS = {
            "limit_text_width", "text_width_dp", "marquee_delay_ms", "compat_retry",
            "island_compat", "island_separator", "allow_focus_click",
            "island_general_separator", "island_side_separator", "island_force_packages",
            "disable_island_property", "disable_island_feature_cache", "marquee_bounce",
            "hook_mode", "hide_notification_icons", "show_focus_divider",
            "show_island_icon", "tint_island_icon", "expand_island_on_click",
            "use_small_icon_fallback", "notification_row_click_fallback", "independent_focus_banner",
            "island_text_mode", "focus_max_display_seconds", "focus_timeout_exempt_packages",
            "width_landscape_dp", "special_banner_normal_background", "notification_icon_hide_mode",
            "island_custom_rules", "media_focus_enabled"
    };

    /** Encode the Provider wire format here so column names and values share one contract. */
    static Object[] toRow(FocusRestoreSettings settings, String legacySeparator) {
        return new Object[]{settings.limitWidth ? 1 : 0, settings.widthDp,
                settings.marqueeDelayMs, settings.compatRetry ? 1 : 0,
                settings.islandCompat ? 1 : 0, legacySeparator,
                settings.allowFocusClick ? 1 : 0, settings.islandGeneralSeparator,
                settings.islandSideSeparator, joinPackages(settings.islandForcePackages),
                settings.disableIslandProperty ? 1 : 0,
                settings.disableIslandFeatureCache ? 1 : 0,
                settings.marqueeBounce ? 1 : 0, settings.hookMode,
                settings.hideNotificationIcons ? 1 : 0,
                settings.showFocusDivider ? 1 : 0,
                settings.showIslandIcon ? 1 : 0,
                settings.tintIslandIcon ? 1 : 0,
                0, // Retired expand_island_on_click column remains permanently disabled.
                settings.useSmallIconFallback ? 1 : 0,
                settings.notificationRowClickFallback ? 1 : 0,
                settings.independentFocusBanner ? 1 : 0,
                settings.islandTextMode, settings.focusMaxDisplaySeconds,
                joinPackages(settings.focusTimeoutExemptPackages), settings.widthLandscapeDp,
                 settings.specialBannerNormalBackground ? 1 : 0,
                 settings.notificationIconHideMode, settings.islandCustomRules,
                 settings.mediaFocusEnabled ? 1 : 0};
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
