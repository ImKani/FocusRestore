/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** OS4 私有颜色回调的参数合同，避免把对比色作为焦点文字色。 */
final class OS4DarkTintCallback {
    private OS4DarkTintCallback() { }

    static Integer normalTint(String methodName, Object[] args) {
        if (args == null || args.length < 3) return null;
        final int tintIndex;
        if ("onDarkChanged".equals(methodName)) {
            tintIndex = 2;
        } else if ("onDarkChangedWithContrast".equals(methodName)) {
            // 小米 SystemUI 17.03.260226.r，用户只读 APK MCP：
            // http://192.168.31.21:8787/mcp，DarkIconDispatcherImpl.addDarkReceiver /
            // applyIconTint：两个 int 依次为 mIconTint、mContrastTint。
            // 上述 ROM 私有接口许可未确认。
            tintIndex = 1;
        } else {
            return null;
        }
        return args[tintIndex] instanceof Integer ? (Integer) args[tintIndex] : null;
    }
}
