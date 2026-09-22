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
            FocusRestoreSettings settings = FocusRestoreSettings.withValues(
                    FocusRestoreSettings.HOOK_MODE_OS4, true, 160, 200,
                    false, true, false, true, true,
                    allowClick, true, true, true, true, false, rowFallback, banner,
                    "·", "·", Collections.<String>emptySet());
            assertEquals(allowClick, settings.allowFocusClick);
            assertEquals(rowFallback, settings.notificationRowClickFallback);
            assertEquals(banner, settings.independentFocusBanner);
            HookSettings hook = HookSettings.fromCursor(positionedCursor(
                    SettingsContract.toRow(settings, "·")));
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
                Object[] providerRow = SettingsContract.toRow(restored,
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
            Object[] row = SettingsContract.toRow(settings, "legacy");
            assertEquals(SettingsContract.COLUMNS.length, row.length);
            assertArrayEquals(legacyWireRow(), Arrays.copyOf(row, 21));
            assertEquals(enabled ? 1 : 0, row[21]);
        }
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

    /** Interface-only test doubles keep these tests runnable without an Android runtime. */
    private static Cursor positionedCursor(Object[] row) {
        return (Cursor) Proxy.newProxyInstance(Cursor.class.getClassLoader(),
                new Class<?>[]{Cursor.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getColumnCount": return row.length;
                        case "isNull": return row[(Integer) args[0]] == null;
                        case "getInt": return ((Number) row[(Integer) args[0]]).intValue();
                        case "getString": return (String) row[(Integer) args[0]];
                        default: throw new AssertionError("Unexpected Cursor call: " + method.getName());
                    }
                });
    }

    private static SharedPreferences memoryPreferences(Map<String, Object> values) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getBoolean":
                        case "getInt":
                        case "getLong":
                        case "getString":
                        case "getStringSet":
                            return values.containsKey(args[0]) ? values.get(args[0]) : args[1];
                        case "edit":
                            Map<String, Object> pending = new HashMap<>();
                            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                    new Class<?>[]{SharedPreferences.Editor.class}, (editor, operation, fields) -> {
                                        switch (operation.getName()) {
                                            case "putBoolean":
                                            case "putInt":
                                            case "putLong":
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
