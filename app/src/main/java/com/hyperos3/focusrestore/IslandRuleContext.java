/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 有界通知匹配上下文。null 表示未知。 */
public final class IslandRuleContext {
    public final String packageName;
    public final String protocolVersion;
    public final String business;
    public final String scene;
    final IslandNotificationType notificationType;

    public IslandRuleContext(String packageName, String protocolVersion, String business, String scene) {
        this(packageName, protocolVersion, business, scene, null);
    }

    IslandRuleContext(String packageName, String protocolVersion, String business,
            String scene, IslandNotificationType notificationType) {
        this.packageName = packageName;
        this.protocolVersion = protocolVersion;
        this.business = business;
        this.scene = scene;
        this.notificationType = notificationType;
    }
}
