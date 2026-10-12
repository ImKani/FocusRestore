/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.database.Cursor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class SettingsContractTest {
    // 独立历史 fixture，不能借当前 COLUMNS 给旧位置数据重新命名。
    static final String[] LEGACY_COLUMNS = {
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
    };

    @Test
    public void defaultColumnsAreGroupedAndEncodingIndicesMatchNames() {
        assertArrayEquals(new String[]{
                "hook_mode", "compat_retry", "disable_island_property", "disable_island_feature_cache",
                "limit_text_width", "text_width_dp", "width_landscape_dp", "marquee_delay_ms", "marquee_bounce",
                "island_compat", "island_text_mode", "island_general_separator", "island_side_separator",
                "island_force_packages",
                "hide_notification_icons", "show_focus_divider", "show_island_icon", "tint_island_icon",
                "use_small_icon_fallback",
                "independent_focus_banner", "special_banner_normal_background", "allow_focus_click",
                "notification_row_click_fallback", "long_press_notification_row_click", "long_press_seconds",
                "focus_swipe_enabled",
                "custom_touch_width", "touch_width_dp", "touch_width_landscape_dp",
                "focus_max_display_seconds", "focus_timeout_exempt_packages",
                "media_focus_enabled", "media_focus_native_banner", "media_focus_cast_picker", "media_focus_cast_direct",
                "island_separator", "expand_island_on_click", "notification_icon_hide_mode", "island_custom_rules"
        }, SettingsContract.columnsForProjection(null));
        int[] fields = {
                SettingsContract.HOOK_MODE, SettingsContract.COMPAT_RETRY,
                SettingsContract.DISABLE_ISLAND_PROPERTY, SettingsContract.DISABLE_ISLAND_FEATURE_CACHE,
                SettingsContract.LIMIT_TEXT_WIDTH, SettingsContract.TEXT_WIDTH_DP, SettingsContract.WIDTH_LANDSCAPE_DP,
                SettingsContract.MARQUEE_DELAY_MS, SettingsContract.MARQUEE_BOUNCE,
                SettingsContract.ISLAND_COMPAT, SettingsContract.ISLAND_TEXT_MODE,
                SettingsContract.ISLAND_GENERAL_SEPARATOR, SettingsContract.ISLAND_SIDE_SEPARATOR,
                SettingsContract.ISLAND_FORCE_PACKAGES, SettingsContract.HIDE_NOTIFICATION_ICONS,
                SettingsContract.SHOW_FOCUS_DIVIDER, SettingsContract.SHOW_ISLAND_ICON,
                SettingsContract.TINT_ISLAND_ICON, SettingsContract.USE_SMALL_ICON_FALLBACK,
                SettingsContract.INDEPENDENT_FOCUS_BANNER, SettingsContract.SPECIAL_BANNER_NORMAL_BACKGROUND,
                SettingsContract.ALLOW_FOCUS_CLICK, SettingsContract.NOTIFICATION_ROW_CLICK_FALLBACK,
                SettingsContract.LONG_PRESS_NOTIFICATION_ROW_CLICK, SettingsContract.LONG_PRESS_SECONDS,
                SettingsContract.FOCUS_SWIPE_ENABLED,
                SettingsContract.CUSTOM_TOUCH_WIDTH, SettingsContract.TOUCH_WIDTH_DP,
                SettingsContract.TOUCH_WIDTH_LANDSCAPE_DP,
                SettingsContract.FOCUS_MAX_DISPLAY_SECONDS, SettingsContract.FOCUS_TIMEOUT_EXEMPT_PACKAGES,
                SettingsContract.MEDIA_FOCUS_ENABLED, SettingsContract.MEDIA_FOCUS_NATIVE_BANNER,
                SettingsContract.MEDIA_FOCUS_CAST_PICKER, SettingsContract.MEDIA_FOCUS_CAST_DIRECT,
                SettingsContract.LEGACY_ISLAND_SEPARATOR, SettingsContract.EXPAND_ISLAND_ON_CLICK,
                SettingsContract.NOTIFICATION_ICON_HIDE_MODE, SettingsContract.ISLAND_CUSTOM_RULES
        };
        assertEquals(SettingsContract.COLUMNS.length, fields.length);
        for (int index = 0; index < fields.length; index++) assertEquals(index, fields[index]);
    }

    @Test
    public void historicalProjectionsPreserveEveryPositionAndWireValue() {
        FocusRestoreSettings settings = distinctiveSettings();
        Object[] legacyRow = {0, 244, 1700, 1, 1, "legacy", 1, "general", "side",
                "com.example.focus", 0, 0, 0, 4, 0, 0, 1, 0, 0, 1, 1, 1, 1, 12.5f,
                "com.example.travel", 388, 1, 0, "", 1, 0, 2, 1, 1, 2.75f};
        for (int count : new int[]{3, 7, 9, 19, 20, 21, 22, 27, 29, 33, 35}) {
            String[] request = Arrays.copyOf(LEGACY_COLUMNS, count);
            String[] columns = SettingsContract.columnsForProjection(request);
            assertArrayEquals(request, columns);
            Object[] response = SettingsContract.toRow(settings, "legacy", columns);
            assertArrayEquals(Arrays.copyOf(legacyRow, count), response);
            // 模拟仍在运行的旧 Hook 直接读取前三个位置，不能只用新版按名 decoder 自证。
            Cursor oldHookCursor = namedCursor(columns, response);
            assertEquals(0, oldHookCursor.getInt(0));
            assertEquals(244, oldHookCursor.getInt(1));
            assertEquals(1700, oldHookCursor.getInt(2));
        }
    }

    @Test
    public void namedReaderAcceptsCurrentLegacyAndReversedColumns() throws Exception {
        FocusRestoreSettings settings = distinctiveSettings();
        HookSettings expected = HookSettings.fromSettings(settings);
        assertSnapshotsEqual(expected, HookSettings.fromCursor(namedCursor(SettingsContract.COLUMNS,
                SettingsContract.toRow(settings, "legacy"))));
        assertSnapshotsEqual(expected, HookSettings.fromCursor(namedCursor(LEGACY_COLUMNS,
                SettingsContract.toRow(settings, "legacy", LEGACY_COLUMNS))));
        String[] reversed = SettingsContract.COLUMNS.clone();
        for (int left = 0, right = reversed.length - 1; left < right; left++, right--) {
            String column = reversed[left];
            reversed[left] = reversed[right];
            reversed[right] = column;
        }
        assertSnapshotsEqual(expected, HookSettings.fromCursor(namedCursor(reversed,
                SettingsContract.toRow(settings, "legacy", reversed))));
    }

    @Test
    public void namedReaderIgnoresExtraFieldsAndRetiredPayloads() throws Exception {
        FocusRestoreSettings settings = distinctiveSettings();
        String[] columns = Arrays.copyOf(LEGACY_COLUMNS, 36);
        Object[] row = Arrays.copyOf(SettingsContract.toRow(settings, "legacy", LEGACY_COLUMNS), 36);
        columns[35] = "future_setting";
        row[35] = "unrecognized payload";
        row[18] = 1;
        row[27] = 2;
        row[28] = "must not execute";
        assertSnapshotsEqual(HookSettings.fromSettings(settings),
                HookSettings.fromCursor(namedCursor(columns, row)));
    }

    @Test
    public void missingAndNullOptionalFieldsUseIdenticalFallbacks() throws Exception {
        FocusRestoreSettings settings = distinctiveSettings();
        Object[] row = SettingsContract.toRow(settings, "legacy");
        for (int field = 0; field < SettingsContract.COLUMNS.length; field++) {
            if (field == SettingsContract.LIMIT_TEXT_WIDTH || field == SettingsContract.TEXT_WIDTH_DP
                    || field == SettingsContract.MARQUEE_DELAY_MS) continue;
            Object[] nullRow = row.clone();
            nullRow[field] = null;
            String[] missingColumns = SettingsContract.COLUMNS.clone();
            missingColumns[field] = "unknown_optional_field";
            HookSettings missing = HookSettings.fromCursor(namedCursor(missingColumns, row));
            HookSettings nulled = HookSettings.fromCursor(namedCursor(SettingsContract.COLUMNS, nullRow));
            assertSnapshotsEqual(nulled, missing);
            assertEquals(244, missing.widthDp);
            assertEquals(1700, missing.marqueeDelayMs);
        }
        String[] sparseColumns = {"long_press_seconds", "marquee_delay_ms", "island_separator",
                "text_width_dp", "allow_focus_click", "limit_text_width"};
        HookSettings sparse = HookSettings.fromCursor(namedCursor(sparseColumns,
                new Object[]{null, 1700, "old", 244, 1, 0}));
        assertEquals(244, sparse.widthLandscapeDp);
        assertEquals("old", sparse.generalSeparator);
        assertEquals("old", sparse.sideSeparator);
        assertTrue(sparse.allowFocusClick);
        assertFalse(sparse.longPressNotificationRowClick);
        assertEquals(0.5f, sparse.longPressSeconds, 0f);
        assertEquals(FocusRestoreSettings.DEFAULT_HOOK_MODE, sparse.hookMode);
        assertEquals(FocusRestoreSettings.DEFAULT_MEDIA_FOCUS_CAST_PICKER, sparse.mediaFocusCastPicker);
        assertFalse(sparse.compatRetry);
        assertFalse(sparse.islandCompat);
        assertFalse(sparse.specialBannerNormalBackground);
        assertFalse(sparse.mediaFocusEnabled);
        assertEquals(FocusRestoreSettings.DEFAULT_MARQUEE_BOUNCE, sparse.marqueeBounce);
        assertEquals(FocusRestoreSettings.DEFAULT_DISABLE_ISLAND_PROPERTY, sparse.disableIslandProperty);
        assertEquals(FocusRestoreSettings.DEFAULT_DISABLE_ISLAND_FEATURE_CACHE, sparse.disableIslandFeatureCache);
        assertEquals(FocusRestoreSettings.DEFAULT_HIDE_NOTIFICATION_ICONS, sparse.hideNotificationIcons);
        assertEquals(FocusRestoreSettings.DEFAULT_SHOW_FOCUS_DIVIDER, sparse.showFocusDivider);
        assertEquals(FocusRestoreSettings.DEFAULT_SHOW_ISLAND_ICON, sparse.showIslandIcon);
        assertEquals(FocusRestoreSettings.DEFAULT_TINT_ISLAND_ICON, sparse.tintIslandIcon);
        assertEquals(FocusRestoreSettings.DEFAULT_USE_SMALL_ICON_FALLBACK, sparse.useSmallIconFallback);
        assertEquals(FocusRestoreSettings.DEFAULT_NOTIFICATION_ROW_CLICK_FALLBACK, sparse.notificationRowClickFallback);
        assertEquals(FocusRestoreSettings.DEFAULT_INDEPENDENT_FOCUS_BANNER, sparse.independentFocusBanner);
        assertEquals(FocusRestoreSettings.DEFAULT_MEDIA_FOCUS_NATIVE_BANNER, sparse.mediaFocusNativeBanner);
        assertEquals(FocusRestoreSettings.DEFAULT_MEDIA_FOCUS_CAST_DIRECT, sparse.mediaFocusCastDirect);
        assertEquals(FocusRestoreSettings.DEFAULT_ISLAND_TEXT_MODE, sparse.islandTextMode);
        assertEquals(FocusRestoreSettings.DEFAULT_FOCUS_MAX_DISPLAY_SECONDS, sparse.focusMaxDisplaySeconds, 0f);
        assertEquals(Collections.emptySet(), sparse.islandForcePackages);
        assertEquals(Collections.emptySet(), sparse.focusTimeoutExemptPackages);
    }

    @Test
    public void namedSecondsColumnRetainsLegacyNumericAndStringEncoding() {
        for (Object seconds : new Object[]{30, 30f, "30"}) {
            Object[] row = SettingsContract.toRow(distinctiveSettings(), "legacy");
            row[SettingsContract.FOCUS_MAX_DISPLAY_SECONDS] = seconds;
            assertEquals(30f, HookSettings.fromCursor(namedCursor(SettingsContract.COLUMNS, row))
                    .focusMaxDisplaySeconds, 0f);
        }
        Object[] row = SettingsContract.toRow(distinctiveSettings(), "legacy");
        row[SettingsContract.FOCUS_MAX_DISPLAY_SECONDS] = "invalid";
        assertEquals(FocusRestoreSettings.DEFAULT_FOCUS_MAX_DISPLAY_SECONDS,
                HookSettings.fromCursor(namedCursor(SettingsContract.COLUMNS, row)).focusMaxDisplaySeconds, 0f);
    }

    @Test
    public void missingOrNullRequiredNamesRejectSnapshotEvenWithManyColumns() {
        for (int field : new int[]{SettingsContract.LIMIT_TEXT_WIDTH, SettingsContract.TEXT_WIDTH_DP,
                SettingsContract.MARQUEE_DELAY_MS}) {
            for (boolean absent : new boolean[]{false, true}) {
                String[] columns = SettingsContract.COLUMNS.clone();
                Object[] row = SettingsContract.toRow(distinctiveSettings(), "legacy");
                if (absent) columns[field] = "unknown_required_field";
                else row[field] = null;
                try {
                    HookSettings.fromCursor(namedCursor(columns, row));
                    fail("Missing required field accepted: " + SettingsContract.COLUMNS[field]);
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage().contains(SettingsContract.COLUMNS[field]));
                }
            }
        }
    }

    @Test
    public void projectionClonesRequestsAndSupportsSubsetsDuplicatesAndEmptySelection() {
        String[] request = {"long_press_seconds", "text_width_dp", "text_width_dp", "island_custom_rules"};
        String[] columns = SettingsContract.columnsForProjection(request);
        request[0] = "mutated";
        assertArrayEquals(new String[]{"long_press_seconds", "text_width_dp", "text_width_dp", "island_custom_rules"}, columns);
        assertArrayEquals(new Object[]{2.75f, 244, 244, ""},
                SettingsContract.toRow(distinctiveSettings(), "legacy", columns));
        assertEquals(0, SettingsContract.columnsForProjection(new String[0]).length);
        assertEquals(0, SettingsContract.toRow(distinctiveSettings(), "legacy", new String[0]).length);
        String[] defaults = SettingsContract.columnsForProjection(null);
        defaults[0] = "mutated";
        assertEquals("hook_mode", SettingsContract.COLUMNS[0]);
    }

    @Test
    public void unknownOrNullProjectionNamesAreRejected() {
        for (String column : new String[]{"unknown", null}) {
            try {
                SettingsContract.columnsForProjection(new String[]{"text_width_dp", column});
                fail("Invalid projection accepted");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("unknown settings column"));
            }
        }
    }

    @Test
    public void retiredIconModeDoesNotChangeLegacyHideSwitch() {
        for (boolean hidden : new boolean[]{false, true}) {
            FocusRestoreSettings settings = FocusRestoreSettings.edit().hideNotificationIcons(hidden).build();
            Object[] row = SettingsContract.toRow(settings, "");
            assertEquals(hidden ? 1 : 0, row[SettingsContract.HIDE_NOTIFICATION_ICONS]);
            assertEquals(0, row[SettingsContract.NOTIFICATION_ICON_HIDE_MODE]);
            assertEquals(hidden, HookSettings.fromSettings(settings).hideNotificationIcons);
        }
    }

    @Test
    public void castPickerChoiceRoundTripsThroughContract() {
        for (int picker : new int[]{FocusRestoreSettings.CAST_PICKER_NATIVE, FocusRestoreSettings.CAST_PICKER_MIPLAY}) {
            FocusRestoreSettings settings = FocusRestoreSettings.edit().mediaFocusCastPicker(picker).build();
            Object[] row = SettingsContract.toRow(settings, "");
            assertEquals(picker, row[SettingsContract.MEDIA_FOCUS_CAST_PICKER]);
            assertEquals(picker, HookSettings.fromCursor(namedCursor(SettingsContract.COLUMNS, row)).mediaFocusCastPicker);
        }
        assertEquals(FocusRestoreSettings.CAST_PICKER_NATIVE, FocusRestoreSettings.normalizeCastPicker(0));
        assertEquals(FocusRestoreSettings.CAST_PICKER_NATIVE, FocusRestoreSettings.normalizeCastPicker(7));
    }

    @Test
    public void directCastReplacesTheMediaBanner() {
        FocusRestoreSettings direct = FocusRestoreSettings.edit().mediaFocusNativeBanner(true).mediaFocusCastDirect(true).build();
        assertTrue(direct.mediaFocusCastDirect);
        assertFalse(direct.mediaFocusNativeBanner);
        assertFalse(HookSettings.fromSettings(direct).mediaFocusNativeBanner);
        FocusRestoreSettings banner = FocusRestoreSettings.edit().mediaFocusNativeBanner(true).mediaFocusCastDirect(false).build();
        assertTrue(banner.mediaFocusNativeBanner);
        assertFalse(banner.mediaFocusCastDirect);
    }

    private static FocusRestoreSettings distinctiveSettings() {
        return FocusRestoreSettings.edit().hookMode(4).limitWidth(false).widthDp(244).widthLandscapeDp(388)
                .marqueeDelayMs(1700).compatRetry(true).marqueeBounce(false).islandCompat(true)
                .disableIslandProperty(false).disableIslandFeatureCache(false).allowFocusClick(true)
                .hideNotificationIcons(false).showFocusDivider(false).showIslandIcon(true).tintIslandIcon(false)
                .useSmallIconFallback(true).notificationRowClickFallback(true).independentFocusBanner(true)
                .specialBannerNormalBackground(true).longPressNotificationRowClick(true).longPressSeconds(2.75f)
                .islandTextMode(1).focusMaxDisplaySeconds(12.5f).islandGeneralSeparator("general").islandSideSeparator("side")
                .islandForcePackages(Collections.singleton("com.example.focus"))
                .focusTimeoutExemptPackages(Collections.singleton("com.example.travel"))
                .mediaFocusEnabled(true).mediaFocusNativeBanner(false).mediaFocusCastPicker(2).mediaFocusCastDirect(true).build();
    }

    private static void assertSnapshotsEqual(HookSettings expected, HookSettings actual) throws Exception {
        for (Field field : HookSettings.class.getDeclaredFields()) {
            assertEquals(field.getName(), field.get(expected), field.get(actual));
        }
    }

    static Cursor namedCursor(String[] columns, Object[] row) {
        assertEquals(columns.length, row.length);
        return (Cursor) Proxy.newProxyInstance(Cursor.class.getClassLoader(), new Class<?>[]{Cursor.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getColumnIndex": return Arrays.asList(columns).indexOf(args[0]);
                        case "getColumnCount": return columns.length;
                        case "isNull": return row[(Integer) args[0]] == null;
                        case "getInt": return ((Number) row[(Integer) args[0]]).intValue();
                        case "getFloat": return ((Number) row[(Integer) args[0]]).floatValue();
                        case "getString": return String.valueOf(row[(Integer) args[0]]);
                        default: throw new AssertionError("Unexpected Cursor call: " + method.getName());
                    }
                });
    }
}
