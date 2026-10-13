/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

/** 等比绘制图标，岛图标保留安全边距；可选状态栏单色着色。 */
final class FocusIconStyler {
    private static boolean sizeFallbackLogged;
    static final class Result {
        final Icon icon;
        final Drawable drawable;

        Result(Icon icon, Drawable drawable) {
            this.icon = icon;
            this.drawable = drawable;
        }
    }

    private FocusIconStyler() { }

    static int statusBarSizePx(Context context, int customSizeDp) {
        if (customSizeDp == 0) return statusBarSizePx(context);
        int sizeDp = Math.max(1, Math.min(32, customSizeDp));
        return Math.max(1, Math.min(256,
                Math.round(sizeDp * context.getResources().getDisplayMetrics().density)));
    }

    static int statusBarSizePx(Context context) {
        Resources resources = context.getResources();
        int fallback = Math.max(1, Math.min(128,
                Math.round(16f * resources.getDisplayMetrics().density)));
        // 来源：作者授权的小米 SystemUI OS3 16.03.251211.r/code202501210 与
        // OS4 17.03.260226.r/code202602260，MT APK MCP。两者 focused_notif_icon_size
        // 均为16dp；OS3 status_bar.xml 的 centerInside 不会放大13dp图片。
        // 独立按当前ROM资源绘制，缺资源按已核实16dp降级；私有许可未确认，未复制实现。
        try {
            int id = resources.getIdentifier("focused_notif_icon_size", "dimen", "com.android.systemui");
            if (id != 0) {
                int size = resources.getDimensionPixelSize(id);
                if (size > 0) return Math.min(128, size);
            }
            logSizeFallback(fallback, null);
        } catch (RuntimeException failure) {
            logSizeFallback(fallback, failure);
        }
        return fallback;
    }

    private static synchronized void logSizeFallback(int fallback, RuntimeException failure) {
        if (sizeFallbackLogged) return;
        sizeFallbackLogged = true;
        android.util.Log.w("HyperOS3FocusRestore",
                "Focus icon size resource unavailable; fallbackPx=" + fallback, failure);
    }

    static Result load(Context context, Icon source, boolean islandIcon,
                       boolean tint, int tintColor, int sizeDp) {
        if (context == null) return null;
        return loadAtSize(context, source, islandIcon, tint, tintColor,
                Math.round(sizeDp * context.getResources().getDisplayMetrics().density));
    }

    static Result loadAtSize(Context context, Icon source, boolean islandIcon,
                             boolean tint, int tintColor, int sizePx) {
        if (context == null || source == null) return null;
        Drawable drawable = source.loadDrawable(context);
        if (drawable == null) return null;
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.max(1, Math.min(256, sizePx));
        // 不再扩张黑色描边：它会填掉透明孔洞，浅色背景下与黑色主体合成一团。
        // 岛图标留 1dp 透明安全边距，并按原比例居中，避免外缘贴边及非方形图失真。
        int padding = islandIcon ? Math.min((size - 1) / 2,
                Math.max(1, Math.round(density))) : 0;
        int[] bounds = FocusIconPixels.bounds(size, padding,
                drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        // 位图像素已经按目标 Context 换算，避免进程默认密度再次改变 Drawable 的固有尺寸。
        bitmap.setDensity(context.getResources().getDisplayMetrics().densityDpi);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(bounds[0], bounds[1], bounds[2], bounds[3]);
        drawable.draw(canvas);
        if (tint) {
            int[] pixels = new int[size * size];
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
            for (int index = 0; index < pixels.length; index++) {
                pixels[index] = FocusIconPixels.recolor(pixels[index], tintColor);
            }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
        }
        return new Result(Icon.createWithBitmap(bitmap),
                new BitmapDrawable(context.getResources(), bitmap));
    }
}
