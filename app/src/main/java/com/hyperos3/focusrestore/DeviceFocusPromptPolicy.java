/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

/**
 * 设备通知焦点提示要不要由模块自己交给提示视图的判定。
 *
 * <p>抽成纯函数是为了能脱离 SystemUI 依赖做单元测试：真机上这条判定决定"竖屏只剩分隔竖线"
 * 会不会复现（详见 {@link HyperOS3FocusRestoreHook#applyConstructedDevicePrompt}）。
 */
final class DeviceFocusPromptPolicy {
    private DeviceFocusPromptPolicy() {
    }

    /**
     * @param hasRemoteViews 这次询问携带的 bean 是否自带提示 RemoteViews；自带时由 ROM 自己渲染
     * @param hasText        bean 里是否有可显示的文字
     * @param islandCompat   用户的"岛内容兼容"总开关；关闭时模块不介入系统提示内容
     */
    static boolean shouldApplyToPromptView(boolean hasRemoteViews, boolean hasText,
                                           boolean islandCompat) {
        return islandCompat && !hasRemoteViews && hasText;
    }
}