/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

/** Pure original-Focus precedence rules shared by the Hook and unit tests. */
final class FocusPriorityPolicy {
    private FocusPriorityPolicy() {
    }

    static boolean needsClockTimerFallback(boolean hasClockTimer, boolean hasBarRemoteViews,
                                           boolean hasTicker) {
        // Focus 标记/AOD/展开卡片不能替代状态栏内容；有原生状态栏内容时仍优先原生。
        return hasClockTimer && !hasBarRemoteViews && !hasTicker;
    }

    static boolean shouldConvertIsland(boolean originalFocus, boolean whitelisted,
                                       boolean smsVerification, boolean clockTimerFallback) {
        return !originalFocus || whitelisted || smsVerification || clockTimerFallback;
    }

    static boolean hasExplicitFocusData(boolean explicitFocus, boolean hasMainRemoteViews,
                                        boolean hasBarRemoteViews, boolean hasTicker,
                                        boolean hasIslandParam) {
        return explicitFocus || hasMainRemoteViews || hasBarRemoteViews
                || (hasTicker && !hasIslandParam);
    }

    static boolean isOriginalFocus(boolean originalFocusField, boolean explicitFocus,
                                   boolean hasMainRemoteViews, boolean hasBarRemoteViews,
                                   boolean hasTicker, boolean hasIslandParam) {
        return originalFocusField || hasExplicitFocusData(explicitFocus, hasMainRemoteViews,
                hasBarRemoteViews, hasTicker, hasIslandParam);
    }
}
