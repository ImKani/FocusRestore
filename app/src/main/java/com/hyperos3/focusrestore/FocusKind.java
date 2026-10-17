/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

/** 焦点通知来源分类，用于按类型隐藏状态栏时间等类型相关行为。 */
final class FocusKind {
    static final int NORMAL = 0;
    static final int MEDIA = 1;
    static final int DEVICE = 2;

    private FocusKind() {
    }
}
