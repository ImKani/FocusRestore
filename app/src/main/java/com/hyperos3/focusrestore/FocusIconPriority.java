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

    static int[] order(int priority) {
        switch (priority) {
            case FocusRestoreSettings.ICON_FOCUS_FIRST: return new int[]{FOCUS, APP, SMALL};
            case FocusRestoreSettings.ICON_SMALL_FIRST: return new int[]{SMALL, APP, FOCUS};
            default: return new int[]{APP, FOCUS, SMALL};
        }
    }

    private FocusIconPriority() { }
}
