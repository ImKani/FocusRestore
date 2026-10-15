/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression coverage for malformed stored types and settings compatibility reads. */
public class FocusRestoreSettingsResilienceTest {
    @Test
    public void malformedFieldsFallBackIndividuallyAndDoNotHideValidSettings() {
        Map<String, Object> values = new HashMap<>();
        values.put(FocusRestoreSettings.KEY_HOOK_SETTINGS_READY, "true");
        values.put(FocusRestoreSettings.KEY_SETTINGS_GENERATION, 12);
        values.put(FocusRestoreSettings.KEY_HOOK_MODE, "4");
        values.put(FocusRestoreSettings.KEY_LIMIT_WIDTH, 0);
        values.put(FocusRestoreSettings.KEY_WIDTH_DP, "244");
        values.put(FocusRestoreSettings.KEY_WIDTH_LANDSCAPE_DP, 1200);
        values.put(FocusRestoreSettings.KEY_MEDIA_FOCUS_ENABLED, true);
        values.put(FocusRestoreSettings.KEY_FOCUS_ICON_SIZE_DP, 8);
        values.put(FocusRestoreSettings.KEY_FOCUS_ICON_TEXT_GAP_DP, "12");
        values.put(FocusRestoreSettings.KEY_LONG_PRESS_SECONDS, true);
        values.put(FocusRestoreSettings.KEY_FOCUS_MAX_DISPLAY_SECONDS,
                Collections.singleton("not a duration"));
        values.put(FocusRestoreSettings.KEY_ISLAND_SEPARATOR, "legacy");
        values.put(FocusRestoreSettings.KEY_ISLAND_GENERAL_SEPARATOR, 17);
        values.put(FocusRestoreSettings.KEY_ISLAND_FORCE_PACKAGES,
                Collections.<Object>singleton(17));
        values.put(FocusRestoreSettings.KEY_FOCUS_TIMEOUT_EXEMPT_PACKAGES,
                Collections.singleton("com.example.travel"));

        SharedPreferences preferences = snapshotPreferences(values);
        FocusRestoreSettings settings = FocusRestoreSettings.fromPreferences(preferences);

        assertEquals(FocusRestoreSettings.DEFAULT_HOOK_MODE, settings.hookMode);
        assertTrue(settings.limitWidth);
        assertEquals(FocusRestoreSettings.DEFAULT_WIDTH_DP, settings.widthDp);
        assertEquals(1200, settings.widthLandscapeDp);
        assertTrue("a valid field survives neighboring type errors", settings.mediaFocusEnabled);
        assertEquals("previously saved 8dp remains an explicit custom size", 8,
                settings.focusIconSizeDp);
        assertEquals(FocusRestoreSettings.DEFAULT_FOCUS_ICON_TEXT_GAP_DP,
                settings.focusIconTextGapDp);
        assertEquals(FocusRestoreSettings.DEFAULT_LONG_PRESS_SECONDS,
                settings.longPressSeconds, 0f);
        assertEquals(FocusRestoreSettings.DEFAULT_FOCUS_MAX_DISPLAY_SECONDS,
                settings.focusMaxDisplaySeconds, 0f);
        assertEquals("legacy", settings.islandGeneralSeparator);
        assertEquals("legacy", settings.islandSideSeparator);
        assertTrue(settings.islandForcePackages.isEmpty());
        assertEquals(Collections.singleton("com.example.travel"),
                settings.focusTimeoutExemptPackages);
        assertFalse(FocusRestoreSettings.hasHookSettings(preferences));
        assertEquals(0L, FocusRestoreSettings.generation(preferences));
    }

    @Test
    public void readinessGenerationAndLegacySeparatorKeepValidTypesAndClampRules() {
        Map<String, Object> values = new HashMap<>();
        values.put(FocusRestoreSettings.KEY_HOOK_SETTINGS_READY, true);
        values.put(FocusRestoreSettings.KEY_SETTINGS_GENERATION, 42L);
        values.put(FocusRestoreSettings.KEY_ISLAND_SEPARATOR, "legacy-value");
        SharedPreferences preferences = snapshotPreferences(values);

        assertTrue(FocusRestoreSettings.hasHookSettings(preferences));
        assertEquals(42L, FocusRestoreSettings.generation(preferences));
        assertEquals("legacy-value",
                FocusRestoreSettings.readLegacyIslandSeparator(preferences));

        values.put(FocusRestoreSettings.KEY_SETTINGS_GENERATION, -1L);
        assertEquals(0L, FocusRestoreSettings.generation(preferences));
        values.put(FocusRestoreSettings.KEY_ISLAND_SEPARATOR, 17);
        assertEquals(FocusRestoreSettings.DEFAULT_ISLAND_SEPARATOR,
                FocusRestoreSettings.readLegacyIslandSeparator(preferences));
    }

    @Test
    public void legacyNumberAndStringDurationRepresentationsRemainReadableWithoutMigration() {
        for (Object duration : new Object[]{2, 2f, " 2.75 "}) {
            Map<String, Object> values = new HashMap<>();
            values.put(FocusRestoreSettings.KEY_LONG_PRESS_SECONDS, duration);
            values.put(FocusRestoreSettings.KEY_FOCUS_MAX_DISPLAY_SECONDS, duration);
            Map<String, Object> before = new HashMap<>(values);

            FocusRestoreSettings settings = FocusRestoreSettings.fromPreferences(
                    snapshotPreferences(values));
            float expected = duration instanceof String ? 2.75f : 2f;
            assertEquals(expected, settings.longPressSeconds, 0f);
            assertEquals(expected, settings.focusMaxDisplaySeconds, 0f);
            assertEquals("reading compatible old types must not rewrite preferences",
                    before, values);
        }
    }

    @Test
    public void iconDefaultsAndIndependentGapRoundTripThroughSnapshotEditorAndPreferences() {
        FocusRestoreSettings defaults = FocusRestoreSettings.defaults();
        assertEquals(0, defaults.focusIconSizeDp);
        assertEquals(-1, defaults.focusIconTextGapDp);
        FocusRestoreSettings missingKeys = FocusRestoreSettings.fromPreferences(
                snapshotPreferences(new HashMap<>()));
        assertEquals(0, missingKeys.focusIconSizeDp);
        assertEquals(-1, missingKeys.focusIconTextGapDp);
        assertEquals(8, FocusRestoreSettings.edit().focusIconSizeDp(8).build().focusIconSizeDp);
        assertEquals(-1, FocusRestoreSettings.normalizeFocusIconTextGapDp(-2));
        assertEquals(-1, FocusRestoreSettings.normalizeFocusIconTextGapDp(-1));
        assertEquals(0, FocusRestoreSettings.normalizeFocusIconTextGapDp(0));
        assertEquals(24, FocusRestoreSettings.normalizeFocusIconTextGapDp(24));
        assertEquals(24, FocusRestoreSettings.normalizeFocusIconTextGapDp(25));

        FocusRestoreSettings configured = FocusRestoreSettings.edit()
                .focusIconSizeDp(8).focusIconTextGapDp(13).build();
        FocusRestoreSettings copied = FocusRestoreSettings.edit(configured).build();
        assertEquals(8, copied.focusIconSizeDp);
        assertEquals(13, copied.focusIconTextGapDp);
        assertTrue(configured.describe().contains("focusIconTextGapDp=13"));

        Map<String, Object> values = new HashMap<>();
        SharedPreferences preferences = writablePreferences(values);
        assertTrue(configured.save(preferences, 4L));
        assertEquals(13, values.get(FocusRestoreSettings.KEY_FOCUS_ICON_TEXT_GAP_DP));
        FocusRestoreSettings restored = FocusRestoreSettings.fromPreferences(preferences);
        assertEquals(8, restored.focusIconSizeDp);
        assertEquals(13, restored.focusIconTextGapDp);
    }

    @Test
    public void unreadableStorageIsNotTreatedAsAnEmptyConfiguration() {
        SharedPreferences broken = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> { throw new IllegalStateException("storage unavailable"); });
        try {
            FocusRestoreSettings.fromPreferences(broken);
            org.junit.Assert.fail("unreadable storage must not yield defaults");
        } catch (IllegalStateException expected) {
            assertEquals("storage unavailable", expected.getMessage());
        }
        try {
            FocusRestoreSettings.hasHookSettings(broken);
            org.junit.Assert.fail("unreadable storage must not yield ready=false");
        } catch (IllegalStateException expected) {
            assertEquals("storage unavailable", expected.getMessage());
        }
    }

    private static SharedPreferences snapshotPreferences(Map<String, Object> values) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if ("getAll".equals(method.getName())) return new HashMap<>(values);
                    if ("toString".equals(method.getName())) return "snapshotPreferences";
                    throw new AssertionError("Settings must use the type-safe snapshot reader, not "
                            + method.getName());
                });
    }

    private static SharedPreferences writablePreferences(Map<String, Object> values) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if ("getAll".equals(method.getName())) return new HashMap<>(values);
                    if ("edit".equals(method.getName())) {
                        Map<String, Object> pending = new HashMap<>();
                        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                new Class<?>[]{SharedPreferences.Editor.class},
                                (editor, operation, fields) -> {
                                    String name = operation.getName();
                                    if (name.startsWith("put") && fields != null
                                            && fields.length == 2) {
                                        pending.put((String) fields[0], fields[1]);
                                        return editor;
                                    }
                                    if ("commit".equals(name)) {
                                        values.putAll(pending);
                                        return true;
                                    }
                                    throw new AssertionError("Unexpected Editor call: " + name);
                                });
                    }
                    if ("toString".equals(method.getName())) return "writablePreferences";
                    throw new AssertionError("Unexpected SharedPreferences call: "
                            + method.getName());
                });
    }
}
