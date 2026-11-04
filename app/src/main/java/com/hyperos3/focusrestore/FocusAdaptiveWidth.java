/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 只处理模块自己构造的图标+文字；不猜测原生chips的剩余宽度，不修改应用RemoteViews。 */
final class FocusAdaptiveWidth {
    static boolean iconOnly(boolean enabled,boolean hasIcon,int available,int inset,int minimumText) {
        return enabled && hasIcon && available > 0 && available != Integer.MAX_VALUE
                && available - Math.max(0,inset) < Math.max(1,minimumText);
    }
}
