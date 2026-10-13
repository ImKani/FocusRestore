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

/** 焦点分隔装饰，固定在正文之外；OS3/OS4共用尺寸和绘制规则。 */
final class FocusDividerDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int style;
    private final float stroke;
    private final int width;
    private final int height;

    FocusDividerDrawable(float density, int style, int widthDp, int color) {
        this(density, style, widthDp, color, Math.max(1f, density));
    }

    FocusDividerDrawable(float density, int style, int widthDp, int color, float nativeWidthPx) {
        this.style = Math.max(0, Math.min(3, style));
        stroke = widthDp == 0 ? Math.max(1f, nativeWidthPx)
                : Math.max(1f, Math.max(1, Math.min(4, widthDp)) * density);
        width = Math.max(1, Math.round(this.style == 0 ? stroke
                : this.style == 1 ? Math.max(3f * density, stroke)
                : this.style == 2 ? 6f * density + stroke : 8f * density));
        height = Math.max(1, Math.round(12f * density));
        paint.setColor(color);
        paint.setStrokeWidth(stroke);
    }

    void setColor(int color) {
        if (paint.getColor() == color) return;
        paint.setColor(color);
        invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        if (style == 1) {
            canvas.drawCircle(cx, cy, Math.min(bounds.width(), bounds.height()) / 2f, paint);
        } else if (style == 2) {
            float inset = stroke / 2f;
            canvas.drawLine(bounds.left + inset, bounds.bottom - inset,
                    bounds.right - inset, bounds.top + inset, paint);
        } else if (style == 3) {
            canvas.drawLine(bounds.left, cy, bounds.right, cy, paint);
        } else {
            canvas.drawRect(cx - stroke / 2f, bounds.top,
                    cx + stroke / 2f, bounds.bottom, paint);
        }
    }

    @Override public int getIntrinsicWidth() { return width; }
    @Override public int getIntrinsicHeight() { return height; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
