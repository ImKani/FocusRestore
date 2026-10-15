/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** Runtime codec tests; Android document UI and lifecycle still require device verification. */
public class SettingsBackupCodecTest {
    @Test
    public void allUserFieldsRoundTripWithNonDefaultValues() throws Exception {
        FocusRestoreSettings original = allSettings(false);
        FocusRestoreSettings decoded = SettingsBackupCodec.decode(SettingsBackupCodec.encode(original));
        assertEverySettingEquals(original, decoded);
        assertEverySettingEquals(allSettings(true), SettingsBackupCodec.decode(
                SettingsBackupCodec.encode(allSettings(true))));
    }

    @Test
    public void defaultsRoundTripAndMissingFieldsUseDefaults() throws Exception {
        FocusRestoreSettings defaults = FocusRestoreSettings.defaults();
        assertEverySettingEquals(defaults, SettingsBackupCodec.decode(SettingsBackupCodec.encode(defaults)));
        assertEverySettingEquals(defaults, decodeGroups("{}"));
        assertEverySettingEquals(defaults, decodeGroups("{\"focus\":{},\"interaction\":{}}"));
    }

    @Test
    public void outputIsGroupedReadableAndDeterministic() throws Exception {
        FocusRestoreSettings first = FocusRestoreSettings.edit(allSettings(false)).islandForcePackages(
                new HashSet<>(Arrays.asList("com.example.z", "com.example.a"))).build();
        FocusRestoreSettings second = FocusRestoreSettings.edit(first).islandForcePackages(
                new java.util.LinkedHashSet<>(Arrays.asList("com.example.a", "com.example.z"))).build();
        assertArrayEquals(SettingsBackupCodec.encode(first), SettingsBackupCodec.encode(second));
        String text = utf8(SettingsBackupCodec.encode(first));
        assertTrue(text.contains("\n  \"settings\": {\n    \"system\": {"));
        String[] groupOrder = {"system", "island", "focus", "media", "interaction", "icons", "marquee", "compatibility"};
        int previous = -1;
        for (String group : groupOrder) {
            int index = text.indexOf("\"" + group + "\": {");
            assertTrue(group + " order", index > previous);
            previous = index;
        }
        assertTrue(text.indexOf("com.example.a") < text.indexOf("com.example.z"));
        assertTrue(text.endsWith("\n"));
        JSONObject root = new JSONObject(text);
        assertEquals(SettingsBackupCodec.FORMAT, root.getString("format"));
        assertEquals(1, root.getInt("formatVersion"));
        int fields = 0;
        JSONObject groups = root.getJSONObject("settings");
        for (String group : groupOrder) fields += groups.getJSONObject(group).length();
        assertEquals(43, fields);
        for (String excluded : new String[]{"island_app_cache", "hook_settings_ready", "settings_generation",
                "island_separator", "expand_island_on_click", "notification_icon_hide_mode", "island_custom_rules",
                "focus_divider_start_dp", "focus_divider_end_dp"}) {
            assertFalse(excluded, text.contains("\"" + excluded + "\""));
        }
    }

    @Test
    public void exportedAppVersionMatchesBuildAndDoesNotGateSettingsImport() throws Exception {
        FocusRestoreSettings original = allSettings(false);
        JSONObject root = new JSONObject(utf8(SettingsBackupCodec.encode(original)));
        assertEquals(BuildConfig.VERSION_NAME + "(" + BuildConfig.VERSION_CODE + ")", root.getString("appVersion"));
        for (String version : new String[]{"0.30.4(262)", "99.0.0(9999)", "手动编辑"}) {
            root.put("appVersion", version);
            assertEverySettingEquals(original, SettingsBackupCodec.decode(root.toString().getBytes(StandardCharsets.UTF_8)));
        }
        // 来源说明不能放宽实际设置校验；非法内容仍在覆盖设置前拒绝。
        root.getJSONObject("settings").getJSONObject("system").put("hook_mode", 5);
        reject(root.toString());
    }

    @Test
    public void missingOrMalformedAppVersionIsRejected() throws Exception {
        JSONObject root = new JSONObject(backup("{}"));
        root.remove("appVersion");
        reject(root.toString());
        for (Object version : new Object[]{JSONObject.NULL, true, 266, new JSONObject(),
                new org.json.JSONArray(), "", "   ", "0.31.3\n(266)", repeat('v', 65)}) {
            root.put("appVersion", version);
            reject(root.toString());
        }
        root.put("appVersion", repeat('v', 64));
        assertEverySettingEquals(FocusRestoreSettings.defaults(),
                SettingsBackupCodec.decode(root.toString().getBytes(StandardCharsets.UTF_8)));
        reject(backup("{}").replace("\"appVersion\":\"0.31.3(266)\"",
                "\"appVersion\":\"0.31.3(266)\",\"appVersion\":\"edited\""));
    }

    @Test
    public void fileNameUsesLocalTimeAndStableDigitsAcrossLocales() {
        java.util.TimeZone previousZone = java.util.TimeZone.getDefault();
        java.util.Locale previousLocale = java.util.Locale.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("GMT+08:00"));
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar"));
            java.util.Calendar local = new java.util.GregorianCalendar();
            local.clear();
            local.set(2026, java.util.Calendar.OCTOBER, 11, 12, 41, 58);
            assertEquals("FocusRestore-2026-10-11-12_41_58.json",
                    SettingsBackupCodec.defaultFileName(local.getTimeInMillis()));
            assertEquals("FocusRestore-2026-10-11-12_41_59.json",
                    SettingsBackupCodec.defaultFileName(local.getTimeInMillis() + 1000));
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            assertEquals("FocusRestore-2026-10-11-04_41_58.json",
                    SettingsBackupCodec.defaultFileName(local.getTimeInMillis()));
        } finally {
            java.util.TimeZone.setDefault(previousZone);
            java.util.Locale.setDefault(previousLocale);
        }
    }

    @Test
    public void emptyAndEscapedUnicodeSeparatorsSurvive() throws Exception {
        FocusRestoreSettings original = FocusRestoreSettings.edit()
                .islandGeneralSeparator("").islandSideSeparator("引号\"\\\n\t😀").build();
        assertEverySettingEquals(original, SettingsBackupCodec.decode(SettingsBackupCodec.encode(original)));
        assertEquals("😀", decodeGroups("{\"island\":{\"island_general_separator\":\"\\ud83d\\ude00\"}}")
                .islandGeneralSeparator);
    }

    @Test
    public void oldLandscapeAndSeparatorFieldsMigrateIndependently() throws Exception {
        FocusRestoreSettings migrated = decodeGroups("{\"focus\":{\"text_width_dp\":244},"
                + "\"island\":{\"island_separator\":\"legacy\",\"island_general_separator\":\"\"}}");
        assertEquals(244, migrated.widthDp);
        assertEquals(244, migrated.widthLandscapeDp);
        assertEquals("", migrated.islandGeneralSeparator);
        assertEquals("legacy", migrated.islandSideSeparator);
        assertEquals(1500, decodeGroups("{\"focus\":{\"text_width_dp\":244,\"text_width_landscape_dp\":1500}}")
                .widthLandscapeDp);
        assertEquals(400, decodeGroups("{\"focus\":{\"text_width_dp\":900}}") .widthDp);
        assertEquals(900, decodeGroups("{\"focus\":{\"text_width_dp\":900}}") .widthLandscapeDp);
    }

    @Test
    public void swipeBackupMissingFieldStaysOffAndNonBooleanIsRejected() throws Exception {
        assertFalse(decodeGroups("{\"interaction\":{\"independent_focus_banner\":true}}").focusSwipeEnabled);
        assertTrue(decodeGroups("{\"interaction\":{\"focus_swipe_enabled\":true}}").focusSwipeEnabled);
        for (String value : new String[]{"1", "\"true\"", "null", "[]"}) {
            try {
                decodeGroups("{\"interaction\":{\"focus_swipe_enabled\":" + value + "}}");
                fail("non-boolean swipe setting must be rejected: " + value);
            } catch (IOException expected) { }
        }
    }

    @Test
    public void customTouchBackupDefaultsDoNotInheritDisplayWidthOrPortraitWidth() throws Exception {
        FocusRestoreSettings old = decodeGroups("{\"focus\":{\"text_width_dp\":244,\"text_width_landscape_dp\":1200}}");
        assertFalse(old.customTouchWidth);
        assertEquals(160, old.touchWidthDp);
        assertEquals(600, old.touchWidthLandscapeDp);
        FocusRestoreSettings portraitOnly = decodeGroups("{\"interaction\":{\"custom_touch_width\":true,\"touch_width_dp\":240}}");
        assertTrue(portraitOnly.customTouchWidth);
        assertEquals(240, portraitOnly.touchWidthDp);
        assertEquals(600, portraitOnly.touchWidthLandscapeDp);
        assertEquals(160, portraitOnly.widthDp);
        FocusRestoreSettings bounded = decodeGroups("{\"interaction\":{\"touch_width_dp\":900,\"touch_width_landscape_dp\":2000}}");
        assertFalse(bounded.customTouchWidth);
        assertEquals(400, bounded.touchWidthDp);
        assertEquals(1600, bounded.touchWidthLandscapeDp);
    }

    @Test
    public void customTouchBackupRejectsWrongBooleanAndWidthTypes() throws Exception {
        for (String value : new String[]{"1", "\"true\"", "null", "[]"}) {
            rejectGroups("{\"interaction\":{\"custom_touch_width\":" + value + "}}");
        }
        for (String key : new String[]{"touch_width_dp", "touch_width_landscape_dp"}) {
            for (String value : new String[]{"1.5", "\"160\"", "true", "null", "2147483648"}) {
                rejectGroups("{\"interaction\":{\"" + key + "\":" + value + "}}");
            }
        }
    }

    @Test
    public void durationNumbersAndLegacyNumericStringsUseExistingNormalization() throws Exception {
        for (String seconds : new String[]{"2", "2.75", "\" 2.75 \"", "\"2e0\""}) {
            FocusRestoreSettings decoded = decodeGroups("{\"focus\":{\"focus_max_display_seconds\":" + seconds
                    + "},\"interaction\":{\"long_press_seconds\":" + seconds + "}}");
            float expected = seconds.contains("2.75") ? 2.75f : 2f;
            assertEquals(expected, decoded.focusMaxDisplaySeconds, 0f);
            assertEquals(expected, decoded.longPressSeconds, 0f);
        }
        FocusRestoreSettings bounded = decodeGroups("{\"focus\":{\"focus_max_display_seconds\":0.01},"
                + "\"interaction\":{\"long_press_seconds\":100}}");
        assertEquals(2f, bounded.focusMaxDisplaySeconds, 0f);
        assertEquals(10f, bounded.longPressSeconds, 0f);
        assertEquals(0f, decodeGroups("{\"focus\":{\"focus_max_display_seconds\":-2}}").focusMaxDisplaySeconds, 0f);
        assertFalse(decodeGroups("{\"interaction\":{\"allow_focus_click\":true}}") .longPressNotificationRowClick);
    }

    @Test
    public void integerAndEnumValuesAreValidated() throws Exception {
        for (String group : new String[]{
                "{\"system\":{\"hook_mode\":5}}",
                "{\"media\":{\"media_focus_cast_picker\":3}}",
                "{\"island\":{\"island_text_mode\":2}}",
                "{\"focus\":{\"text_width_dp\":240.5}}",
                "{\"focus\":{\"text_width_dp\":240.00000000000000000001}}",
                "{\"focus\":{\"text_width_dp\":2.4e2}}",
                "{\"focus\":{\"text_width_dp\":2147483648}}",
                "{\"focus\":{\"text_width_dp\":-2147483649}}",
                "{\"focus\":{\"text_width_dp\":\"244\"}}"}) rejectGroups(group);
    }

    @Test
    public void badNumericValuesNeverSilentlyBecomeDefaults() throws Exception {
        for (String number : new String[]{"\"bad\"", "\"\"", "\"NaN\"", "\"Infinity\"", "\"-Infinity\"",
                "\"0x1.0p0\"", "\"2f\"", "1e400", "\"1e400\"", "null", "true", "[]", "{}"}) {
            rejectGroups("{\"focus\":{\"focus_max_display_seconds\":" + number + "}}");
            rejectGroups("{\"interaction\":{\"long_press_seconds\":" + number + "}}");
        }
    }

    @Test
    public void unknownFormatsVersionsAndRuntimeFieldsAreRejected() throws Exception {
        for (String text : new String[]{"{}", "[]", "null", "42",
                "{\"format\":\"other\",\"formatVersion\":1,\"settings\":{}}",
                "{\"format\":\"FocusRestore.settings\",\"formatVersion\":2,\"settings\":{}}",
                "{\"format\":\"FocusRestore.settings\",\"formatVersion\":\"1\",\"settings\":{}}",
                "{\"format\":\"FocusRestore.settings\",\"settings\":{}}",
                "{\"format\":\"FocusRestore.settings\",\"formatVersion\":1}",
                "{\"format\":\"FocusRestore.settings\",\"formatVersion\":1,\"settings\":{},\"extra\":true}"}) reject(text);
        rejectGroups("{\"future_group\":{}}");
        rejectGroups("{\"system\":null}");
        rejectGroups("{\"system\":[]}");
        for (String field : new String[]{"settings_generation", "hook_settings_ready", "island_app_cache",
                "island_custom_rules", "expand_island_on_click", "notification_icon_hide_mode", "unknown"}) {
            rejectGroups("{\"island\":{\"" + field + "\":true}}");
        }
    }

    @Test
    public void booleansAndTextDoNotCoerceTypesOrNulls() throws Exception {
        for (String value : new String[]{"null", "1", "\"true\"", "[]", "{}"}) {
            rejectGroups("{\"focus\":{\"limit_text_width\":" + value + "}}");
        }
        for (String value : new String[]{"null", "1", "true", "[]", "{}"}) {
            rejectGroups("{\"island\":{\"island_general_separator\":" + value + "}}");
        }
        rejectGroups("{\"island\":{\"island_general_separator\":\"" + repeat('x', 33) + "\"}}");
        assertEquals(32, decodeGroups("{\"island\":{\"island_general_separator\":\""
                + repeat('x', 32) + "\"}}").islandGeneralSeparator.length());
    }

    @Test
    public void strictSyntaxRejectsLenientJsonExtensionsAndTrailingContent() throws Exception {
        String good = backup("{}");
        for (String text : new String[]{good + " {}", good + " junk", good.replace("\"format\"", "format"),
                good.replace("\"format\"", "'format'"), good.replace("\"format\":", "\"format\"="),
                good.replace("\"settings\":{}", "\"settings\":{/*comment*/}"),
                good.replace("\"settings\":{}", "\"settings\":{,}"), good.replace("\"settings\":{}", "\"settings\":{},"),
                good.replace("\"settings\":{}", "\"settings\":{\"system\":{\"hook_mode\":+3}}"),
                good.replace("\"settings\":{}", "\"settings\":{\"system\":{\"hook_mode\":03}}"),
                good.replace("\"settings\":{}", "\"settings\":{\"focus\":{\"focus_max_display_seconds\":.5}}"),
                good.replace("\"settings\":{}", "\"settings\":{\"focus\":{\"focus_max_display_seconds\":5.}}")}) reject(text);
        rejectGroups("{\"island\":{\"island_force_packages\":[\"com.example\",]}}");
        rejectGroups("{\"island\":{\"island_force_packages\":[,]}}");
        rejectGroups("{\"focus\":{\"focus_max_display_seconds\":NaN}}");
        rejectGroups("{\"focus\":{\"focus_max_display_seconds\":Infinity}}");
    }

    @Test
    public void duplicateKeysIncludingEscapedAliasesAreRejectedAtEveryLevel() throws Exception {
        reject(backup("{}").replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"));
        rejectGroups("{\"system\":{},\"system\":{}}");
        rejectGroups("{\"system\":{\"hook_mode\":3,\"hook_mode\":4}}");
        rejectGroups("{\"system\":{\"hook_mode\":3,\"hook\\u005fmode\":4}}");
    }

    @Test
    public void malformedEscapesControlsAndSurrogatesAreRejected() throws Exception {
        for (String separator : new String[]{"\\q", "\\u12", "\\uZZZZ", "\\u００００", "\\ud800",
                "\\udc00", "\\ud800x", "a\nb", "a\tb"}) {
            rejectGroups("{\"island\":{\"island_general_separator\":\"" + separator + "\"}}");
        }
        reject(backup("{}").substring(0, backup("{}").length() - 1));
        rejectBytes(new byte[]{(byte) 0xc3, (byte) 0x28});
        rejectBytes(new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80});
        reject("\ufeff" + backup("{}"));
        try {
            SettingsBackupCodec.encode(FocusRestoreSettings.edit().islandGeneralSeparator("\ud800").build());
            fail("isolated surrogate must not be exported as replacement text");
        } catch (SettingsBackupCodec.InvalidBackupException expected) { }
    }

    @Test
    public void packageSetsAreTrimmedSortedDeduplicatedAndKeptIndependent() throws Exception {
        FocusRestoreSettings decoded = decodeGroups("{\"island\":{\"island_force_packages\":[\"com.z\",\" com.a \",\"com.z\"]},"
                + "\"focus\":{\"focus_timeout_exempt_packages\":[\"com.travel\"]}}");
        assertEquals(Arrays.asList("com.a", "com.z"), new java.util.ArrayList<>(decoded.islandForcePackages));
        assertEquals(Collections.singleton("com.travel"), decoded.focusTimeoutExemptPackages);
        for (String invalid : new String[]{"null", "\"com.app\"", "{}", "[null]", "[true]", "[1]", "[{}]",
                "[\"\"]", "[\"   \"]", "[\"com.a\\u001fcom.b\"]", "[\"com.a\\n\"]", "[\"com.a\\u007f\"]",
                "[\"" + repeat('x', 256) + "\"]"}) {
            rejectGroups("{\"island\":{\"island_force_packages\":" + invalid + "}}");
            rejectGroups("{\"focus\":{\"focus_timeout_exempt_packages\":" + invalid + "}}");
        }
        assertEquals(255, decodeGroups("{\"island\":{\"island_force_packages\":[\""
                + repeat('x', 255) + "\"]}}").islandForcePackages.iterator().next().length());
        try {
            SettingsBackupCodec.encode(FocusRestoreSettings.edit()
                    .islandForcePackages(Collections.singleton("com.a\u001fcom.b")).build());
            fail("wire separator must not be exported as package content");
        } catch (SettingsBackupCodec.InvalidBackupException expected) { }
    }

    @Test
    public void combinedPackageBudgetProtectsSavedActivityStateOnImportAndExport() throws Exception {
        Set<String> force = new HashSet<>();
        for (int index = 0; index < 256; index++) {
            String suffix = Integer.toString(index);
            force.add(repeat('x', 255 - suffix.length()) + suffix);
        }
        // 256 * 255 + 256 = 65536，跨两个独立集合恰好达到总预算。
        FocusRestoreSettings boundary = FocusRestoreSettings.edit().islandForcePackages(force)
                .focusTimeoutExemptPackages(new HashSet<>(Arrays.asList(repeat('a', 255), "b"))).build();
        assertEverySettingEquals(boundary, SettingsBackupCodec.decode(SettingsBackupCodec.encode(boundary)));
        FocusRestoreSettings oversized = FocusRestoreSettings.edit(boundary)
                .focusTimeoutExemptPackages(new HashSet<>(Arrays.asList(repeat('a', 255), "bc"))).build();
        try { SettingsBackupCodec.encode(oversized); fail("oversized saved state exported"); }
        catch (SettingsBackupCodec.InvalidBackupException expected) { assertTrue(expected.getMessage().contains("65536")); }
        JSONObject root = new JSONObject(utf8(SettingsBackupCodec.encode(boundary)));
        root.getJSONObject("settings").getJSONObject("focus").put("focus_timeout_exempt_packages",
                new org.json.JSONArray(Arrays.asList(repeat('a', 255), "bc")));
        reject(root.toString());
    }

    @Test
    public void arraysAndObjectDepthHaveBoundedQuotas() throws Exception {
        StringBuilder packages = new StringBuilder("[");
        for (int index = 0; index < InputLimits.MAX_FORCE_PACKAGES; index++) {
            if (index > 0) packages.append(',');
            packages.append('"').append("com.p").append(index).append('"');
        }
        packages.append(']');
        assertEquals(InputLimits.MAX_FORCE_PACKAGES,
                decodeGroups("{\"island\":{\"island_force_packages\":" + packages + "}}").islandForcePackages.size());
        packages.insert(packages.length() - 1, ",\"com.extra\"");
        rejectGroups("{\"island\":{\"island_force_packages\":" + packages + "}}");
        rejectGroups("{\"system\":{\"hook_mode\":[[[[[[3]]]]]]}}");
        rejectGroups("{\"focus\":{\"focus_max_display_seconds\":" + repeat('1', 129) + "}}");
        StringBuilder members = new StringBuilder("{");
        for (int index = 0; index < 65; index++) {
            if (index > 0) members.append(',');
            members.append('"').append('k').append(index).append("\":0");
        }
        members.append('}');
        rejectGroups(members.toString());
    }

    @Test
    public void exactFileByteLimitIsAcceptedAndLargerInputRejected() throws Exception {
        byte[] padded = new byte[SettingsBackupCodec.MAX_FILE_BYTES];
        Arrays.fill(padded, (byte) ' ');
        byte[] header = backup("{}").getBytes(StandardCharsets.UTF_8);
        System.arraycopy(header, 0, padded, 0, header.length);
        assertEverySettingEquals(FocusRestoreSettings.defaults(), SettingsBackupCodec.decode(padded));
        rejectBytes(new byte[SettingsBackupCodec.MAX_FILE_BYTES + 1]);
        rejectBytes(new byte[0]);
        rejectBytes(null);
    }

    @Test
    public void streamReadEnforcesLimitsAndPropagatesIoFailureWithoutClosingCallerStream() throws Exception {
        final boolean[] closed = {false};
        InputStream stream = new ByteArrayInputStream(backup("{}").getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() { closed[0] = true; }
        };
        assertEverySettingEquals(FocusRestoreSettings.defaults(), SettingsBackupCodec.read(stream));
        assertFalse("stream ownership belongs to SAF caller", closed[0]);
        InputStream huge = new InputStream() {
            private int left = SettingsBackupCodec.MAX_FILE_BYTES + 1;
            @Override public int read() { return left-- > 0 ? ' ' : -1; }
            @Override public int read(byte[] bytes, int offset, int count) {
                if (left == 0) return -1;
                int available = Math.min(left, count);
                Arrays.fill(bytes, offset, offset + available, (byte) ' ');
                left -= available;
                return available;
            }
        };
        try { SettingsBackupCodec.read(huge); fail("oversized stream accepted"); }
        catch (SettingsBackupCodec.InvalidBackupException expected) { }
        try {
            SettingsBackupCodec.read(new InputStream() {
                @Override public int read() throws IOException { throw new IOException("provider failure"); }
            });
            fail("IO failure must propagate");
        } catch (IOException expected) { assertEquals("provider failure", expected.getMessage()); }
        try { SettingsBackupCodec.read(null); fail("null stream accepted"); }
        catch (SettingsBackupCodec.InvalidBackupException expected) { }
    }

    @Test
    public void cancellationIsCheckedBeforeParsingAndEncoding() throws Exception {
        Thread.currentThread().interrupt();
        try {
            try { SettingsBackupCodec.decode(backup("{}").getBytes(StandardCharsets.UTF_8)); fail("decode ignored cancellation"); }
            catch (InterruptedIOException expected) { }
            try { SettingsBackupCodec.encode(FocusRestoreSettings.defaults()); fail("encode ignored cancellation"); }
            catch (InterruptedIOException expected) { }
            try { SettingsBackupCodec.read(new ByteArrayInputStream(new byte[0])); fail("read ignored cancellation"); }
            catch (InterruptedIOException expected) { }
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void failedDecodeCannotMutateAnExistingSnapshot() throws Exception {
        FocusRestoreSettings existing = allSettings(false);
        byte[] before = SettingsBackupCodec.encode(existing);
        rejectGroups("{\"focus\":{\"text_width_dp\":244},\"interaction\":{\"long_press_seconds\":\"bad\"}}");
        assertArrayEquals(before, SettingsBackupCodec.encode(existing));
        assertTrue("defaults for a separate import must not inherit current settings", decodeGroups("{}") .islandCompat);
    }

    @Test
    public void appearanceImportRejectsGapsBoundsAndWrongTypes() throws Exception {
        for (String invalid : new String[]{"-1", "33", "1.0", "true", "\"8\""}) {
            rejectGroups("{\"icons\":{\"focus_icon_size_dp\":" + invalid + "}}");
        }
        for (String key : new String[]{"focus_divider_start_dp", "focus_divider_end_dp"}) {
            for (String invalid : new String[]{"-2", "25", "0.5", "false", "\"0\""}) {
                rejectGroups("{\"focus\":{\"" + key + "\":" + invalid + "}}");
            }
        }
        for (String key : new String[]{"focus_divider_start_offset_dp", "focus_divider_end_offset_dp"}) {
            for (String invalid : new String[]{"-26", "25", "0.5", "1.0", "false", "\"0\"", "null", "[]", "{}"}) {
                rejectGroups("{\"focus\":{\"" + key + "\":" + invalid + "}}");
            }
        }
        for (String invalid : new String[]{"-1", "5", "1.5", "true", "\"1\""}) {
            rejectGroups("{\"focus\":{\"focus_divider_width_dp\":" + invalid + "}}");
        }
        for (String invalid : new String[]{"-1", "4", "1.5", "null", "\"1\""}) {
            rejectGroups("{\"focus\":{\"focus_divider_style\":" + invalid + "}}");
        }
        FocusRestoreSettings automatic = decodeGroups("{\"icons\":{\"focus_icon_size_dp\":0},\"focus\":{\"focus_divider_start_dp\":-1,\"focus_divider_end_dp\":-1,\"focus_divider_width_dp\":0,\"focus_divider_style\":0}}");
        assertEverySettingEquals(FocusRestoreSettings.edit().focusIconSizeDp(0).build(), automatic);
        for (int size : new int[]{1, 7, 8, 32}) {
            assertEquals(size, decodeGroups("{\"icons\":{\"focus_icon_size_dp\":" + size + "}}").focusIconSizeDp);
        }
    }

    @Test
    public void iconTextGapBackupIsIndependentAndMissingFieldFollowsSystem() throws Exception {
        FocusRestoreSettings old = decodeGroups("{\"icons\":{\"focus_icon_size_dp\":8}}");
        assertEquals(8, old.focusIconSizeDp);
        assertEquals(-1, old.focusIconTextGapDp);
        for (int gap : new int[]{-1, 0, 1, 24}) {
            FocusRestoreSettings settings = FocusRestoreSettings.edit().focusIconSizeDp(8)
                    .focusIconTextGapDp(gap).focusDividerEndDp(12).build();
            JSONObject icons = new JSONObject(utf8(SettingsBackupCodec.encode(settings)))
                    .getJSONObject("settings").getJSONObject("icons");
            assertEquals(gap, icons.getInt("focus_icon_text_gap_dp"));
            assertEverySettingEquals(settings, SettingsBackupCodec.decode(SettingsBackupCodec.encode(settings)));
            assertEquals(gap, decodeGroups("{\"icons\":{\"focus_icon_text_gap_dp\":" + gap + "}}")
                    .focusIconTextGapDp);
        }
        for (String invalid : new String[]{"-2", "25", "1.0", "1e0", "true", "\"3\"", "null", "[]", "{}"}) {
            rejectGroups("{\"icons\":{\"focus_icon_text_gap_dp\":" + invalid + "}}");
        }
    }

    @Test
    public void dividerBackupExportsOnlyOffsetsAndRoundTripsActualNegativeOne() throws Exception {
        for (int offset : new int[]{-25, -24, -1, 0, 24}) {
            FocusRestoreSettings settings = FocusRestoreSettings.edit()
                    .focusDividerStartDp(offset).focusDividerEndDp(offset).build();
            byte[] encoded = SettingsBackupCodec.encode(settings);
            JSONObject focus = new JSONObject(utf8(encoded)).getJSONObject("settings").getJSONObject("focus");
            assertEquals(offset, focus.getInt("focus_divider_start_offset_dp"));
            assertEquals(offset, focus.getInt("focus_divider_end_offset_dp"));
            assertFalse(focus.has("focus_divider_start_dp"));
            assertFalse(focus.has("focus_divider_end_dp"));
            assertEverySettingEquals(settings, SettingsBackupCodec.decode(encoded));
            FocusRestoreSettings imported = decodeGroups("{\"focus\":{\"focus_divider_start_offset_dp\":"
                    + offset + ",\"focus_divider_end_offset_dp\":" + offset + "}}");
            assertEquals(offset, imported.focusDividerStartDp);
            assertEquals(offset, imported.focusDividerEndDp);
        }
    }

    @Test
    public void dividerBackupMigratesOldValuesAndNewOffsetsWinRegardlessOfKeyOrder() throws Exception {
        for (int old : new int[]{-1, 0, 24}) {
            FocusRestoreSettings migrated = decodeGroups("{\"focus\":{\"focus_divider_start_dp\":"
                    + old + ",\"focus_divider_end_dp\":" + old + "}}");
            assertEquals(old == -1 ? -25 : old, migrated.focusDividerStartDp);
            assertEquals(old == -1 ? -25 : old, migrated.focusDividerEndDp);
            assertEverySettingEquals(migrated, SettingsBackupCodec.decode(SettingsBackupCodec.encode(migrated)));
        }
        for (String fields : new String[]{
                "\"focus_divider_start_dp\":24,\"focus_divider_start_offset_dp\":-1,\"focus_divider_end_dp\":-1,\"focus_divider_end_offset_dp\":0",
                "\"focus_divider_end_offset_dp\":0,\"focus_divider_end_dp\":-1,\"focus_divider_start_offset_dp\":-1,\"focus_divider_start_dp\":24"}) {
            FocusRestoreSettings preferred = decodeGroups("{\"focus\":{" + fields + "}}");
            assertEquals(-1, preferred.focusDividerStartDp);
            assertEquals(0, preferred.focusDividerEndDp);
        }
        for (String side : new String[]{"start", "end"}) {
            String oldKey = "focus_divider_" + side + "_dp";
            String newKey = "focus_divider_" + side + "_offset_dp";
            // 新键优先只决定采用值，不能让另一份非法配置绕过完整校验。
            for (String invalid : new String[]{"-2", "25", "0.5", "1.0", "false", "\"0\"", "null", "[]", "{}"}) {
                rejectGroups("{\"focus\":{\"" + newKey + "\":-1,\"" + oldKey + "\":" + invalid + "}}");
                rejectGroups("{\"focus\":{\"" + oldKey + "\":" + invalid + ",\"" + newKey + "\":-1}}");
            }
            for (String invalid : new String[]{"-26", "25", "0.5", "1.0", "false", "\"0\"", "null", "[]", "{}"}) {
                rejectGroups("{\"focus\":{\"" + oldKey + "\":-1,\"" + newKey + "\":" + invalid + "}}");
            }
        }
    }

    private static FocusRestoreSettings allSettings(boolean direct) {
        return FocusRestoreSettings.edit().hookMode(4).limitWidth(false).widthDp(244).widthLandscapeDp(1200)
                .marqueeDelayMs(1700).specialBannerNormalBackground(false)
                .mediaFocusEnabled(true).mediaFocusNativeBanner(true).mediaFocusCastPicker(1).mediaFocusCastDirect(direct)
                .compatRetry(true).marqueeBounce(false).islandCompat(true)
                .disableIslandProperty(false).disableIslandFeatureCache(false).allowFocusClick(true)
                .hideNotificationIcons(false).showFocusDivider(false).showIslandIcon(true).tintIslandIcon(false)
                .focusIconSizeDp(23).focusIconTextGapDp(12).focusDividerStartDp(0).focusDividerEndDp(24)
                .focusDividerWidthDp(4).focusDividerStyle(3)
                .useSmallIconFallback(true).focusIconPriority(1).tintDeviceIcon(false)
                .notificationRowClickFallback(true).independentFocusBanner(true)
                .longPressNotificationRowClick(true).longPressSeconds(2.75f).focusSwipeEnabled(true).islandTextMode(1)
                .customTouchWidth(true).touchWidthDp(100).touchWidthLandscapeDp(720)
                .focusMaxDisplaySeconds(12.34f).islandGeneralSeparator("general").islandSideSeparator("side")
                .islandForcePackages(new HashSet<>(Arrays.asList("com.example.z", "com.example.a")))
                .focusTimeoutExemptPackages(Collections.singleton("com.example.travel")).build();
    }

    @Test public void oldIconBackupsMigrateAndNewPriorityRejectsInvalidValues() throws Exception {
        for (boolean small : new boolean[]{false, true}) {
            for (boolean island : new boolean[]{false, true}) {
                FocusRestoreSettings migrated = decodeGroups("{\"icons\":{\"use_small_icon_fallback\":"
                        + small + ",\"show_island_icon\":" + island + "}}");
                assertEquals(small ? 2 : island ? 1 : 0, migrated.focusIconPriority);
                assertTrue(migrated.tintDeviceIcon);
                assertEquals(island, migrated.showIslandIcon);
            }
        }
        FocusRestoreSettings explicit = decodeGroups("{\"icons\":{\"focus_icon_priority\":0,"
                + "\"use_small_icon_fallback\":true,\"show_island_icon\":true,\"tint_device_icon\":false}}");
        assertEquals(0, explicit.focusIconPriority);
        assertFalse(explicit.useSmallIconFallback);
        assertFalse(explicit.tintDeviceIcon);
        for (int priority = 0; priority < FocusRestoreSettings.ICON_ORDER_COUNT; priority++) {
            FocusRestoreSettings imported = decodeGroups("{\"icons\":{\"focus_icon_priority\":"
                    + priority + ",\"use_small_icon_fallback\":true}}");
            assertEquals(priority, imported.focusIconPriority);
            assertEquals(priority == 2 || priority == 5, imported.useSmallIconFallback);
        }
        for (String invalid : new String[]{"-1", "6", "2147483647", "1.5", "true", "\"1\""}) {
            rejectGroups("{\"icons\":{\"focus_icon_priority\":" + invalid + "}}");
        }
    }

    private static void assertEverySettingEquals(FocusRestoreSettings expected, FocusRestoreSettings actual) throws Exception {
        int count = 0;
        // 使用真实快照字段逐一比较；以后新增用户字段而漏加编解码会在此暴露。
        for (Field field : FocusRestoreSettings.class.getFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)) continue;
            assertEquals(field.getName(), field.get(expected), field.get(actual));
            count++;
        }
        assertEquals("current user snapshot field count", 43, count);
    }

    private static FocusRestoreSettings decodeGroups(String groups) throws IOException {
        return SettingsBackupCodec.decode(backup(groups).getBytes(StandardCharsets.UTF_8));
    }
    private static String backup(String groups) {
        return "{\"format\":\"FocusRestore.settings\",\"formatVersion\":1,\"appVersion\":\"0.31.3(266)\",\"settings\":" + groups + "}";
    }
    private static void rejectGroups(String groups) throws IOException { reject(backup(groups)); }
    private static void reject(String text) throws IOException { rejectBytes(text.getBytes(StandardCharsets.UTF_8)); }
    private static void rejectBytes(byte[] bytes) throws IOException {
        try { SettingsBackupCodec.decode(bytes); fail("invalid backup was accepted"); }
        catch (SettingsBackupCodec.InvalidBackupException expected) { assertNotNull(expected.getMessage()); }
    }
    private static String utf8(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    private static String repeat(char character, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, character);
        return new String(characters);
    }
}
