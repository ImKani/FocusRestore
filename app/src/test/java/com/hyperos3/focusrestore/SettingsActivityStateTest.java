/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Source-level guard for the settings screen state plumbing.
 *
 * <p>Every {@code pending*} field has to travel three paths: it must be loaded from the store, be
 * written into the saved activity state, and be read back from it. Dropping one of those steps is
 * invisible to the compiler and once shipped a build in which the island conversion switch looked
 * disabled after every restart, so all three paths are asserted here.
 */
public class SettingsActivityStateTest {
    private static final Pattern PENDING_FIELD = Pattern.compile("\\bpending[A-Z]\\w*\\b");
    private static final Pattern BUNDLE_PUT =
            Pattern.compile("outState\\.put\\w+\\(\"(m3\\.[^\"]+)\"");
    private static final Pattern BUNDLE_GET =
            Pattern.compile("state\\.get\\w+\\(\"(m3\\.[^\"]+)\"");
    /** Only the field declaration block is scanned; local variables are not settings. */
    private static final String FIELD_BLOCK_START = "private boolean pendingManual";
    private static final String FIELD_BLOCK_END = "private Set<String> pendingTimeoutExemptPackages";

    @Test
    public void everyPendingSettingIsLoadedSavedAndRestored() throws Exception {
        String source = readSettingsActivitySource();

        int start = source.indexOf(FIELD_BLOCK_START);
        int end = source.indexOf(FIELD_BLOCK_END);
        assertTrue("pending field declaration block must exist", start >= 0 && end > start);
        String fieldBlock = source.substring(start, source.indexOf(';', end) + 1);

        Set<String> fields = new TreeSet<>();
        Matcher fieldMatcher = PENDING_FIELD.matcher(fieldBlock);
        while (fieldMatcher.find()) fields.add(fieldMatcher.group());
        assertTrue("settings screen should declare pending fields", fields.size() > 10);

        for (String field : fields) {
            assertTrue(field + " is never loaded from the stored settings",
                    Pattern.compile("\\b" + field + " = [^;]*settings\\.").matcher(source).find());
        }

        Set<String> written = keys(BUNDLE_PUT, source);
        Set<String> restored = keys(BUNDLE_GET, source);
        // 旧间距key只读取迁移，不再写入；其余所有字段仍必须严格三路径一致。
        restored.remove("m3.focusDividerStartDp");
        restored.remove("m3.focusDividerEndDp");
        assertTrue("saved activity state should carry keys", written.size() > 10);
        assertEquals("saved and restored activity state keys must match", written, restored);

        // The two settings regressions that actually shipped.
        assertTrue("island conversion switch must be part of the pending fields",
                fields.contains("pendingIslandCompat"));
        assertTrue("island conversion switch must survive a restart", written.contains("m3.island"));
        assertTrue("long press switch must survive a restart",
                written.contains("m3.longPressNotificationRowClick"));
        assertTrue("long press duration must survive a restart",
                written.contains("m3.longPressSeconds"));
        assertTrue("long press switch must be saved to the snapshot", source.contains(
                ".longPressNotificationRowClick(pendingLongPressNotificationRowClick)"));
        assertTrue("long press duration must be saved to the snapshot", source.contains(
                ".longPressSeconds(pendingLongPressSeconds)"));
        // 长按开关不允许加入短按互斥链；时长只依赖长按自身。
        assertTrue(source.contains("setModeSpecificSwitchEnabled(longPressNotificationRowClickSwitch, true)"));
        assertTrue(source.contains("setControlEnabled(longPressSecondsSeekBar, pendingLongPressNotificationRowClick)"));
        assertTrue(!source.contains("pendingLongPressNotificationRowClick = false"));
        assertTrue(source.contains("长按焦点通知模拟通知列表点击（实验性）"));
        assertTrue(source.contains("默认关闭；长按直接走通知列表点击"));
        assertTrue("focus text mode must survive a restart",
                written.contains("m3.islandTextMode"));
    }

    @Test
    public void appearanceStateControlsAndCleanupRemainAvailableOnOs3() throws Exception {
        String source = readSettingsActivitySource();
        for (String field : new String[]{"focusIconSizeDp", "focusDividerStartDp", "focusDividerEndDp",
                "focusDividerWidthDp", "focusDividerStyle"}) {
            String pending = "pending" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            String wireField = field.equals("focusDividerStartDp") || field.equals("focusDividerEndDp")
                    ? field.replace("Dp", "OffsetDp") : field;
            assertTrue(source.contains("outState.putInt(\"m3." + wireField + "\", " + pending + ")"));
            assertTrue(source.contains("state.getInt(\"m3." + wireField + "\", " + pending + ")"));
            assertTrue(source.contains("." + field + "(" + pending + ")"));
        }
        for (String control : new String[]{"focusIconSizeSeekBar", "focusDividerStartSeekBar", "focusDividerEndSeekBar",
                "focusDividerWidthSeekBar", "focusDividerStyleField"}) assertTrue(source.contains(control + " = null;"));
        assertTrue(source.contains("setModeSpecificSwitchEnabled(showFocusDividerSwitch, true)"));
        assertTrue(!source.contains("显示焦点分隔线（HyperOS 4）"));
        assertTrue(source.contains("focusIconOrderContainer.setPadding(0, dp(6), 0, dp(6))"));
        assertTrue(!source.contains("protected void onMeasure"));
        assertTrue(source.contains("FocusRestoreSettings.focusIconSizeFromProgress(progress)"));
        assertTrue(source.contains("FocusRestoreSettings.focusDividerSpacingFromProgress(progress)"));
        assertTrue(source.contains("value == automatic ? \"跟随系统\""));
    }

    @Test
    public void numericValueDefaultsStayIndividualAndCleanedUp() throws Exception {
        String source = readSettingsActivitySource();
        for (String value : new String[]{"widthValue", "longPressSecondsValue", "touchWidthValue",
                "touchWidthLandscapeValue", "delayValue", "focusMaxDisplayValue", "valueLabel"}) {
            assertTrue(value + " has no individual default gesture", source.contains("bindValueReset(" + value + ","));
        }
        assertTrue(source.contains("bindValueReset((TextView) landscapeWidthRow.getChildAt(1),"));
        assertTrue(source.contains("val.setBackground(roundedBg(COLOR_PRIMARY_LIGHT, 12))"));
        assertTrue(source.contains("val.setMinimumHeight(dp(40))"));
        assertTrue(source.contains("ViewConfiguration.getDoubleTapTimeout()"));
        assertTrue(source.contains("SystemClock.elapsedRealtime()"));
        assertTrue(source.contains("protected void onPause() {\n        valueResetTaps.clear();"));
        assertTrue(source.contains("private void clearPageControls() {\n        valueResetTaps.clear();"));
        assertTrue(source.contains("focusMaxDisplayValue = null;"));
        // 程序设置进度不会触发fromUser分支，默认回调必须显式同步pending和值。
        assertTrue(source.contains("update.accept(automatic);\n            slider.setProgress(0);"));
        assertTrue(source.contains("valueLabel.setText(appearanceValue(automatic, automatic))"));
        for (String assignment : new String[]{
                "pendingWidthDp = DEFAULT_WIDTH_DP", "pendingWidthLandscapeDp = FocusRestoreSettings.DEFAULT_WIDTH_LANDSCAPE_DP",
                "pendingTouchWidthDp = FocusRestoreSettings.DEFAULT_TOUCH_WIDTH_DP",
                "pendingTouchWidthLandscapeDp = FocusRestoreSettings.DEFAULT_TOUCH_WIDTH_LANDSCAPE_DP",
                "pendingLongPressSeconds = FocusRestoreSettings.DEFAULT_LONG_PRESS_SECONDS",
                "pendingDelayMs = DEFAULT_MARQUEE_DELAY_MS",
                "pendingFocusMaxDisplaySeconds = FocusRestoreSettings.DEFAULT_FOCUS_MAX_DISPLAY_SECONDS"}) {
            assertTrue("default callback missing: " + assignment, source.contains(assignment));
        }
        int start = source.indexOf("private void bindValueReset(");
        int end = source.indexOf("private LinearLayout rangeRow", start);
        String gesture = source.substring(start, end);
        assertTrue(gesture.contains("if (!view.isEnabled()) return"));
        assertEquals("reset saves exactly once", 1, gesture.split("markPending\\(\\)", -1).length - 1);
        assertTrue(!gesture.contains("postDelayed"));
        assertTrue(source.contains("setChildrenEnabled(touchWidthOptionsPanel, pendingCustomTouchWidth)"));
        assertTrue(source.contains("setChildrenEnabled(landscapeWidthValueRow, enabled)"));
        assertTrue(!source.contains("ic_reset"));
    }

    @Test
    public void offsetBundleMigrationPreservesLegacyAutomaticAndNewNegativeOne() throws Exception {
        String source = readSettingsActivitySource();
        for (String side : new String[]{"Start", "End"}) {
            assertTrue(source.contains("state.containsKey(\"m3.focusDivider" + side + "OffsetDp\")"));
            assertTrue(source.contains("state.containsKey(\"m3.focusDivider" + side + "Dp\")"));
            assertTrue(source.contains("state.getInt(\"m3.focusDivider" + side + "Dp\", -1)"));
        }
        assertTrue(source.contains("FocusRestoreSettings.migrateLegacyDividerSpacing("));
        assertTrue(!source.contains("outState.putInt(\"m3.focusDividerStartDp\""));
        assertTrue(source.contains("\"分隔线前间距（时间侧）\", 49,"));
        assertTrue(source.contains("\"分隔线后间距（焦点侧）\", 49,"));
    }

    @Test
    public void adapterViewsUseItemListenersDuringPageCleanup() throws Exception {
        String source = readSettingsActivitySource();
        int start = source.indexOf("private void detachPageListeners(View view)");
        int end = source.indexOf("private LinearLayout panel()", start);
        assertTrue("page cleanup method must exist", start >= 0 && end > start);
        String cleanup = source.substring(start, end);
        // 源码守卫只防本次接口误用，不能代替 Android Spinner 切页/销毁实测。
        assertTrue("ordinary click cleanup must exclude AdapterView", cleanup.contains(
                "} else {\n            view.setOnClickListener(null);\n        }"));
        assertTrue(cleanup.contains("if (view instanceof AdapterView)"));
        assertTrue(cleanup.contains("adapter.setOnItemSelectedListener(null)"));
        assertTrue("Spinner also prohibits item click listeners", !cleanup.contains("setOnItemClickListener"));
        assertTrue(cleanup.contains("detachPageListeners(group.getChildAt(index))"));
    }

    @Test
    public void importConfirmationUsesSettingsDialogStyle() throws Exception {
        String source = readSettingsActivitySource();
        int start = source.indexOf("private View createImportConfirmDialogView");
        int end = source.indexOf("private void finishBackup", start);
        assertTrue("custom import confirmation dialog must exist", start >= 0 && end > start);
        String dialog = source.substring(start, end);
        assertTrue(dialog.contains("roundedBg(COLOR_SURFACE_HIGH, 12)"));
        assertTrue(dialog.contains("actionButton(\"取消\", Color.TRANSPARENT, COLOR_TEXT_SECONDARY)"));
        assertTrue(dialog.contains("actionButton(\"确认覆盖\", COLOR_PRIMARY, Color.WHITE)"));
    }

    private static Set<String> keys(Pattern pattern, String source) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private static String readSettingsActivitySource() throws Exception {
        String relative = "src/main/java/com/hyperos3/focusrestore/SettingsActivity.java";
        File directory = new File(System.getProperty("user.dir", "."));
        StringBuilder tried = new StringBuilder();
        for (int depth = 0; depth < 4 && directory != null; depth++) {
            for (File base : new File[]{directory, new File(directory, "app")}) {
                File candidate = new File(base, relative);
                if (candidate.isFile()) {
                    // 工作区可能按 core.autocrlf 使用 CRLF；结构守卫应忽略换行编码。
                    return new String(Files.readAllBytes(candidate.toPath()),
                            StandardCharsets.UTF_8).replace("\r\n", "\n");
                }
                tried.append(candidate.getPath()).append(", ");
            }
            directory = directory.getParentFile();
        }
        throw new AssertionError("SettingsActivity.java not found; tried " + tried);
    }
}