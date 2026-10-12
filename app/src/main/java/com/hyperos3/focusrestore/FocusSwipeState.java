/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 单指手势先锁定方向；取消与多指不能提交切换，短距离滑动也不能触发点击。 */
final class FocusSwipeState {
    private float startX, startY;
    private boolean tracking, horizontal, cancelled;

    void begin(float x, float y) {
        reset();
        startX = x;
        startY = y;
        tracking = true;
    }

    boolean move(float x, float y, float slop) {
        if (!tracking || cancelled) return horizontal;
        if (!horizontal && Math.max(Math.abs(x - startX), Math.abs(y - startY)) > slop) {
            if (Math.abs(x - startX) > Math.abs(y - startY)) horizontal = true;
            else cancelled = true;
        }
        return horizontal;
    }

    int finish(float x, float y, float slop, float threshold) {
        move(x, y, slop);
        int direction = !cancelled && horizontal && Math.abs(x - startX) > threshold
                ? (x > startX ? 1 : -1) : 0;
        reset();
        return direction;
    }

    void cancel() { cancelled = true; }
    boolean tracking() { return tracking && !cancelled; }
    boolean horizontal() { return horizontal; }
    void reset() { tracking = false; horizontal = false; cancelled = false; }
}
