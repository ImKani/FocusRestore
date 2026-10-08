/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 单指长按的状态；移动取消后仍保留已消费标记，防止松手再触发短按。 */
final class FocusLongPressState {
    private float downX;
    private float downY;
    private boolean pending;
    private boolean consumed;

    void begin(float x, float y) {
        downX = x;
        downY = y;
        pending = true;
        consumed = false;
    }

    void move(float x, float y, int slop) {
        if (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop) pending = false;
    }

    void cancelPending() { pending = false; }
    boolean pending() { return pending; }
    boolean consumed() { return consumed; }

    boolean fire() {
        if (!pending) return false;
        pending = false;
        consumed = true;
        return true;
    }

    void reset() {
        pending = false;
        consumed = false;
    }
}
