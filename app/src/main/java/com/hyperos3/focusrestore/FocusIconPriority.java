/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 图标来源顺序；设备图标在调用此策略前独立处理。 */
final class FocusIconPriority {
    static final int APP = 0;
    static final int FOCUS = 1;
    static final int SMALL = 2;

    // 0～2 保留已发布的持久值语义；UI 与 Hook 共用完整排列，避免回退顺序分叉。
    private static final int[][] ORDERS = {
            {APP, FOCUS, SMALL}, {FOCUS, APP, SMALL}, {SMALL, APP, FOCUS},
            {APP, SMALL, FOCUS}, {FOCUS, SMALL, APP}, {SMALL, FOCUS, APP}
    };

    static int[] order(int priority) {
        return ORDERS[FocusRestoreSettings.normalizeIconPriority(priority)].clone();
    }

    static int indexOf(int[] order) {
        for (int priority = 0; priority < ORDERS.length; priority++) {
            if (java.util.Arrays.equals(ORDERS[priority], order)) return priority;
        }
        return -1;
    }

    static boolean smallFirst(int priority) {
        return ORDERS[FocusRestoreSettings.normalizeIconPriority(priority)][0] == SMALL;
    }

    private FocusIconPriority() { }
}
