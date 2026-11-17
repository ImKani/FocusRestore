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

    /** 单色解析岛图统一跟随状态栏前景，多色素材保留原色和内部细节。 */
    static boolean tint(int[] pixels, int size, int[] bounds, boolean islandIcon, int color) {
        if (islandIcon && !isMonochrome(pixels, size, bounds)) return false;
        for (int index = 0; index < pixels.length; index++) pixels[index] = recolor(pixels[index], color);
        return true;
    }

    private static boolean isMonochrome(int[] pixels, int size, int[] bounds) {
        if (pixels == null || size <= 0 || (long) size * size != pixels.length
                || bounds == null || bounds.length != 4) return false;
        int left = Math.max(0, bounds[0]);
        int top = Math.max(0, bounds[1]);
        int right = Math.min(size, bounds[2]);
        int bottom = Math.min(size, bounds[3]);
        boolean foreground = false;
        int minRed = 255, minGreen = 255, minBlue = 255;
        int maxRed = 0, maxGreen = 0, maxBlue = 0;
        // 只检查原图绘制区域，模块的安全边距不参与素材分类。
        // 单色不要求透明底；带底色和图案的多色素材不整幅填色，避免内部图案变成黑块。
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int pixel = pixels[y * size + x];
                int alpha = pixel >>> 24;
                if (alpha < 128) continue; // 忽略低覆盖率抗锯齿的 RGB 舍入噪声。
                foreground = true;
                int red = (pixel >>> 16) & 255;
                int green = (pixel >>> 8) & 255;
                int blue = pixel & 255;
                minRed = Math.min(minRed, red); maxRed = Math.max(maxRed, red);
                minGreen = Math.min(minGreen, green); maxGreen = Math.max(maxGreen, green);
                minBlue = Math.min(minBlue, blue); maxBlue = Math.max(maxBlue, blue);
                if (maxRed - minRed > 24 || maxGreen - minGreen > 24 || maxBlue - minBlue > 24) {
                    return false;
                }
            }
        }
        return foreground;
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