/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** SystemUI 本地禁用岛，不应由 ROM 同步成设备全局不支持；接口来源见 IslandCapabilityPublication。 */
final class IslandCapabilityPolicy {
    static final String DEVICE_CAPABILITY = "persist.sys.feature.island";
    private IslandCapabilityPolicy() { }

    static boolean isPublisher(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            if (("com.android.systemui.application.impl.SystemUIApplicationImpl".equals(frame.getClassName())
                    && "onCreate".equals(frame.getMethodName()))
                    || ("com.miui.systemui.MiuiVendorServices$1".equals(frame.getClassName())
                    && "onUserChanged".equals(frame.getMethodName()))) return true;
        }
        return false;
    }

    static String publishedValue(boolean localSuppression, Boolean nativeCapability,
                                 String key, String requested, boolean romPublisher) {
        // 原生能力未知时不猜 true；其他属性、调用方和值完全交回 ROM。
        if (!localSuppression || nativeCapability == null || !romPublisher
                || !DEVICE_CAPABILITY.equals(key)
                || !("0".equals(requested) || "1".equals(requested))) return requested;
        return nativeCapability ? "1" : "0";
    }
}
