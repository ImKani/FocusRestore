/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 高级材质共用的通知背景配方，不修改文字、玻璃参数或系统材质选择。 */
final class BannerBlendColors {
    interface Resources {
        int color(String name);
        int integer(String name);
    }

    static int[] load(Resources resources, boolean hun) {
        if (resources == null) throw new IllegalStateException("notification blend resources unavailable");
        // SystemUI OS4 17.03.260226.r HeadsUpNotificationBlurEffect使用headsUp两对资源；
        // 来源：Knowledge/sources/decompiled/notification-headsup-20261115/bg-hun-resources.json。
        // ROM版权所有者/实现许可未确认；这里只按资源名独立读取，不复制其实现或参数。
        // 普通配方仍适用于OS3/OS4 BLUR；颜色和模式全部交给ROM按系统日夜解析。
        String prefix = hun ? "notification_element_blend_headsUp_" : "notification_element_blend_shade_";
        return new int[] {
                resources.color(prefix + "color_1"),
                resources.integer(prefix + "mode_1"),
                resources.color(prefix + "color_2"),
                resources.integer(prefix + "mode_2")
        };
    }

    /** OS3 SystemUI 16.03.251211.r updateBlurBg 的焦点 HUN 六项合同。
     * 来源：Knowledge/sources/decompiled/os3-native-focus-hun-20261117；Xiaomi，许可未确认。
     * 只按 ROM 资源名读取日夜值，不复制厂商实现或内嵌色值。
     */
    static int[] loadFocusHun(Resources resources) {
        if (resources == null) throw new IllegalStateException("focus HUN resources unavailable");
        return new int[] {
                resources.color("focus_notification_element_blend_headsUp_color_1"),
                resources.integer("blur_mode_linear_light"),
                resources.color("focus_notification_element_blend_headsUp_color_2"),
                resources.integer("blur_mode_lab"),
                resources.color("focus_notification_element_blend_headsUp_color_3"),
                resources.integer("blur_mode_pure")
        };
    }

    private BannerBlendColors() { }
}
