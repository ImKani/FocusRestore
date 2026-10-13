/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.content.SharedPreferences;
import android.database.Cursor;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExperimentalSettingsTest {
    @Test
    public void iconChoicesAndIndependentTintRoundTripWithoutChangingPriority() throws Exception {
        assertEquals(0, FocusRestoreSettings.defaults().focusIconPriority);
        assertFalse(FocusRestoreSettings.defaults().showIslandIcon);
        assertTrue(FocusRestoreSettings.defaults().tintDeviceIcon);
        for (int priority = 0; priority < FocusRestoreSettings.ICON_ORDER_COUNT; priority++) {
            for (int mask = 0; mask < 8; mask++) {
                FocusRestoreSettings settings = FocusRestoreSettings.edit().focusIconPriority(priority)
                        .showIslandIcon((mask & 1) != 0).tintIslandIcon((mask & 2) != 0)
                        .tintDeviceIcon((mask & 4) != 0).build();
                Map<String, Object> values = new HashMap<>();
                SharedPreferences store = memoryPreferences(values);
                assertTrue(settings.save(store));
                for (FocusRestoreSettings copy : new FocusRestoreSettings[]{
                        FocusRestoreSettings.edit(settings).build(), FocusRestoreSettings.fromPreferences(store),
                        SettingsBackupCodec.decode(SettingsBackupCodec.encode(settings))}) {
                    assertEquals(priority, copy.focusIconPriority);
                    assertEquals(priority == 2 || priority == 5, copy.useSmallIconFallback);
                    assertEquals(settings.showIslandIcon, copy.showIslandIcon);
                    assertEquals(settings.tintIslandIcon, copy.tintIslandIcon);
                    assertEquals(settings.tintDeviceIcon, copy.tintDeviceIcon);
                    HookSettings hook = HookSettings.fromCursor(SettingsContractTest.namedCursor(
                            SettingsContract.COLUMNS, SettingsContract.toRow(copy, "")));
                    assertEquals(priority, hook.focusIconPriority);
                    assertEquals(settings.tintDeviceIcon, hook.tintDeviceIcon);
                }
                assertEquals(priority, FocusRestoreSettings.edit(settings)
                        .showIslandIcon(!settings.showIslandIcon).build().focusIconPriority);
            }
        }
    }

    @Test
    public void missingPriorityMigratesLegacyFlagsOnceAndExplicitPriorityWins() {
        for (boolean small : new boolean[]{false, true}) {
            for (boolean island : new boolean[]{false, true}) {
                Map<String, Object> values = legacyPreferences();
                values.put("use_small_icon_fallback", small);
                values.put("show_island_icon", island);
                SharedPreferences store = memoryPreferences(values);
                int expected = small ? 2 : island ? 1 : 0;
                FocusRestoreSettings migrated = FocusRestoreSettings.fromPreferences(store);
                assertEquals(expected, migrated.focusIconPriority);
                assertTrue(migrated.tintDeviceIcon);
                HookSettings hook = HookSettings.fromCursor(SettingsContractTest.namedCursor(
                        SettingsContractTest.LEGACY_COLUMNS, SettingsContract.toRow(migrated, "",
                                SettingsContractTest.LEGACY_COLUMNS)));
                assertEquals(expected, hook.focusIconPriority);
                assertTrue(hook.tintDeviceIcon);
                assertTrue(FocusRestoreSettings.edit(migrated).focusIconPriority(0).build().save(store));
                values.put("show_island_icon", true);
                values.put("use_small_icon_fallback", true);
                assertEquals(0, FocusRestoreSettings.fromPreferences(store).focusIconPriority);
            }
        }
        assertEquals(0, FocusRestoreSettings.edit().focusIconPriority(-1).build().focusIconPriority);
        assertEquals(0, FocusRestoreSettings.edit().focusIconPriority(6).build().focusIconPriority);
        assertArrayEquals(new int[]{0, 1, 2}, FocusIconPriority.order(0));
        assertArrayEquals(new int[]{1, 0, 2}, FocusIconPriority.order(1));
        assertArrayEquals(new int[]{2, 0, 1}, FocusIconPriority.order(2));
    }

    @Test
    public void allIconPermutationsKeepExactFallbackOrderAndAreIsolatedFromCallers() {
        int[][] expected = {{0, 1, 2}, {1, 0, 2}, {2, 0, 1},
                {0, 2, 1}, {1, 2, 0}, {2, 1, 0}};
        for (int priority = 0; priority < expected.length; priority++) {
            int[] order = FocusIconPriority.order(priority);
            assertArrayEquals(expected[priority], order);
            assertEquals(priority, FocusIconPriority.indexOf(order));
            order[0] = -1;
            assertArrayEquals(expected[priority], FocusIconPriority.order(priority));
        }
        assertEquals(-1, FocusIconPriority.indexOf(new int[]{0, 0, 2}));
        assertEquals(-1, FocusIconPriority.indexOf(new int[]{0, 1}));
        assertEquals(-1, FocusIconPriority.indexOf(null));
        for (int invalid : new int[]{Integer.MIN_VALUE, -1, 6, Integer.MAX_VALUE}) {
            assertArrayEquals(expected[0], FocusIconPriority.order(invalid));
        }
    }

    @Test
    public void customTouchWidthIsOptionalAndIndependentAcrossStoresProviderAndBackup() throws Exception {
        FocusRestoreSettings defaults = FocusRestoreSettings.defaults();
        assertFalse(defaults.customTouchWidth);
        assertFalse(HookSettings.defaults().customTouchWidth);
        assertEquals(160, defaults.touchWidthDp);
        assertEquals(160, defaults.touchWidthLandscapeDp);
        FocusRestoreSettings legacy = FocusRestoreSettings.fromPreferences(memoryPreferences(legacyPreferences()));
        assertFalse(legacy.customTouchWidth);
        assertEquals(160, legacy.touchWidthDp);
        assertEquals(160, legacy.touchWidthLandscapeDp);
        Map<String, Object> first = new HashMap<>();
        Map<String, Object> second = new HashMap<>();
        for (boolean enabled : new boolean[]{true, false}) {
            FocusRestoreSettings settings = FocusRestoreSettings.edit().limitWidth(false)
                    .widthDp(244).widthLandscapeDp(1200).customTouchWidth(enabled)
                    .touchWidthDp(100).touchWidthLandscapeDp(720).build();
            assertTouchWidths(FocusRestoreSettings.edit(settings).build(), enabled);
            assertTouchWidths(SettingsBackupCodec.decode(SettingsBackupCodec.encode(settings)), enabled);
            for (Map<String, Object> values : new Map[]{first, second}) {
                SharedPreferences store = memoryPreferences(values);
                assertTrue(settings.save(store));
                FocusRestoreSettings loaded = FocusRestoreSettings.fromPreferences(store);
                assertTouchWidths(loaded, enabled);
                for (HookSettings hook : new HookSettings[]{HookSettings.fromSettings(loaded),
                        HookSettings.fromCursor(SettingsContractTest.namedCursor(SettingsContract.COLUMNS,
                                SettingsContract.toRow(loaded, "legacy")))}) {
                    assertEquals(enabled, hook.customTouchWidth);
                    assertEquals(100, hook.touchWidthDp);
                    assertEquals(720, hook.touchWidthLandscapeDp);
                    assertFalse(hook.limitWidth);
                    assertEquals(244, hook.widthDp);
                    assertEquals(1200, hook.widthLandscapeDp);
                }
            }
            HookSettings old = HookSettings.fromCursor(SettingsContractTest.namedCursor(
                    SettingsContractTest.LEGACY_COLUMNS, SettingsContract.toRow(settings, "legacy",
                    SettingsContractTest.LEGACY_COLUMNS)));
            assertFalse(old.customTouchWidth);
            assertEquals(160, old.touchWidthDp);
            assertEquals(160, old.touchWidthLandscapeDp);
        }
    }

    @Test
    public void touchWidthBoundsDoNotChangeDisplayWidthOrEnableTheFeature() {
        FocusRestoreSettings settings = FocusRestoreSettings.edit().widthDp(244).widthLandscapeDp(1200)
                .touchWidthDp(-1).touchWidthLandscapeDp(Integer.MAX_VALUE).build();
        assertFalse(settings.customTouchWidth);
        assertEquals(80, settings.touchWidthDp);
        assertEquals(1600, settings.touchWidthLandscapeDp);
        assertEquals(244, settings.widthDp);
        assertEquals(1200, settings.widthLandscapeDp);
        FocusRestoreSettings reverse = FocusRestoreSettings.edit().touchWidthDp(Integer.MAX_VALUE)
                .touchWidthLandscapeDp(-1).build();
        assertEquals(400, reverse.touchWidthDp);
        assertEquals(80, reverse.touchWidthLandscapeDp);
    }

    @Test
    public void appearanceSurvivesBothStoresCopyBackupAndNamedProvider() throws Exception {
        SharedPreferences first = memoryPreferences(new HashMap<>());
        SharedPreferences second = memoryPreferences(new HashMap<>());
        // 先保存自定义再恢复自动，防止关闭或归零后只更新 UI 而存储仍残留旧值。
        for (boolean custom : new boolean[]{true, false}) {
            FocusRestoreSettings settings = FocusRestoreSettings.edit().hookMode(3).showFocusDivider(false)
                    .focusIconSizeDp(custom ? 8 : 0).focusDividerStartDp(custom ? 0 : -1)
                    .focusDividerEndDp(custom ? 24 : -1).focusDividerWidthDp(custom ? 4 : 0)
                    .focusDividerStyle(custom ? 2 : 0).build();
            for (SharedPreferences store : new SharedPreferences[]{first, second}) {
                assertTrue(settings.save(store));
                FocusRestoreSettings loaded = FocusRestoreSettings.fromPreferences(store);
                for (FocusRestoreSettings copy : new FocusRestoreSettings[]{loaded,
                        FocusRestoreSettings.edit(loaded).build(), SettingsBackupCodec.decode(SettingsBackupCodec.encode(loaded))}) {
                    HookSettings hook = HookSettings.fromCursor(SettingsContractTest.namedCursor(SettingsContract.COLUMNS, SettingsContract.toRow(copy, "")));
                    assertEquals(settings.focusIconSizeDp, copy.focusIconSizeDp);
                    assertEquals(settings.focusDividerStartDp, copy.focusDividerStartDp);
                    assertEquals(settings.focusDividerEndDp, copy.focusDividerEndDp);
                    assertEquals(settings.focusDividerWidthDp, copy.focusDividerWidthDp);
                    assertEquals(settings.focusDividerStyle, copy.focusDividerStyle);
                    assertEquals(settings.focusIconSizeDp, hook.focusIconSizeDp);
                    assertEquals(settings.focusDividerStartDp, hook.focusDividerStartDp);
                    assertEquals(settings.focusDividerEndDp, hook.focusDividerEndDp);
                    assertEquals(settings.focusDividerWidthDp, hook.focusDividerWidthDp);
                    assertEquals(settings.focusDividerStyle, hook.focusDividerStyle);
                    assertFalse(copy.showFocusDivider);
                    assertEquals(3, copy.hookMode);
                }
                assertEquals(settings.focusIconSizeDp, store.getInt("focus_icon_size_dp", -99));
                assertEquals(settings.focusDividerWidthDp, store.getInt("focus_divider_width_dp", -99));
            }
        }
        FocusRestoreSettings legacy = FocusRestoreSettings.fromPreferences(memoryPreferences(legacyPreferences()));
        assertEquals(0, legacy.focusIconSizeDp);
        assertEquals(-1, legacy.focusDividerStartDp);
        assertEquals(-1, legacy.focusDividerEndDp);
        assertEquals(0, legacy.focusDividerWidthDp);
        assertEquals(0, legacy.focusDividerStyle);
    }

    @Test
    public void appearanceBoundsAndSliderStepsAlwaysProduceSaveableValues() {
        for (int progress = 0; progress <= 25; progress++) {
            int size = FocusRestoreSettings.focusIconSizeFromProgress(progress);
            assertEquals(progress == 0 ? 0 : progress + 7, size);
            assertEquals(progress, FocusRestoreSettings.focusIconSizeToProgress(size));
            int spacing = FocusRestoreSettings.focusDividerSpacingFromProgress(progress);
            assertEquals(progress - 1, spacing);
            assertEquals(progress, FocusRestoreSettings.focusDividerSpacingToProgress(spacing));
        }
        for (int invalid : new int[]{Integer.MIN_VALUE, -1, 1, 7}) assertEquals(0, FocusRestoreSettings.normalizeFocusIconSizeDp(invalid));
        assertEquals(32, FocusRestoreSettings.normalizeFocusIconSizeDp(Integer.MAX_VALUE));
        FocusRestoreSettings bounded = FocusRestoreSettings.edit().focusDividerStartDp(-2).focusDividerEndDp(25)
                .focusDividerWidthDp(5).focusDividerStyle(4).build();
        assertEquals(-1, bounded.focusDividerStartDp);
        assertEquals(24, bounded.focusDividerEndDp);
        assertEquals(4, bounded.focusDividerWidthDp);
        assertEquals(0, bounded.focusDividerStyle);
        assertEquals(0, FocusRestoreSettings.edit().focusDividerWidthDp(-1).build().focusDividerWidthDp);
    }

    private static void assertTouchWidths(FocusRestoreSettings settings, boolean enabled) {
        assertEquals(enabled, settings.customTouchWidth);
        assertEquals(100, settings.touchWidthDp);
        assertEquals(720, settings.touchWidthLandscapeDp);
        assertFalse(settings.limitWidth);
        assertEquals(244, settings.widthDp);
        assertEquals(1200, settings.widthLandscapeDp);
    }

    @Test
    public void swipeIsOptionalAndRoundTripsThroughBothStoresAndNamedProvider() throws Exception {
        assertFalse(FocusRestoreSettings.defaults().focusSwipeEnabled);
        assertFalse(HookSettings.defaults().focusSwipeEnabled);
        assertFalse(FocusRestoreSettings.fromPreferences(memoryPreferences(legacyPreferences())).focusSwipeEnabled);
        Map<String, Object> first = new HashMap<>();
        Map<String, Object> second = new HashMap<>();
        for (boolean enabled : new boolean[]{true, false}) {
            FocusRestoreSettings settings = FocusRestoreSettings.edit().focusSwipeEnabled(enabled).build();
            assertEquals(enabled, FocusRestoreSettings.edit(settings).build().focusSwipeEnabled);
            assertEquals(enabled, HookSettings.fromSettings(settings).focusSwipeEnabled);
            for (Map<String, Object> values : new Map[]{first, second}) {
                SharedPreferences store = memoryPreferences(values);
                assertTrue(settings.save(store));
                FocusRestoreSettings loaded = FocusRestoreSettings.fromPreferences(store);
                assertEquals(enabled, loaded.focusSwipeEnabled);
                assertEquals(enabled, HookSettings.fromCursor(SettingsContractTest.namedCursor(
                        SettingsContract.COLUMNS, SettingsContract.toRow(loaded, "legacy"))).focusSwipeEnabled);
            }
            assertEquals(enabled, SettingsBackupCodec.decode(SettingsBackupCodec.encode(settings)).focusSwipeEnabled);
            assertFalse(HookSettings.fromCursor(SettingsContractTest.namedCursor(
                    SettingsContractTest.LEGACY_COLUMNS, SettingsContract.toRow(settings, "legacy",
                    SettingsContractTest.LEGACY_COLUMNS))).focusSwipeEnabled);
        }
        assertFalse(SettingsBackupCodec.decode(SettingsBackupCodec.encode(FocusRestoreSettings.defaults()))
                .focusSwipeEnabled);
    }

    @Test
    public void longPressDefaultsAndLegacyColumnsStayOff() {
        // 旧配置和旧 33 列不能继承短按开启状态，空列也必须安全关闭。
        assertFalse(FocusRestoreSettings.defaults().longPressNotificationRowClick);
        assertFalse(HookSettings.defaults().longPressNotificationRowClick);
        assertEquals(0.5f, HookSettings.defaults().longPressSeconds, 0f);
        FocusRestoreSettings legacy = FocusRestoreSettings.fromPreferences(
                memoryPreferences(legacyPreferences()));
        assertFalse(legacy.longPressNotificationRowClick);
        assertEquals(0.5f, legacy.longPressSeconds, 0f);
        Object[] row = legacyProviderRow(FocusRestoreSettings.edit()
                .allowFocusClick(true).notificationRowClickFallback(true)
                .independentFocusBanner(true).longPressNotificationRowClick(true)
                .longPressSeconds(2.75f).build(), "legacy");
        for (Object[] old : new Object[][]{Arrays.copyOf(row, 33), Arrays.copyOf(row, 35)}) {
            if (old.length == 35) {
                old[33] = null;
                old[34] = null;
            }
            HookSettings hook = HookSettings.fromCursor(positionedCursor(old));
            assertFalse(hook.longPressNotificationRowClick);
            assertEquals(0.5f, hook.longPressSeconds, 0f);
            assertTrue(hook.allowFocusClick);
            assertTrue(hook.notificationRowClickFallback);
            assertTrue(hook.independentFocusBanner);
        }
    }

    @Test
    public void longPressRoundTripsIndependentlyThroughBothStoresAndProvider() {
        Map<String, Object> credentialValues = legacyPreferences();
        Map<String, Object> deviceValues = legacyPreferences();
        SharedPreferences credential = memoryPreferences(credentialValues);
        SharedPreferences device = memoryPreferences(deviceValues);
        // 开关组合包含短按全部关闭；长按开关和时长均不得被短按状态修改。
        for (int mask = 0; mask < 16; mask++) {
            boolean enabled = (mask & 8) != 0;
            FocusRestoreSettings settings = FocusRestoreSettings.edit()
                    .allowFocusClick((mask & 1) != 0)
                    .notificationRowClickFallback((mask & 2) != 0)
                    .independentFocusBanner((mask & 4) != 0)
                    .longPressNotificationRowClick(enabled).longPressSeconds(2.75f).build();
            FocusRestoreSettings copied = FocusRestoreSettings.edit(settings).build();
            assertEquals(enabled, copied.longPressNotificationRowClick);
            assertEquals(2.75f, copied.longPressSeconds, 0f);
            assertTrue(copied.describe().contains("longPressNotificationRowClick=" + enabled));
            for (SharedPreferences store : new SharedPreferences[]{credential, device}) {
                assertTrue(copied.save(store, mask + 1L));
                FocusRestoreSettings loaded = FocusRestoreSettings.fromPreferences(store);
                assertEquals(enabled, loaded.longPressNotificationRowClick);
                assertEquals(2.75f, loaded.longPressSeconds, 0f);
                Object[] row = legacyProviderRow(loaded, "legacy");
                assertEquals(35, row.length);
                assertEquals(enabled ? 1 : 0, row[33]);
                assertEquals(2.75f, (Float) row[34], 0f);
                for (HookSettings hook : new HookSettings[]{HookSettings.fromSettings(loaded),
                        HookSettings.fromCursor(positionedCursor(row))}) {
                    assertEquals(enabled, hook.longPressNotificationRowClick);
                    assertEquals(2.75f, hook.longPressSeconds, 0f);
                    assertEquals((mask & 1) != 0, hook.allowFocusClick);
                    assertEquals((mask & 2) != 0, hook.notificationRowClickFallback);
                    assertEquals((mask & 4) != 0, hook.independentFocusBanner);
                    assertTrue(hook.describe().contains("longPressSeconds=2.75"));
                }
            }
        }
        // 在同一存储关闭已开启选项，必须覆盖 true，同时保留用户时长。
        assertTrue(FocusRestoreSettings.edit().longPressSeconds(2.75f).build().save(credential));
        assertEquals(Boolean.FALSE, credentialValues.get("long_press_notification_row_click"));
        assertEquals(2.75f, FocusRestoreSettings.fromPreferences(credential).longPressSeconds, 0f);
    }

    @Test
    public void longPressDurationNormalizesBoundsAndCompatiblePreferenceTypes() {
        assertEquals(0.2f, FocusRestoreSettings.normalizeLongPressSeconds(-1f), 0f);
        assertEquals(0.2f, FocusRestoreSettings.normalizeLongPressSeconds(0.2f), 0f);
        assertEquals(10f, FocusRestoreSettings.normalizeLongPressSeconds(10f), 0f);
        assertEquals(10f, FocusRestoreSettings.normalizeLongPressSeconds(20f), 0f);
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertEquals(0.5f, FocusRestoreSettings.normalizeLongPressSeconds(invalid), 0f);
        }
        for (String invalid : new String[]{null, "", "bad", "NaN", "Infinity"}) {
            assertEquals(0.5f, FocusRestoreSettings.parseLongPressSeconds(invalid), 0f);
        }
        assertEquals(0.2f, FocusRestoreSettings.parseLongPressSeconds("0.1"), 0f);
        assertEquals(10f, FocusRestoreSettings.parseLongPressSeconds("11"), 0f);
        assertEquals(1.25f, FocusRestoreSettings.parseLongPressSeconds(" 1.25 "), 0f);
        for (Object duration : new Object[]{2, 2f, "2"}) {
            Map<String, Object> values = legacyPreferences();
            values.put("long_press_seconds", duration);
            Map<String, Object> before = new HashMap<>(values);
            assertEquals(2f, FocusRestoreSettings.fromPreferences(memoryPreferences(values))
                    .longPressSeconds, 0f);
            assertEquals(before, values);
        }
        for (float seconds : new float[]{0.1f, 0.2f, 10f, 11f, Float.NaN, Float.POSITIVE_INFINITY}) {
            Object[] row = legacyProviderRow(FocusRestoreSettings.defaults(), "legacy");
            row[34] = seconds;
            assertEquals(FocusRestoreSettings.normalizeLongPressSeconds(seconds),
                    HookSettings.fromCursor(positionedCursor(row)).longPressSeconds, 0f);
        }
    }

    @Test
    public void iconFallbackAndBannerDefaultsMatchStatusBarPolicy() {
        FocusRestoreSettings settings = FocusRestoreSettings.defaults();
        assertFalse(settings.showIslandIcon);
        assertTrue(settings.tintIslandIcon);
        assertFalse(settings.useSmallIconFallback);
        assertFalse(settings.notificationRowClickFallback);
        assertFalse(settings.independentFocusBanner);
        assertFalse(HookSettings.defaults().independentFocusBanner);
    }

    @Test
    public void bannerAndLegacyClickSettingsRemainIndependent() {
        for (int mask = 0; mask < 8; mask++) {
            boolean allowClick = (mask & 1) != 0;
            boolean rowFallback = (mask & 2) != 0;
            boolean banner = (mask & 4) != 0;
            FocusRestoreSettings settings = FocusRestoreSettings.edit()
                    .hookMode(FocusRestoreSettings.HOOK_MODE_OS4)
                    .marqueeBounce(true)
                    .islandCompat(false)
                    .disableIslandProperty(true)
                    .disableIslandFeatureCache(true)
                    .allowFocusClick(allowClick)
                    .hideNotificationIcons(true)
                    .showFocusDivider(true)
                    .showIslandIcon(true)
                    .tintIslandIcon(true)
                    .useSmallIconFallback(false)
                    .notificationRowClickFallback(rowFallback)
                    .independentFocusBanner(banner)
                    .islandGeneralSeparator("·")
                    .islandSideSeparator("·")
                    .build();
            assertEquals(allowClick, settings.allowFocusClick);
            assertEquals(rowFallback, settings.notificationRowClickFallback);
            assertEquals(banner, settings.independentFocusBanner);
            HookSettings hook = HookSettings.fromCursor(positionedCursor(
                    legacyProviderRow(settings, "·")));
            assertEquals(allowClick, hook.allowFocusClick);
            assertEquals(rowFallback, hook.notificationRowClickFallback);
            assertEquals(banner, hook.independentFocusBanner);
        }
    }

    @Test
    public void missingOrDisabledBannerDoesNotResetExistingPreferences() {
        for (Boolean banner : new Boolean[]{null, false}) {
            Map<String, Object> values = legacyPreferences();
            if (banner != null) values.put("independent_focus_banner", banner);
            Map<String, Object> before = new HashMap<>(values);
            FocusRestoreSettings settings = FocusRestoreSettings.fromPreferences(
                    memoryPreferences(values));
            assertFalse(settings.independentFocusBanner);
            assertEquals(FocusRestoreSettings.HOOK_MODE_OS4, settings.hookMode);
            assertFalse(settings.limitWidth);
            assertEquals(244, settings.widthDp);
            assertEquals(1700, settings.marqueeDelayMs);
            assertTrue(settings.compatRetry);
            assertFalse(settings.marqueeBounce);
            assertTrue(settings.islandCompat);
            assertTrue(settings.allowFocusClick);
            assertTrue(settings.notificationRowClickFallback);
            assertEquals("general", settings.islandGeneralSeparator);
            assertEquals("side", settings.islandSideSeparator);
            assertEquals(Collections.singleton("com.example.focus"), settings.islandForcePackages);
            assertEquals("Reading preferences must not migrate or rewrite values", before, values);
        }
    }

    @Test
    public void bannerSaveRoundTripsThroughBothStoresAndProviderRow() {
        Map<String, Object> credentialValues = legacyPreferences();
        Map<String, Object> deviceValues = legacyPreferences();
        SharedPreferences credential = memoryPreferences(credentialValues);
        SharedPreferences device = memoryPreferences(deviceValues);
        long generation = 40L;
        // Save on then off into the same stores: disabling must overwrite an existing true value.
        for (boolean enabled : new boolean[]{true, false}) {
            Map<String, Object> input = legacyPreferences();
            input.put("independent_focus_banner", enabled);
            FocusRestoreSettings settings = FocusRestoreSettings.fromPreferences(
                    memoryPreferences(input));
            generation++;
            assertTrue(settings.save(credential, generation));
            assertTrue(settings.save(device, generation));
            assertEquals(enabled, credentialValues.get("independent_focus_banner"));
            assertEquals(enabled, deviceValues.get("independent_focus_banner"));
            assertEquals(Boolean.FALSE, credentialValues.get("expand_island_on_click"));
            assertEquals(Boolean.FALSE, deviceValues.get("expand_island_on_click"));
            for (SharedPreferences store : new SharedPreferences[]{credential, device}) {
                assertTrue(FocusRestoreSettings.hasHookSettings(store));
                assertEquals(generation, FocusRestoreSettings.generation(store));
                FocusRestoreSettings restored = FocusRestoreSettings.fromPreferences(store);
                assertEquals(enabled, restored.independentFocusBanner);
                Object[] providerRow = legacyProviderRow(restored,
                        store.getString("island_separator", null));
                assertEquals(0, providerRow[18]);
                assertEquals(enabled ? 1 : 0, providerRow[21]);
                HookSettings hook = HookSettings.fromCursor(positionedCursor(providerRow));
                assertEquals(enabled, hook.independentFocusBanner);
                assertTrue(hook.allowFocusClick);
                assertTrue(hook.notificationRowClickFallback);
                assertEquals(244, hook.widthDp);
                assertEquals("general", hook.generalSeparator);
                assertEquals("side", hook.sideSeparator);
                assertEquals(Collections.singleton("com.example.focus"), hook.islandForcePackages);
            }
        }
    }

    @Test
    public void legacyShortCursorsDefaultBannerOff() {
        Object[] legacyRow = legacyWireRow();
        for (int count : new int[]{3, 7, 19, 20, 21}) {
            HookSettings settings = HookSettings.fromCursor(positionedCursor(
                    Arrays.copyOf(legacyRow, count)));
            assertFalse("Legacy column count " + count, settings.independentFocusBanner);
            assertEquals(244, settings.widthDp);
            assertEquals(1700, settings.marqueeDelayMs);
            assertEquals(count > 6, settings.allowFocusClick);
            assertEquals(count > 20, settings.notificationRowClickFallback);
        }
    }

    @Test
    public void bannerColumnReadsTrueFalseAndNullWithoutChangingLegacyClicks() {
        for (Object value : new Object[]{1, 0, null}) {
            Object[] row = Arrays.copyOf(legacyWireRow(), 22);
            row[18] = 1; // Even a stale retired column must not opt in to the new banner.
            row[21] = value;
            HookSettings settings = HookSettings.fromCursor(positionedCursor(row));
            assertEquals(Integer.valueOf(1).equals(value), settings.independentFocusBanner);
            assertTrue(settings.allowFocusClick);
            assertTrue(settings.notificationRowClickFallback);
            assertEquals("general", settings.generalSeparator);
        }
    }

    @Test
    public void providerKeepsLegacyValuesAndRetiredColumnWhenBannerChanges() {
        for (boolean enabled : new boolean[]{false, true}) {
            Map<String, Object> values = legacyPreferences();
            values.put("independent_focus_banner", enabled);
            FocusRestoreSettings settings = FocusRestoreSettings.fromPreferences(
                    memoryPreferences(values));
            Object[] row = legacyProviderRow(settings, "legacy");
            assertEquals(SettingsContractTest.LEGACY_COLUMNS.length, row.length);
            assertArrayEquals(legacyWireRow(), Arrays.copyOf(row, 21));
            assertEquals(enabled ? 1 : 0, row[21]);
        }
    }

    @Test
    public void islandTextModeAndFocusDisplayLimitSurviveSaveReloadAndProvider() {
        FocusRestoreSettings saved = FocusRestoreSettings.edit()
                .hookMode(FocusRestoreSettings.HOOK_MODE_OS4)
                .marqueeBounce(true)
                .islandCompat(true)
                .islandTextMode(FocusRestoreSettings.ISLAND_TEXT_MODE_COMPACT)
                .focusMaxDisplaySeconds(7.25f)
                .islandGeneralSeparator("·")
                .islandSideSeparator("·")
                .build();
        Map<String, Object> values = legacyPreferences();
        assertEquals(7.25f, saved.focusMaxDisplaySeconds, 0.0001f);

        assertTrue(saved.save(memoryPreferences(values), 12L));

        // Same values must come back from the app store after a restart.
        FocusRestoreSettings reloaded = FocusRestoreSettings.fromPreferences(
                memoryPreferences(values));
        assertEquals(FocusRestoreSettings.ISLAND_TEXT_MODE_COMPACT, reloaded.islandTextMode);
        assertEquals(7.25f, reloaded.focusMaxDisplaySeconds, 0.0001f);

        // ...and reach SystemUI through the provider wire format.
        Object[] row = legacyProviderRow(reloaded, "·");
        assertEquals(SettingsContractTest.LEGACY_COLUMNS.length, row.length);
        HookSettings hook = HookSettings.fromCursor(positionedCursor(row));
        assertEquals(FocusRestoreSettings.ISLAND_TEXT_MODE_COMPACT, hook.islandTextMode);
        assertEquals(7.25f, hook.focusMaxDisplaySeconds, 0.0001f);
    }

    @Test
    public void focusDisplayLimitParsesTextAndNormalizesRange() {
        assertEquals(0f, FocusRestoreSettings.parseMaxDisplaySeconds("", 3f), 0.0001f);
        assertEquals(0f, FocusRestoreSettings.parseMaxDisplaySeconds("0", 3f), 0.0001f);
        // The supported range is 2 - 3600 seconds (up to 60 minutes), so sub-second input is raised
        // to the minimum rather than being kept.
        assertEquals(2f, FocusRestoreSettings.MIN_FOCUS_MAX_DISPLAY_SECONDS, 0.0001f);
        assertEquals(3600f, FocusRestoreSettings.MAX_FOCUS_MAX_DISPLAY_SECONDS, 0.0001f);
        assertEquals(2f, FocusRestoreSettings.parseMaxDisplaySeconds("0.50", 3f), 0.0001f);
        assertEquals(12.34f, FocusRestoreSettings.parseMaxDisplaySeconds("12.34", 3f), 0.0001f);
        // Out-of-range and unparsable text must never silently disable or blow past the limit.
        assertEquals(FocusRestoreSettings.MIN_FOCUS_MAX_DISPLAY_SECONDS,
                FocusRestoreSettings.parseMaxDisplaySeconds("0.001", 3f), 0.0001f);
        assertEquals(FocusRestoreSettings.MAX_FOCUS_MAX_DISPLAY_SECONDS,
                FocusRestoreSettings.parseMaxDisplaySeconds("999999", 3f), 0.0001f);
        assertEquals(3f, FocusRestoreSettings.parseMaxDisplaySeconds("abc", 3f), 0.0001f);
        // A value written by the previous build as an int must still load as seconds.
        Map<String, Object> legacy = legacyPreferences();
        legacy.put("focus_max_display_seconds", 30);
        assertEquals(30f, FocusRestoreSettings.fromPreferences(memoryPreferences(legacy))
                .focusMaxDisplaySeconds, 0.0001f);
        assertEquals("7.25", FocusRestoreSettings.formatMaxDisplaySeconds(7.25f));
        assertEquals("0", FocusRestoreSettings.formatMaxDisplaySeconds(0f));
    }

    @Test
    public void staleActivitySnapshotNeverOverridesNewerStoredSettings() {
        // The island-conversion switch regression: a snapshot captured before the last save must
        // never be replayed over the store, otherwise the switch turns itself back off.
        assertTrue(FocusRestoreSettings.snapshotIsStale(7L, 8L));
        assertFalse(FocusRestoreSettings.snapshotIsStale(8L, 8L));
        assertFalse(FocusRestoreSettings.snapshotIsStale(9L, 8L));
        assertFalse(FocusRestoreSettings.snapshotIsStale(-1L, 8L));
        // A save interrupted by process death still has to be replayed.
        assertTrue(FocusRestoreSettings.snapshotNeedsReplay(9L, 8L));
        assertFalse(FocusRestoreSettings.snapshotNeedsReplay(8L, 8L));
        assertFalse(FocusRestoreSettings.snapshotNeedsReplay(7L, 8L));
        assertFalse(FocusRestoreSettings.snapshotNeedsReplay(-1L, 8L));
    }

    @Test
    public void editorSetsEveryFieldAndSurvivesTheStore() {
        FocusRestoreSettings all = FocusRestoreSettings.edit()
                .hookMode(FocusRestoreSettings.HOOK_MODE_OS4)
                .limitWidth(false)
                .widthDp(244)
                .marqueeDelayMs(1700)
                .compatRetry(true)
                .marqueeBounce(false)
                .islandCompat(true)
                .disableIslandProperty(false)
                .disableIslandFeatureCache(false)
                .allowFocusClick(true)
                .hideNotificationIcons(false)
                .showFocusDivider(false)
                .showIslandIcon(true)
                .tintIslandIcon(false)
                .useSmallIconFallback(true)
                .notificationRowClickFallback(true)
                .independentFocusBanner(true)
                .islandTextMode(FocusRestoreSettings.ISLAND_TEXT_MODE_COMPACT)
                .focusMaxDisplaySeconds(12.5f)
                .islandGeneralSeparator("general")
                .islandSideSeparator("side")
                .islandForcePackages(Collections.singleton("com.example.focus"))
                .focusTimeoutExemptPackages(Collections.singleton("com.example.travel"))
                .build();

        assertEquals(FocusRestoreSettings.HOOK_MODE_OS4, all.hookMode);
        assertFalse(all.limitWidth);
        assertEquals(244, all.widthDp);
        assertEquals(1700, all.marqueeDelayMs);
        assertTrue(all.compatRetry);
        assertFalse(all.marqueeBounce);
        assertTrue(all.islandCompat);
        assertFalse(all.disableIslandProperty);
        assertFalse(all.disableIslandFeatureCache);
        assertTrue(all.allowFocusClick);
        assertFalse(all.hideNotificationIcons);
        assertFalse(all.showFocusDivider);
        assertTrue(all.showIslandIcon);
        assertFalse(all.tintIslandIcon);
        assertTrue(all.useSmallIconFallback);
        assertTrue(all.notificationRowClickFallback);
        assertTrue(all.independentFocusBanner);
        assertEquals(FocusRestoreSettings.ISLAND_TEXT_MODE_COMPACT, all.islandTextMode);
        assertEquals(12.5f, all.focusMaxDisplaySeconds, 0.0001f);
        assertEquals("general", all.islandGeneralSeparator);
        assertEquals("side", all.islandSideSeparator);
        assertEquals(Collections.singleton("com.example.focus"), all.islandForcePackages);
        assertEquals(Collections.singleton("com.example.travel"), all.focusTimeoutExemptPackages);

        // SystemUI must see the very same values, including the fields added in 0.13.48.
        HookSettings hook = HookSettings.fromCursor(positionedCursor(
                legacyProviderRow(all, "general")));
        assertTrue(hook.islandCompat);
        assertEquals(FocusRestoreSettings.ISLAND_TEXT_MODE_COMPACT, hook.islandTextMode);
        assertEquals(12.5f, hook.focusMaxDisplaySeconds, 0.0001f);
        assertEquals(FocusRestoreSettings.HOOK_MODE_OS4, hook.hookMode);
        assertEquals("side", hook.sideSeparator);
        // The timeout exemption list has to survive the wire format as its own column, independent of
        // the focus whitelist.
        assertEquals(Collections.singleton("com.example.travel"), hook.focusTimeoutExemptPackages);
        assertEquals(Collections.singleton("com.example.focus"), hook.islandForcePackages);

        // defaults() and HookSettings.defaults() must not drift apart either.
        HookSettings hookDefaults = HookSettings.defaults();
        FocusRestoreSettings defaults = FocusRestoreSettings.defaults();
        assertEquals(defaults.islandCompat, hookDefaults.islandCompat);
        assertEquals(defaults.islandTextMode, hookDefaults.islandTextMode);
        assertEquals(defaults.focusMaxDisplaySeconds, hookDefaults.focusMaxDisplaySeconds, 0.0001f);
        assertEquals(defaults.showIslandIcon, hookDefaults.showIslandIcon);
        assertEquals(defaults.tintIslandIcon, hookDefaults.tintIslandIcon);
        assertEquals(defaults.focusTimeoutExemptPackages, hookDefaults.focusTimeoutExemptPackages);
    }

    /** Fixed pre-banner wire fixture, independent of current schema constants. */
    private static Object[] legacyWireRow() {
        return new Object[]{0, 244, 1700, 1, 1, "legacy", 1, "general", "side",
                "com.example.focus", 0, 0, 0, 4, 0, 0, 1, 0, 0, 1, 1};
    }

    private static Map<String, Object> legacyPreferences() {
        Map<String, Object> values = new HashMap<>();
        values.put("hook_mode", 4);
        values.put("limit_text_width", false);
        values.put("text_width_dp", 244);
        values.put("marquee_delay_ms", 1700);
        values.put("compat_retry", true);
        values.put("marquee_bounce", false);
        values.put("island_compat", true);
        values.put("disable_island_property", false);
        values.put("disable_island_feature_cache", false);
        values.put("allow_focus_click", true);
        values.put("hide_notification_icons", false);
        values.put("show_focus_divider", false);
        values.put("show_island_icon", true);
        values.put("tint_island_icon", false);
        values.put("expand_island_on_click", true);
        values.put("use_small_icon_fallback", true);
        values.put("notification_row_click_fallback", true);
        values.put("island_separator", "legacy");
        values.put("island_general_separator", "general");
        values.put("island_side_separator", "side");
        values.put("island_force_packages", Collections.singleton("com.example.focus"));
        return values;
    }

    private static Object[] legacyProviderRow(FocusRestoreSettings settings, String separator) {
        return SettingsContract.toRow(settings, separator, SettingsContractTest.LEGACY_COLUMNS);
    }

    /** Historical fixtures keep their original column names even when the current schema moves. */
    private static Cursor positionedCursor(Object[] row) {
        return SettingsContractTest.namedCursor(
                Arrays.copyOf(SettingsContractTest.LEGACY_COLUMNS, row.length), row);
    }

    private static SharedPreferences memoryPreferences(Map<String, Object> values) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getBoolean":
                        case "getInt":
                        case "getLong":
                        case "getFloat":
                        case "getString":
                        case "getStringSet":
                            return values.containsKey(args[0]) ? values.get(args[0]) : args[1];
                        case "getAll":
                            return new HashMap<>(values);
                        case "edit":
                            Map<String, Object> pending = new HashMap<>();
                            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                    new Class<?>[]{SharedPreferences.Editor.class}, (editor, operation, fields) -> {
                                        switch (operation.getName()) {
                                            case "putBoolean":
                                            case "putInt":
                                            case "putLong":
                                            case "putFloat":
                                            case "putString":
                                            case "putStringSet":
                                                pending.put((String) fields[0], fields[1]);
                                                return editor;
                                            case "commit":
                                                values.putAll(pending);
                                                return true;
                                            default: throw new AssertionError(
                                                    "Unexpected Editor call: " + operation.getName());
                                        }
                                    });
                        default: throw new AssertionError(
                                "Unexpected SharedPreferences call: " + method.getName());
                    }
                });
    }
}
