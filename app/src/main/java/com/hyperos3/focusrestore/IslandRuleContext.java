/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 已由外层准备的四字段字符串视图；null 表示未知，不在此读取或转换通知数据。 */
public final class IslandRuleContext {
    public final String packageName;
    public final String protocolVersion;
    public final String business;
    public final String scene;

    public IslandRuleContext(String packageName, String protocolVersion, String business,
            String scene) {
        this.packageName = packageName;
        this.protocolVersion = protocolVersion;
        this.business = business;
        this.scene = scene;
    }
}
