/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SettingsContractTest {
    @Test
    public void retiredIconModeDoesNotChangeLegacyHideSwitch() {
        // 废弃模式只保留列位，旧隐藏开关必须仍能独立开启和关闭。
        for (boolean hidden : new boolean[]{false, true}) {
            FocusRestoreSettings settings = FocusRestoreSettings.edit()
                    .hideNotificationIcons(hidden).build();
            Object[] row = SettingsContract.toRow(settings, "");
            assertEquals(SettingsContract.COLUMNS.length, row.length);
            assertEquals(hidden ? 1 : 0, row[SettingsContract.HIDE_NOTIFICATION_ICONS]);
            assertEquals(0, row[SettingsContract.NOTIFICATION_ICON_HIDE_MODE]);
            assertEquals(hidden, HookSettings.fromSettings(settings).hideNotificationIcons);
        }
    }

    @Test
    public void providerColumnsRemainAppendOnlyAndIndexStable() {
        assertArrayEquals(new String[]{
                "limit_text_width", "text_width_dp", "marquee_delay_ms", "compat_retry",
                "island_compat", "island_separator", "allow_focus_click",
                "island_general_separator", "island_side_separator", "island_force_packages",
                "disable_island_property", "disable_island_feature_cache", "marquee_bounce",
                "hook_mode", "hide_notification_icons", "show_focus_divider",
                "show_island_icon", "tint_island_icon", "expand_island_on_click",
                "use_small_icon_fallback", "notification_row_click_fallback", "independent_focus_banner",
                "island_text_mode", "focus_max_display_seconds", "focus_timeout_exempt_packages",
                "width_landscape_dp", "special_banner_normal_background", "notification_icon_hide_mode",
                "island_custom_rules", "media_focus_enabled", "media_focus_native_banner",
                "media_focus_cast_picker", "media_focus_cast_direct",
                "long_press_notification_row_click", "long_press_seconds"
        }, SettingsContract.COLUMNS);
        assertEquals(13, SettingsContract.HOOK_MODE);
        assertEquals(14, SettingsContract.HIDE_NOTIFICATION_ICONS);
        assertEquals(15, SettingsContract.SHOW_FOCUS_DIVIDER);
        assertEquals(16, SettingsContract.SHOW_ISLAND_ICON);
        assertEquals(17, SettingsContract.TINT_ISLAND_ICON);
        assertEquals(18, SettingsContract.EXPAND_ISLAND_ON_CLICK);
        assertEquals(19, SettingsContract.USE_SMALL_ICON_FALLBACK);
        assertEquals(20, SettingsContract.NOTIFICATION_ROW_CLICK_FALLBACK);
        assertEquals(21, SettingsContract.INDEPENDENT_FOCUS_BANNER);
        assertEquals(22, SettingsContract.ISLAND_TEXT_MODE);
        assertEquals(23, SettingsContract.FOCUS_MAX_DISPLAY_SECONDS);
        assertEquals(24, SettingsContract.FOCUS_TIMEOUT_EXEMPT_PACKAGES);
        assertEquals(25, SettingsContract.WIDTH_LANDSCAPE_DP);
        assertEquals(26, SettingsContract.SPECIAL_BANNER_NORMAL_BACKGROUND);
        assertEquals(27, SettingsContract.NOTIFICATION_ICON_HIDE_MODE);
        assertEquals(28, SettingsContract.ISLAND_CUSTOM_RULES);
        assertEquals(29, SettingsContract.MEDIA_FOCUS_ENABLED);
        assertEquals(30, SettingsContract.MEDIA_FOCUS_NATIVE_BANNER);
        assertEquals(31, SettingsContract.MEDIA_FOCUS_CAST_PICKER);
        assertEquals(32, SettingsContract.MEDIA_FOCUS_CAST_DIRECT);
        assertEquals(33, SettingsContract.LONG_PRESS_NOTIFICATION_ROW_CLICK);
        assertEquals(34, SettingsContract.LONG_PRESS_SECONDS);
    }

    @Test
    public void castPickerChoiceRoundTripsThroughContract() {
        // 安卓原生 / 小米妙播二态必须能独立读写，未知取值回落到安卓原生。
        for (int picker : new int[]{FocusRestoreSettings.CAST_PICKER_NATIVE,
                FocusRestoreSettings.CAST_PICKER_MIPLAY}) {
            FocusRestoreSettings settings = FocusRestoreSettings.edit()
                    .mediaFocusCastPicker(picker).build();
            Object[] row = SettingsContract.toRow(settings, "");
            assertEquals(SettingsContract.COLUMNS.length, row.length);
            assertEquals(picker, row[SettingsContract.MEDIA_FOCUS_CAST_PICKER]);
            assertEquals(picker, HookSettings.fromSettings(settings).mediaFocusCastPicker);
        }
        // 旧版本写入的 0（已移除的关闭态）与任何未知取值都回落到安卓原生。
        assertEquals(FocusRestoreSettings.CAST_PICKER_NATIVE,
                FocusRestoreSettings.normalizeCastPicker(0));
        assertEquals(FocusRestoreSettings.CAST_PICKER_NATIVE,
                FocusRestoreSettings.normalizeCastPicker(7));
    }

    @Test
    public void directCastReplacesTheMediaBanner() {
        // 二选一：直接展开流转界面时媒体横幅必须关闭，反之亦然。
        FocusRestoreSettings direct = FocusRestoreSettings.edit()
                .mediaFocusNativeBanner(true).mediaFocusCastDirect(true).build();
        assertEquals(true, direct.mediaFocusCastDirect);
        assertEquals(false, direct.mediaFocusNativeBanner);
        assertEquals(false, HookSettings.fromSettings(direct).mediaFocusNativeBanner);

        FocusRestoreSettings banner = FocusRestoreSettings.edit()
                .mediaFocusNativeBanner(true).mediaFocusCastDirect(false).build();
        assertEquals(true, banner.mediaFocusNativeBanner);
        assertEquals(false, banner.mediaFocusCastDirect);
    }
}