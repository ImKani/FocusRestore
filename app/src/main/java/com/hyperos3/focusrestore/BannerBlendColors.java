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

    private BannerBlendColors() { }
}
