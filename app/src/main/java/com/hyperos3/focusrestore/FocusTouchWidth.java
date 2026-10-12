/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 点击、长按、滑动和收起后的恢复区共用宽度；实际布局仍受父容器约束。 */
final class FocusTouchWidth {
    private FocusTouchWidth() { }

    static int dp(boolean custom, boolean landscape, int portraitDp, int landscapeDp) {
        return custom ? (landscape ? landscapeDp : portraitDp) : 0;
    }

    static int measured(int naturalWidth, int touchWidth, int contentLimit, int availableWidth) {
        // 显示宽度先裁切；自定义仅补充空白触控区，不改变正文的显示上限。
        return Math.max(1, Math.min(Math.max(Math.min(naturalWidth, contentLimit), touchWidth),
                availableWidth));
    }

    static int recovery(int customWidth, int previousWidth, int fallbackWidth) {
        return customWidth > 0 ? customWidth : (previousWidth > 0 ? previousWidth : fallbackWidth);
    }
}
