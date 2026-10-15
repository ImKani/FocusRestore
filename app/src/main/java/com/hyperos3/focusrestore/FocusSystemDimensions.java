/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;
import java.util.HashSet;
import java.util.Set;

/**
 * 系统焦点尺寸。来源：作者授权MT APK MCP http://192.168.31.21:8787/mcp，
 * Xiaomi SystemUI OS3 16.03.251211.r / OS4 17.03.260226.r；版权所有者小米及Android上游，
 * 私有资源许可未确认。仅使用资源名/尺寸事实独立实现，不复制ROM实现。
 * OS3 status_bar.xml的mContent起始margin引用focused_notif_content_start=3dp；
 * OS4保留同名3dp资源但已无该原生PromptView，模块重建焦点使用此资源基准。
 */
final class FocusSystemDimensions {
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final Set<String> REPORTED = new HashSet<>();

    private FocusSystemDimensions() { }

    static float iconTextGapDp(Context context, int hookMode) {
        return dimensionDp(context, "focused_notif_content_start", 3f, hookMode);
    }

    static int iconTextGapPx(Context context, int configuredDp, int hookMode) {
        float dp = configuredDp < 0 ? iconTextGapDp(context, hookMode) : configuredDp;
        return Math.max(0, Math.round(dp * context.getResources().getDisplayMetrics().density));
    }

    static float dimensionDp(Context context, String name, float fallback, int hookMode) {
        try {
            Resources resources = SYSTEM_UI.equals(context.getPackageName())
                    ? context.getResources()
                    : context.getPackageManager().getResourcesForApplication(SYSTEM_UI);
            int id = resources.getIdentifier(name, "dimen", SYSTEM_UI);
            if (id == 0) throw new Resources.NotFoundException(name);
            float density = resources.getDisplayMetrics().density;
            float dp = resources.getDimension(id) / density;
            if (Float.isNaN(dp) || Float.isInfinite(dp) || dp < 0f || density <= 0f) {
                throw new IllegalArgumentException("invalid dimension " + name);
            }
            return dp;
        } catch (Exception failure) {
            // 只记录资源能力降级，不按每次布局刷日志；既有合法自定义值不经此路径。
            String key = hookMode + ":" + name;
            synchronized (REPORTED) {
                if (REPORTED.add(key)) Log.w("HyperOS3FocusRestore", "system focus dimension unavailable"
                        + " mode=OS" + hookMode + " name=" + name
                        + "; using verified reference " + fallback + "dp", failure);
            }
            return fallback;
        }
    }
}
