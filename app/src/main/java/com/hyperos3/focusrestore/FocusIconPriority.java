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
    static final int ISLAND = 3;
    static final int DEFAULT_ORDER = 6;

    // 0～2 保留已发布的持久值语义；UI 与 Hook 共用完整排列，避免回退顺序分叉。
    private static final int[][] LEGACY_ORDERS = {
            {APP, FOCUS, SMALL}, {FOCUS, APP, SMALL}, {SMALL, APP, FOCUS},
            {APP, SMALL, FOCUS}, {FOCUS, SMALL, APP}, {SMALL, FOCUS, APP}
    };

    // 0～5 永久保留旧三来源编码；6～29 为四来源编码，不复用旧数值语义。
    private static final int[][] ORDERS = createOrders();

    private static int[][] createOrders() {
        int[][] orders = new int[30][];
        System.arraycopy(LEGACY_ORDERS, 0, orders, 0, 6);
        orders[DEFAULT_ORDER] = new int[]{APP, FOCUS, ISLAND, SMALL};
        int next = DEFAULT_ORDER + 1;
        for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++)
            for (int c = 0; c < 4; c++) for (int d = 0; d < 4; d++) {
                if (a == b || a == c || a == d || b == c || b == d || c == d) continue;
                int[] order = {a, b, c, d};
                if (!java.util.Arrays.equals(order, orders[DEFAULT_ORDER])) orders[next++] = order;
            }
        return orders;
    }

    static int[] order(int priority) {
        return ORDERS[FocusRestoreSettings.normalizeIconPriority(priority)].clone();
    }

    /** 仅迁移旧设置：原开关开启置首、关闭置末，之后四来源一律参与排序。 */
    static int migrate(int priority, boolean previouslyEnabled) {
        int[] saved = order(priority);
        if (saved.length == 4) return FocusRestoreSettings.normalizeIconPriority(priority);
        return indexOf(previouslyEnabled ? new int[]{ISLAND, saved[0], saved[1], saved[2]}
                : new int[]{saved[0], saved[1], saved[2], ISLAND});
    }

    static int indexOf(int[] order) {
        for (int priority = 0; priority < ORDERS.length; priority++) {
            if (java.util.Arrays.equals(ORDERS[priority], order)) return priority;
        }
        return -1;
    }

    static boolean smallFirst(int priority) {
        for (int source : order(priority)) if (source != ISLAND) return source == SMALL;
        return false;
    }

    private FocusIconPriority() { }
}