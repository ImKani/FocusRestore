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
    private boolean swallowRelease;

    void begin(float x, float y) {
        downX = x;
        downY = y;
        pending = true;
        consumed = false;
        swallowRelease = false;
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
        swallowRelease = true;
        return true;
    }

    /**
     * 长按成立后取走"抬手要吞掉"标记。
     *
     * <p>这个标记刻意在 {@link #reset()} 之外单独存在：ACTION_UP 到达时 {@code onEvent} 会先读到
     * {@code consumed}，随后立刻 reset；钩子是在 onEvent 返回之后才判断要不要消费事件的，所以
     * 只有把标记留到那一刻，抬手才真的被吞掉，ROM/子视图的短按才会被拦住。
     */
    boolean consumeRelease() {
        if (!swallowRelease) return false;
        swallowRelease = false;
        return true;
    }

    void reset() {
        pending = false;
        consumed = false;
        swallowRelease = false;
    }
}
