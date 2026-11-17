/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

/*
 * 资源来源：HyperOS/SystemUI 的 notification_item_bg 与 notification_focus_item_bg；具体版本和分析记录见 Notes。
 */

package com.hyperos3.focusrestore;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Reads native notification geometry and themed resources without retaining or moving row Views. */
final class NativeFocusAppearance {
    private static volatile boolean useNormalNotificationBackground;
    private static volatile boolean advancedMaterialEnabled;
    private static volatile int installedHookMode;

    static void setInstalledHookMode(int mode) {
        installedHookMode = mode;
    }

    static boolean useOs4GlassHunBlend(boolean glass) {
        // 使用本进程已安装的手动模式；配置改为OS3/4但未重启时不能提前切材质合同。
        return useNativeMaterial() && installedHookMode == FocusRestoreSettings.HOOK_MODE_OS4
                && glass;
    }

    static boolean useGlassDarkTemplate(boolean glass) {
        return useNativeMaterial() && installedHookMode == FocusRestoreSettings.HOOK_MODE_OS4 && glass;
    }

    static boolean useDarkTemplate(boolean systemDark, boolean glass, boolean materialFailed) {
        return useDarkTemplate(systemDark, glass, materialFailed, false);
    }

    static boolean useDarkTemplate(boolean systemDark, boolean glass, boolean materialFailed, boolean hunReady) {
        return systemDark || (!hunReady && !materialFailed && useGlassDarkTemplate(glass));
    }

    static boolean isOs3() { return installedHookMode == FocusRestoreSettings.HOOK_MODE_OS3; }

    static String backgroundName(boolean hunEligible) {
        return useNativeMaterial() && isOs3() && hunEligible ? "notification_focus_heads_up_bg"
                : useNormalNotificationBackground ? "notification_item_bg" : "notification_focus_item_bg";
    }

    static void setAdvancedMaterialEnabled(boolean enabled) {
        advancedMaterialEnabled = enabled;
    }

    static void setUseNormalNotificationBackground(boolean enabled) {
        useNormalNotificationBackground = enabled;
    }
    // 材质接入独立于背景选择，旧配置缺字段时保持关闭。
    static boolean useNativeMaterial() {
        return advancedMaterialEnabled && useNormalNotificationBackground;
    }

    final boolean dark;
    final int width;
    final float radius;
    final Drawable background;
    final String description;
    private final Context themeContext;

    private final boolean systemDark;
    final boolean hunEligible;
    private NativeFocusAppearance(boolean dark, int width, float radius,
                                  Drawable background, String description, Context themeContext, boolean hunEligible) {
        this.hunEligible = hunEligible;
        this.systemDark = isSystemDark(themeContext);
        this.dark = dark;
        this.width = width;
        this.radius = radius;
        this.background = background;
        this.description = description;
        this.themeContext = themeContext;
    }

    boolean isCurrent(boolean materialFailed) {
        return systemDark == isSystemDark(themeContext)
                && dark == isDarkTemplate(themeContext, materialFailed, hunEligible);
    }

    static boolean freezeHunEligibility(Context context, boolean eligible) {
        // 缺样本创建的横幅始终用旧配方；同一代显示期间的迟到样本不能改变正文或触发收起。
        boolean sample = eligible && !isOs3() && NativeBannerMaterial.hasHunGlassParameters(context);
        boolean frozen = BannerMaterialRecipe.freezeEligibility(eligible, isOs3(), sample);
        if (eligible && !frozen) android.util.Log.i("HyperOS3FocusRestore",
                "banner HUN unavailable at render creation; keep legacy until next render");
        return frozen;
    }

    static boolean isSystemDark(Context context) {
        Context app = context.getApplicationContext();
        Context live = app != null ? app : context;
        return (live.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    static boolean isDarkTemplate(Context sysuiContext, boolean materialFailed) {
        return isDarkTemplate(sysuiContext, materialFailed, false);
    }

    static boolean isDarkTemplate(Context sysuiContext, boolean materialFailed, boolean hunEligible) {
        Context app = sysuiContext.getApplicationContext();
        Context context = app != null ? app : sysuiContext;
        boolean glass = !materialFailed && NativeBannerMaterial.isGlassActive();
        boolean hunReady = hunEligible && glass && NativeBannerMaterial.hasHunGlassParameters(context);
        return useDarkTemplate((context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES, glass, materialFailed, hunReady);
    }

    static NativeFocusAppearance read(View row, Context sysuiContext,
                                     android.service.notification.StatusBarNotification sbn) {
        // Use the current application configuration, not a plugin/row context with a forced theme.
        Context app = sysuiContext.getApplicationContext();
        Context context = app != null ? app : sysuiContext;
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean hunEligible = freezeHunEligibility(context, BannerHunEligibility.read(row, sbn));
        boolean dark = isDarkTemplate(context, false, hunEligible);
        boolean glassDark = dark && night != Configuration.UI_MODE_NIGHT_YES;
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
        String backgroundName = backgroundName(hunEligible);
        int id = identifier(context, "drawable", backgroundName);
        if (id == 0 && "notification_focus_heads_up_bg".equals(backgroundName)) {
            android.util.Log.w("HyperOS3FocusRestore", "banner focus HUN background missing; keep notification_item_bg");
            backgroundName = "notification_item_bg";
            id = identifier(context, "drawable", backgroundName);
        }
        if (id == 0) throw new IllegalStateException("native themed notification background unavailable: " + backgroundName);
        Drawable background = context.getDrawable(id);
        if (background == null) throw new IllegalStateException("native notification background is null: " + backgroundName);
        background = background.mutate();
        return new NativeFocusAppearance(dark, width, radius, background,
                (glassDark ? "glass-dark-template" : dark ? "system-dark" : "system-light") + "/" + geometrySource
                        + "/" + backgroundName + "; systemNight=" + night + "; blur-and-modal-animation-not-copied", context, hunEligible);
    }

    /** 复用本类已接入的 SystemUI 面板资源；不读取或改挂原生媒体 View。 */
    static int mediaWidth(Context context) {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int panel = 0;
        int padding = 0;
        try {
            panel = dimension(context, "notification_panel_width");
            padding = dimension(context, "notification_side_paddings");
        } catch (android.content.res.Resources.NotFoundException error) {
            android.util.Log.w("HyperOS3FocusRestore", "media banner geometry resource unavailable; use short edge", error);
        }
        return MediaBannerWidth.resolve(metrics.widthPixels, metrics.heightPixels, panel, padding);
    }

    private static int dimension(Context context, String name) {
        int id = identifier(context, "dimen", name);
        return id == 0 ? 0 : context.getResources().getDimensionPixelSize(id);
    }

    private static int identifier(Context context, String type, String name) {
        return context.getResources().getIdentifier(name, type, "com.android.systemui");
    }
}
