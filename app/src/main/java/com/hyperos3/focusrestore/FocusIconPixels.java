/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

/** 与 Android 无关的图标像素与等比尺寸规则。 */
final class FocusIconPixels {
    private FocusIconPixels() { }

    static int recolor(int pixel, int color) {
        // 只换 RGB，不扩张 alpha；透明孔洞和抗锯齿边缘必须保留。
        return (pixel & 0xff000000) | (color & 0x00ffffff);
    }

    static int[] bounds(int size, int padding, int width, int height) {
        int available = Math.max(1, size - 2 * padding);
        if (width <= 0 || height <= 0) width = height = available;
        float scale = Math.min((float) available / width, (float) available / height);
        int fittedWidth = Math.max(1, Math.round(width * scale));
        int fittedHeight = Math.max(1, Math.round(height * scale));
        int left = (size - fittedWidth) / 2;
        int top = (size - fittedHeight) / 2;
        return new int[]{left, top, left + fittedWidth, top + fittedHeight};
    }
}