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

    /** 近似单色解析岛图统一跟随状态栏前景，多色素材保留原色和内部细节。 */
    static boolean tint(int[] pixels, int size, int[] bounds, boolean islandIcon, int color) {
        return tint(pixels, color, islandIcon ? analyze(pixels, size, bounds) : null);
    }

    static boolean tint(int[] pixels, int color, Analysis analysis) {
        if (analysis != null && !analysis.monochrome) return false;
        for (int index = 0; index < pixels.length; index++) pixels[index] = recolor(pixels[index], color);
        return true;
    }

    /** 只统计本次加载的绘制区域，不把模块安全边距或动态资源名当作素材身份。 */
    static Analysis analyze(int[] pixels, int size, int[] bounds) {
        if (pixels == null || size <= 0 || (long) size * size != pixels.length
                || bounds == null || bounds.length != 4) return Analysis.empty();
        int left = Math.max(0, bounds[0]);
        int top = Math.max(0, bounds[1]);
        int right = Math.min(size, bounds[2]);
        int bottom = Math.min(size, bounds[3]);
        if (left >= right || top >= bottom) return Analysis.empty();
        int total = (right - left) * (bottom - top);
        int[] histogram = new int[4096];
        int valid = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int pixel = pixels[y * size + x];
                if ((pixel >>> 24) < 128) continue;
                int bucket = (((pixel >>> 16) & 255) >>> 4) << 8
                        | (((pixel >>> 8) & 255) >>> 4) << 4 | (pixel & 255) >>> 4;
                histogram[bucket]++;
                valid++;
            }
        }
        int best = 0, bestCount = 0;
        for (int bucket = 0; bucket < histogram.length; bucket++) {
            if (histogram[bucket] > bestCount) {
                best = bucket;
                bestCount = histogram[bucket];
            }
        }
        // >>4 对应宽度16的桶，中心为桶号*16+8；*17+8会把末桶推到255之外。
        int redCenter = ((best >>> 8) & 15) * 16 + 8;
        int greenCenter = ((best >>> 4) & 15) * 16 + 8;
        int blueCenter = (best & 15) * 16 + 8;
        int near = 0, maxDistanceSquared = 0;
        long redSum = 0, greenSum = 0, blueSum = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int pixel = pixels[y * size + x];
                if ((pixel >>> 24) < 128) continue;
                int red = (pixel >>> 16) & 255;
                int green = (pixel >>> 8) & 255;
                int blue = pixel & 255;
                int dr = red - redCenter, dg = green - greenCenter, db = blue - blueCenter;
                int distanceSquared = dr * dr + dg * dg + db * db;
                maxDistanceSquared = Math.max(maxDistanceSquared, distanceSquared);
                if (distanceSquared <= 64 * 64) {
                    redSum += red; greenSum += green; blueSum += blue;
                    near++;
                }
            }
        }
        int average = near == 0 ? 0 : (int) (redSum / near) << 16
                | (int) (greenSum / near) << 8 | (int) (blueSum / near);
        // 采用整数交叉相乘，精确保留5%有效覆盖率和90%近邻占比边界。
        boolean monochrome = valid > 0 && (long) valid * 20 >= total
                && (long) near * 10 >= (long) valid * 9;
        return new Analysis(total, valid, bestCount, near, average, maxDistanceSquared, monochrome);
    }

    static final class Analysis {
        final int total, valid, bestCount, near, average, maxDistanceSquared;
        final boolean monochrome;

        Analysis(int total, int valid, int bestCount, int near, int average,
                 int maxDistanceSquared, boolean monochrome) {
            this.total = total; this.valid = valid; this.bestCount = bestCount;
            this.near = near; this.average = average;
            this.maxDistanceSquared = maxDistanceSquared; this.monochrome = monochrome;
        }

        static Analysis empty() { return new Analysis(0, 0, 0, 0, 0, 0, false); }

        String diagnostic() {
            String reason = total == 0 ? "invalid-bounds" : valid == 0 ? "no-foreground"
                    : (long) valid * 20 < total ? "coverage-below-5pct"
                    : !monochrome ? "outside-dominant-cluster" : "dominant-cluster";
            return String.format(java.util.Locale.ROOT,
                    "valid=%d total=%d bestCount=%d near=%d nearRatio=%.4f avg=#%06X maxDist=%.2f coverage=%.4f monochrome=%s reason=%s",
                    valid, total, bestCount, near, valid == 0 ? 0d : (double) near / valid,
                    average, Math.sqrt(maxDistanceSquared), total == 0 ? 0d : (double) valid / total,
                    monochrome, reason);
        }
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