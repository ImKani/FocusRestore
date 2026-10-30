/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 收起补偿的范围判断；协议来源见 SuppressedIslandLifecycle。 */
final class SuppressedIslandPolicy {
    private SuppressedIslandPolicy() { }

    static boolean active(boolean os4, boolean debug, boolean propertyDisabled,
                          boolean featureDisabled, boolean viewsBlocked, int controllerAddHooks) {
        // 插件卸载或未挂上实际 add 拦截时，不可宣称原生岛已经关闭。
        return os4 && viewsBlocked && controllerAddHooks > 0
                && (!debug || (propertyDisabled && featureDisabled));
    }

    static boolean overridesQuery(boolean active, String method, String requestedPackage) {
        return active && "getIslandExpanded".equals(method)
                && "com.miui.voiceassist".equals(requestedPackage);
    }
}
