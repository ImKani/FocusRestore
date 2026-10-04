/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

/** 等比绘制图标，岛图标保留安全边距；可选状态栏单色着色。 */
final class FocusIconStyler {
    static final class Result {
        final Icon icon;
        final Drawable drawable;

        Result(Icon icon, Drawable drawable) {
            this.icon = icon;
            this.drawable = drawable;
        }
    }

    private FocusIconStyler() { }

    static Result load(Context context, Icon source, boolean islandIcon,
                       boolean tint, int tintColor, int sizeDp) {
        if (context == null || source == null) return null;
        Drawable drawable = source.loadDrawable(context);
        if (drawable == null) return null;
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.max(1, Math.min(128, Math.round(sizeDp * density)));
        // 不再扩张黑色描边：它会填掉透明孔洞，浅色背景下与黑色主体合成一团。
        // 岛图标留 1dp 透明安全边距，并按原比例居中，避免外缘贴边及非方形图失真。
        int padding = islandIcon ? Math.min((size - 1) / 2,
                Math.max(1, Math.round(density))) : 0;
        int[] bounds = FocusIconPixels.bounds(size, padding,
                drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
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