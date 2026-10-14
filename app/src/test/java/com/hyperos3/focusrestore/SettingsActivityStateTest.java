/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void initialDetectionIsLimitedToEmptyStoresAndExpandedGroupsAreUiState() throws Exception {
        String source = readSettingsActivitySource();
        assertTrue(source.contains("preferences.getAll().isEmpty() && hookPreferences.getAll().isEmpty()"));
        assertEquals(1, source.split("InitialHookMode\\.detect\\(\\)", -1).length - 1);
        assertTrue(source.contains("outState.putStringArrayList(\"ui.expandedSections\""));
        assertTrue(source.contains("state.getStringArrayList(\"ui.expandedSections\")"));
        assertTrue(source.contains("content.setVisibility(expanded ? View.VISIBLE : View.GONE)"));
    }

    /**
     * 未解锁时凭据存储不可读，两份存储会同时读成空；此时"两库都空即视为首次安装"的判断会把
     * 默认值写进两份存储并覆盖真实配置。0.37.1 真机日志出现过该顺序：同一进程先记录
     * provider ready=false，紧接着 settings initialized 写入全默认值，所以初始化必须先判解锁。
     */
    @Test
    public void settingsInitializationIsGatedOnUserUnlock() throws Exception {
        String source = readSettingsActivitySource();
        assertTrue(source.contains("private boolean userUnlocked()"));
        assertTrue(source.contains("isUserUnlocked()"));
        assertTrue(source.contains("settings init deferred: user locked"));
        int guard = source.indexOf("if (!userUnlocked())");
        int initialization = source.indexOf("preferences.getAll().isEmpty()");
        assertTrue("unlock guard must exist before the store initialization", guard >= 0);
        assertTrue("store initialization must follow the unlock guard", initialization > guard);
    }

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
        assertTrue(source.contains("长按直接打开通知，独立于短按。"));
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
        assertTrue(!source.contains("class DisclosureChevronDrawable"));
        assertTrue(!source.contains("arrow.setText(expanded ? \"▲\" : \"▼\")"));
        assertTrue(source.contains("arrow.setImageResource(R.drawable.ic_section_chevron)"));
        assertTrue(source.contains("arrow.setRotation(expanded ? 180f : 0f)"));
        assertTrue(source.contains("systemDimensionDp(\"focused_notif_icon_size\", 16f)"));
        assertTrue(source.contains("FocusRestoreSettings.AUTO_FOCUS_DIVIDER_SPACING_DP"));
        assertTrue(source.contains("if (value != automatic) { label.setText(value + \" dp\"); return; }"));
        assertTrue(source.contains("label.setText(number + \" dp\")"));
        int start = source.indexOf("private SeekBar appearanceSlider(");
        int end = source.indexOf("private void setAppearanceValue(", start);
        assertTrue("appearance slider must exist", start >= 0 && end > start);
        String appearance = source.substring(start, end);
        assertTrue(appearance.contains("if (defaultValue != automatic)"));
        assertTrue(appearance.contains("defaultAction(\"默认\", label, () -> {"));
        assertTrue(appearance.contains("current[0] = defaultValue;\n                apply.accept(defaultValue);"));
        assertTrue(appearance.contains("() -> current[0] == defaultValue, () -> slider.isEnabled()"));
        assertTrue(appearance.contains("defaultAction(defaultValue == automatic ? \"跟随系统（默认）\" : \"跟随系统\""));
        assertTrue(appearance.contains("current[0] = automatic; apply.accept(automatic);"));
        assertTrue(appearance.contains("() -> current[0] == automatic, () -> slider.isEnabled()"));
        // 程序设置进度不会触发 fromUser 分支，恢复必须显式同步 pending 和数值。
        assertTrue(appearance.contains("update.accept(value);"));
        assertTrue(appearance.contains("setAppearanceValue(valueLabel, value, automatic, systemDp)"));
    }

    @Test
    public void numericValueDefaultsStayIndividualAndCleanedUp() throws Exception {
        String source = readSettingsActivitySource();
        int rowStart = source.indexOf("private LinearLayout valueRow(");
        int rowEnd = source.indexOf("private int numericValueWidth(", rowStart);
        assertTrue("numeric value row must exist", rowStart >= 0 && rowEnd > rowStart);
        String row = source.substring(rowStart, rowEnd);
        assertTrue(row.contains("val.setSingleLine(true)"));
        assertTrue(row.contains("numericValueWidth(val), ViewGroup.LayoutParams.WRAP_CONTENT"));
        assertTrue(!row.contains("setBackground"));
        assertTrue(!row.contains("setMinimumHeight"));
        assertTrue(!source.contains("bindValueReset("));
        assertTrue(!source.contains("valueResetTaps"));
        assertTrue(!source.contains("getDoubleTapTimeout()"));
        assertTrue(source.contains("private void clearPageControls() {\n        defaultActionRefreshers.clear();"));
        assertTrue(source.contains("focusMaxDisplayValue = null;"));
        String[] fields = {"pendingWidthDp", "pendingWidthLandscapeDp", "pendingTouchWidthDp",
                "pendingTouchWidthLandscapeDp", "pendingLongPressSeconds", "pendingDelayMs",
                "pendingFocusMaxDisplaySeconds"};
        String[] defaults = {"DEFAULT_WIDTH_DP", "FocusRestoreSettings.DEFAULT_WIDTH_LANDSCAPE_DP",
                "FocusRestoreSettings.DEFAULT_TOUCH_WIDTH_DP", "FocusRestoreSettings.DEFAULT_TOUCH_WIDTH_LANDSCAPE_DP",
                "FocusRestoreSettings.DEFAULT_LONG_PRESS_SECONDS", "DEFAULT_MARQUEE_DELAY_MS",
                "FocusRestoreSettings.DEFAULT_FOCUS_MAX_DISPLAY_SECONDS"};
        String[] enabled = {"pendingManual", "pendingManual", "pendingCustomTouchWidth",
                "pendingCustomTouchWidth", "pendingLongPressNotificationRowClick", "true", "true"};
        for (int index = 0; index < fields.length; index++) {
            String assignment = fields[index] + " = " + defaults[index];
            assertTrue("individual default callback missing: " + fields[index], Pattern.compile(
                    "addDefaultRange\\([^\\n]*, \\(\\) -> \\{\\s*" + Pattern.quote(assignment)
                            + ";[^}]*\\}, \\(\\) -> " + Pattern.quote(fields[index] + " == " + defaults[index])
                            + ", \\(\\) -> " + Pattern.quote(enabled[index]) + "\\);")
                    .matcher(source).find());
        }
        int start = source.indexOf("private TextView defaultAction(");
        int end = source.indexOf("private LinearLayout rangeRow", start);
        assertTrue("default action must exist", start >= 0 && end > start);
        String action = source.substring(start, end);
        assertTrue(action.contains("if (!enabled.getAsBoolean()) return"));
        assertTrue(action.contains("reset.run();"));
        assertEquals("reset saves exactly once", 1, action.split("markPending\\(\\)", -1).length - 1);
        assertTrue(action.contains("action.setTextColor(selected.getAsBoolean() ? COLOR_PRIMARY : COLOR_TEXT_PRIMARY)"));
        assertTrue(action.contains("action.setSelected(selected.getAsBoolean())"));
        assertTrue(action.contains("setControlEnabled(action, enabled.getAsBoolean())"));
        assertTrue(action.contains("defaultActionRefreshers.add(refresh)"));
        assertTrue(action.contains("for (Runnable refresh : defaultActionRefreshers) refresh.run()"));
        assertTrue(action.contains("row.addView(defaultAction(\"默认\", label, reset, selected, enabled), 1)"));
        assertTrue(source.contains("private void markPending() {\n        refreshDefaultActions();"));
        assertTrue(!action.contains("postDelayed"));
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
        assertTrue(source.contains("\"分隔线前间距（时间侧）\", -24, 24,"));
        assertTrue(source.contains("\"分隔线后间距（焦点侧）\", -24, 24,"));
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

    /**
     * 文本框输入必须合并保存。真机 0.37.1 日志逐字符提交（7 秒窗口内最多 57 次保存，共 66 次），
     * 并逐字符把 islandSideSeparator 落盘成 `'的*@&@*…` 这类半截值；守卫要求 debounce 与
     * "离开页面前冲刷"同时存在，否则最后一次输入会丢。
     */
    @Test
    public void textFieldInputIsCoalescedAndFlushed() throws Exception {
        String source = readSettingsActivitySource();
        assertTrue(source.contains("private void scheduleTextSave()"));
        assertTrue(source.contains("postDelayed(textSaveRunnable, TEXT_SAVE_DEBOUNCE_MS)"));
        assertTrue(source.contains("private void flushTextSave()"));
        assertTrue(source.contains("focusMaxDisplayInput.hasFocus()) scheduleTextSave()"));
        // 暂停与销毁两条路径都不能丢最后一次输入。
        assertTrue(source.contains("flushTextSave();"));
        assertTrue(source.contains("textSaveHandler.removeCallbacks(textSaveRunnable);"));
        // 逐字符直接保存是本次修复前的形态，不得回归。
        assertFalse(source.contains("if (field.hasFocus()) markPending();"));
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
