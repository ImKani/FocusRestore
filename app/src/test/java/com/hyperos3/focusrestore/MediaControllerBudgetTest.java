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
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Source guard for the media controller construction budget.
 *
 * <p>{@code MediaController.release()} is {@code @hide} and the hidden-API policy rejects it by
 * reflection on device, so no module code can free a controller. 0.37.1 tried anyway and produced
 * 2110 {@code NoSuchMethodException} reports in one OS3 session, which buried every other
 * diagnostic. The supported shape is therefore: construct through the token-keyed cache in
 * {@link MediaFocusContent}, and keep every construction site enumerated here so a new per-event
 * construction cannot be added without updating this guard.
 */
public class MediaControllerBudgetTest {
    private static final String CONSTRUCTION = "new MediaController(";
    private static final String RELEASE_REFLECTION = "\"release\"";
    private static final String PACKAGE_PATH = "src/main/java/com/hyperos3/focusrestore";

    @Test
    public void controllersAreConstructedOnlyAtTheEnumeratedSites() throws Exception {
        List<String> sites = new ArrayList<>();
        int total = 0;
        for (File file : mainSources()) {
            String name = file.getName();
            int count = occurrences(read(file).replace("\r\n", "\n"), CONSTRUCTION);
            if (count == 0) continue;
            total += count;
            sites.add(name + "=" + count);
        }
        // MediaFocusContent holds the cached factory; MediaNotificationBannerSource owns its
        // controllers per banner instance. Any other site is a new per-event construction.
        assertEquals("unexpected MediaController construction sites: " + sites,
                "[MediaFocusContent.java=1, MediaNotificationBannerSource.java=2]", sites.toString());
        assertEquals("unexpected MediaController construction count", 3, total);
    }

    @Test
    public void noSourceReflectsTheHiddenReleaseMethod() throws Exception {
        for (File file : mainSources()) {
            String source = read(file).replace("\r\n", "\n");
            assertFalse(file.getName() + " must not reflect MediaController#release()",
                    source.contains(RELEASE_REFLECTION));
        }
    }

    /**
     * The cached factory is what bounds controller growth: without the reuse and the capacity cap
     * the per-inspection {@code new MediaController} path comes back.
     */
    @Test
    public void mediaFocusContentReusesControllersPerToken() throws Exception {
        String source = read(sourceFile("MediaFocusContent.java")).replace("\r\n", "\n");
        assertTrue(source.contains("MAX_CONTROLLERS"));
        assertTrue(source.contains("removeEldestEntry"));
        assertTrue(source.contains("System.identityHashCode(token)"));
        assertTrue(source.contains("CONTROLLERS.get(tokenKey)"));
        // The hidden-API fact behind the rewrite stays documented next to the code it explains.
        assertTrue(source.contains("MediaController#release()"));
    }

    /** 设置读取成功后必须补装 Hook，否则 attach 阶段读取失败会让整个模块静默失效到重启系统界面。 */
    @Test
    public void deferredHookInstallationIsRetriedAfterASuccessfulRead() throws Exception {
        String source = read(sourceFile("HyperOS3FocusRestoreHook.java")).replace("\r\n", "\n");
        assertTrue(source.contains("private void installHooksAfterDeferredSettings()"));
        int call = source.indexOf("installHooksAfterDeferredSettings();");
        int readOk = source.indexOf("hasSuccessfulProviderSettings = true;");
        assertTrue("补装必须在设置读取成功之后触发", readOk >= 0 && call > readOk);
    }

    /**
     * 长按成立后必须吞掉那次抬手。
     *
     * <p>0.37.2 之前的形态：{@code onEvent} 在 ACTION_UP 时先读到已消费、随后立刻 reset，等到挂钩点
     * 判断要不要消费事件时标记已经没了，于是抬手照常传给 ROM/子视图，短按点击和长按动作一起触发。
     * 守卫要求"吞掉标记"在 reset 之外单独存在，并且挂钩点真的用返回值消费了 ACTION_UP。
     */
    @Test
    public void longPressSwallowsTheFollowingRelease() throws Exception {
        String state = read(sourceFile("FocusLongPressState.java")).replace("\r\n", "\n");
        assertTrue(state.contains("private boolean swallowRelease;"));
        assertTrue(state.contains("boolean consumeRelease()"));
        assertTrue("长按成立时必须置位吞掉标记", state.contains("swallowRelease = true;"));

        String gesture = read(sourceFile("FocusLongPressGesture.java")).replace("\r\n", "\n");
        assertTrue(gesture.contains("boolean consumeRelease() { return state.consumeRelease(); }"));

        String hook = read(sourceFile("HyperOS3FocusRestoreHook.java")).replace("\r\n", "\n");
        assertTrue(hook.contains("gesture.consumeRelease()"));
        assertTrue("长按成立后必须消费那次抬手",
                hook.contains("if (releasedAfterLongPress) param.setResult(true);"));
    }

    /**
     * 长按成立后必须在"同一次手势"内抑制短按，而不是按固定时间窗。
     *
     * <p>0.37.6 真机日志把成因定量了：短按由本次手势的 {@code ACTION_UP} 触发（抬手后 1ms 建起
     * 媒体横幅），而抬手距长按成立是 300ms 到 7.9 秒不等。固定 600ms 窗口必然漏掉，放宽窗口又会
     * 吞掉用户随后的正常短按。拦截点也必须放在 {@code handleTouchEvent}：挂在
     * {@code onFocusNotifPromptClicked} 上的钩子在该 ROM 上一次都没有执行。
     */
    @Test
    public void shortPressIsSuppressedWithinTheSameGesture() throws Exception {
        String suppression = read(sourceFile("FocusShortPressSuppression.java")).replace("\r\n", "\n");
        assertTrue(suppression.contains("void arm()"));
        assertTrue("标记必须一次性消费", suppression.contains("if (!armed) return false;"));
        assertTrue("抬手/取消后必须复位，避免跨手势泄漏", suppression.contains("void clear()"));

        String gesture = read(sourceFile("FocusLongPressGesture.java")).replace("\r\n", "\n");
        assertTrue("长按成立时必须通知抑制置位", gesture.contains("FocusBannerController.onLongPressFired();"));

        String controller = read(sourceFile("FocusBannerController.java")).replace("\r\n", "\n");
        assertTrue(controller.contains("static void useShortPressSuppression(FocusShortPressSuppression"));
        assertTrue(controller.contains("if (suppression != null) suppression.arm();"));
        assertFalse("按时间窗判定短按的旧实现必须移除",
                controller.contains("longPressFiredRecently()"));

        String hook = read(sourceFile("HyperOS3FocusRestoreHook.java")).replace("\r\n", "\n");
        assertTrue(hook.contains("private void hookOS3TouchEntry()"));
        assertTrue(hook.contains("hookOS3TouchEntry();"));
        assertFalse("旧的时间窗抑制钩子必须移除",
                hook.contains("suppressShortPressAfterLongPress()"));
        int entryStart = hook.indexOf("private void hookOS3TouchEntry()");
        int consume = hook.indexOf("if (suppression.consume())", entryStart);
        int swallow = hook.indexOf("param.setResult(null)", consume);
        int ended = hook.indexOf("suppression.clear()", swallow);
        assertTrue("命中抑制时必须消费这次 ROM 短按", consume > entryStart && swallow > consume);
        assertTrue("手势结束时必须复位未消费的标记", ended > swallow);
        assertTrue("手势入口必须装在 handleTouchEvent 上",
                hook.indexOf("getDeclaredMethod(\"handleTouchEvent\"", entryStart) > entryStart);
        // findAndHookMethod 装的 onFocusNotifPromptClicked 在该 ROM 上一轮都没执行过，
        // 点击判定必须改用与 handleTouchEvent 相同的安装方式。
        assertTrue("点击判定入口必须改用 hookMethod + getDeclaredMethod",
                hook.contains("hookMethod(promptView.getDeclaredMethod(\"onFocusNotifPromptClicked\")"));
        assertFalse("不得再用 findAndHookMethod 装 onFocusNotifPromptClicked",
                hook.contains("findAndHookMethod(promptView, \"onFocusNotifPromptClicked\""));
    }

    private static List<File> mainSources() {
        List<File> result = new ArrayList<>();
        File directory = sourceDirectory();
        // Only the flat module package is walked; the guards above name their files explicitly.
        File[] files = new File(directory, PACKAGE_PATH).listFiles();
        if (files == null) throw new AssertionError("package directory missing under " + directory);
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".java")) result.add(file);
        }
        if (result.isEmpty()) throw new AssertionError("no main sources found under " + directory);
        return result;
    }

    private static File sourceFile(String name) {
        return new File(new File(sourceDirectory(), PACKAGE_PATH), name);
    }

    private static File sourceDirectory() {
        File directory = new File(System.getProperty("user.dir", "."));
        for (int depth = 0; depth < 4 && directory != null; depth++) {
            if (new File(directory, PACKAGE_PATH).isDirectory()) return directory;
            if (new File(new File(directory, "app"), PACKAGE_PATH).isDirectory()) {
                return new File(directory, "app");
            }
            directory = directory.getParentFile();
        }
        throw new AssertionError("module sources not found from " + System.getProperty("user.dir"));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static int occurrences(String source, String needle) {
        return source.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
