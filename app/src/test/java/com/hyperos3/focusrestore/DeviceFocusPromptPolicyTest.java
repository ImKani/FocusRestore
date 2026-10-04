/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 0.25.7：设备通知焦点提示文字为空（竖屏只剩分隔竖线）的判定。
 *
 * <p>真机依据 `log/focus-restore.0.25.6.log`：静音 / 勿扰关闭 / 充电事件里提示文字视图在 t0、t1
 * 两次 dump 中都为空，只有勿扰开启那次有文字；后者是 ROM 自己把 bean 交给了提示视图。
 */
public class DeviceFocusPromptPolicyTest {
    @Test
    public void constructedDeviceNotificationIsAppliedByTheModule() {
        // 模块构造的通知只有 miui.focus.ticker，没有 RemoteViews，由模块自己交接。
        assertTrue(DeviceFocusPromptPolicy.shouldApplyToPromptView(false, true, true));
    }

    @Test
    public void romRemoteViewsKeepPriority() {
        // 自带 RemoteViews 的焦点通知照旧由 ROM 渲染，模块不抢。
        assertFalse(DeviceFocusPromptPolicy.shouldApplyToPromptView(true, true, true));
    }

    @Test
    public void emptyTextIsNotApplied() {
        // 没有可显示的文字时不写视图，避免把提示清成空白。
        assertFalse(DeviceFocusPromptPolicy.shouldApplyToPromptView(false, false, true));
    }

    @Test
    public void islandCompatibilityDisabledSkipsTheModuleHandoff() {
        // 用户关掉岛内容兼容时模块不介入系统提示内容，与其它转换路径一致。
        assertFalse(DeviceFocusPromptPolicy.shouldApplyToPromptView(false, true, false));
        assertFalse(DeviceFocusPromptPolicy.shouldApplyToPromptView(true, true, false));
    }
}