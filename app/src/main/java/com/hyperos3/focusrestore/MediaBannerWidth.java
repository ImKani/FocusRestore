/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 媒体横幅沿通知面板限宽；缺资源时按屏幕短边兜底，避免横屏拉满长边。 */
final class MediaBannerWidth {
    private MediaBannerWidth() { }

    static int resolve(int screenWidth, int screenHeight, int panelWidth, int sidePadding) {
        int width = Math.max(1, screenWidth);
        int shortEdge = screenHeight > 0 ? Math.min(width, screenHeight) : width;
        int panel = panelWidth > 0 ? Math.min(panelWidth, shortEdge) : shortEdge;
        // 异常资源不生成零宽或负宽；最终仍由窗口按安全区域裁定。
        long content = (long) panel - 2L * Math.max(0, sidePadding);
        return (int) Math.max(1L, content);
    }
}
