/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 跑马灯只消费当前内容的有效布局；同一几何不重复启动，清理后旧回调失效。 */
final class FocusMarqueeLayout {
    private Object content;
    private int naturalWidth;
    private int displayWidth;
    private int contentInset;
    private int hostWidth;
    private long generation;
    private boolean valid;

    long update(Object target, int natural, int display, int inset, int host) {
        if (target == null || natural < 0 || display <= 0 || host <= 0) {
            clear();
            return 0L;
        }
        if (valid && content == target && naturalWidth == natural && displayWidth == display
                && contentInset == inset && hostWidth == host) return 0L;
        content = target;
        naturalWidth = natural;
        displayWidth = display;
        contentInset = inset;
        hostWidth = host;
        valid = true;
        return ++generation;
    }

    boolean isCurrent(Object target, long revision) {
        return valid && content == target && generation == revision;
    }

    void clear() {
        content = null;
        valid = false;
        ++generation;
    }
}
