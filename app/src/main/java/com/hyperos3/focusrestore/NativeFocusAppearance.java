/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. 由 FocusRestore 项目维护。
 * 原始项目：https://github.com/ImKani/FocusRestore
 * 外部事实来源：HyperOS/SystemUI 的 notification_item_bg 与 notification_focus_item_bg 资源；具体版本和分析记录见 Notes。
 * 说明：本文件为 FocusRestore 独立实现，不复制或重新授权 SystemUI 代码。
 */
package com.hyperos3.focusrestore;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Reads native notification geometry and themed resources without retaining or moving row Views. */
final class NativeFocusAppearance {
    private static volatile boolean useNormalNotificationBackground;

    static void setUseNormalNotificationBackground(boolean enabled) {
        useNormalNotificationBackground = enabled;
    }
    final boolean dark;
    final int width;
    final float radius;
    final Drawable background;
    final String description;

    private NativeFocusAppearance(boolean dark, int width, float radius,
                                  Drawable background, String description) {
        this.dark = dark;
        this.width = width;
        this.radius = radius;
        this.background = background;
        this.description = description;
    }

    static NativeFocusAppearance read(View row, Context sysuiContext) {
        // Use the current application configuration, not a plugin/row context with a forced theme.
        Context app = sysuiContext.getApplicationContext();
        Context context = app != null ? app : sysuiContext;
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        // This independent popup follows system light/dark. No island/glass forced-Dark override.
        boolean dark = night == Configuration.UI_MODE_NIGHT_YES;
        int padding = dimension(context, "notification_side_paddings");
        int width = row != null ? row.getWidth() : 0;
        String geometrySource = "row-width";
        if (width <= 0) {
            int panel = dimension(context, "notification_panel_width");
            int screen = context.getResources().getDisplayMetrics().widthPixels;
            width = Math.min(panel > 0 ? panel : screen, screen) - 2 * Math.max(0, padding);
            geometrySource = "notification-panel-resources";
        }
        // notification_min_height belongs to the shade row, not this native content root.
        // Applying it to our wrappers stretched short templates and left empty space below them.
        int radius = dimension(context, "notification_item_bg_radius");
        if (radius <= 0) radius = dimension(context, "notification_corner_radius");
        if (width <= 0 || radius <= 0) {
            throw new IllegalStateException("native notification geometry resources unavailable");
        }
        // 普通背景模式必须使用通知栏旧卡片 drawable；关闭时沿用原生 Focus 纯色背景，避免改变默认外观。
        String backgroundName = useNormalNotificationBackground
                ? "notification_item_bg" : "notification_focus_item_bg";
        int id = identifier(context, "drawable", backgroundName);
        if (id == 0) throw new IllegalStateException("native themed notification background unavailable: " + backgroundName);
        Drawable background = context.getDrawable(id);
        if (background == null) throw new IllegalStateException("native notification background is null: " + backgroundName);
        background = background.mutate();
        return new NativeFocusAppearance(dark, width, radius, background,
                (dark ? "system-dark" : "system-light") + "/" + geometrySource
                        + "/" + backgroundName + "; blur-and-modal-animation-not-copied");
    }

    private static int dimension(Context context, String name) {
        int id = identifier(context, "dimen", name);
        return id == 0 ? 0 : context.getResources().getDimensionPixelSize(id);
    }

    private static int identifier(Context context, String type, String name) {
        return context.getResources().getIdentifier(name, type, "com.android.systemui");
    }
}
