/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** OS4独立焦点的顺序间距和正文视口；坐标均相对宿主的起始边。 */
final class FocusContentGeometry {
    private FocusContentGeometry() { }

    static int focusStart(int timeGap, int dividerWidth, int focusGap) {
        return timeGap + dividerWidth + focusGap;
    }

    static int textStart(int focusStart, int iconWidth, int iconGap) {
        return focusStart + iconWidth + iconGap;
    }

    static boolean outsideTextViewport(float x, int hostWidth, int displayWidth,
            int textStart, boolean rtl) {
        int boundary = textClipBoundary(hostWidth, textStart, rtl);
        return rtl ? x < hostWidth - displayWidth || x >= boundary
                : x < boundary || x >= displayWidth;
    }

    static int textClipBoundary(int hostWidth, int textStart, boolean rtl) {
        // 分隔线负间距只改变整组位置，正文仍从自己的起始位置裁切；允许边界在宿主外。
        return rtl ? hostWidth - textStart : textStart;
    }
}
