/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** 独立绘制的24dp放大镜，不依赖字体字形或ROM内置图标。 */
final class SettingsSearchIconDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;

    SettingsSearchIconDrawable(int color, float density) {
        size = Math.max(1, Math.round(24f * density));
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float scale = Math.min(bounds.width(), bounds.height()) / 24f;
        int save = canvas.save();
        canvas.translate(bounds.exactCenterX() - 12f * scale, bounds.exactCenterY() - 12f * scale);
        canvas.scale(scale, scale);
        paint.setStrokeWidth(1.9f);
        canvas.drawCircle(10.5f, 10.5f, 6.3f, paint);
        canvas.drawLine(15f, 15f, 20.2f, 20.2f, paint);
        canvas.restoreToCount(save);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
