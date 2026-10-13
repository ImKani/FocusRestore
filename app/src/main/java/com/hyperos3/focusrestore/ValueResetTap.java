/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 数值标签双击必须连续命中同一控件；不排队回调、不保存View引用。 */
final class ValueResetTap {
    private int lastTargetId = -1;
    private long lastTapMillis = -1L;

    boolean click(int targetId, long nowMillis, int timeoutMillis) {
        boolean doubleTap = targetId == lastTargetId && lastTapMillis >= 0L
                && nowMillis >= lastTapMillis && timeoutMillis > 0
                && nowMillis - lastTapMillis <= timeoutMillis;
        if (doubleTap) {
            clear();
        } else {
            lastTargetId = targetId;
            lastTapMillis = nowMillis;
        }
        return doubleTap;
    }

    void clear() {
        lastTargetId = -1;
        lastTapMillis = -1L;
    }
}
