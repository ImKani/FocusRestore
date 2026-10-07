/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.content.Context;
import android.content.ContextWrapper;

import java.util.IdentityHashMap;
import java.util.Map;

/** 有界查找能提供SystemUI焦点横幅几何与背景资源的外观Context。 */
final class NativeLegacyAppearanceContext {
    private static final int MAX_BASE_DEPTH = 8;

    private NativeLegacyAppearanceContext() { }

    static Result resolve(Context anchor, Context row, Context plugin) {
        Map<Context, Boolean> seen = new IdentityHashMap<>();
        Result result = find(anchor, "anchor", 0, seen);
        if (result != null) return result;
        result = find(row, "row", 0, seen);
        if (result != null) return result;
        result = find(plugin, "plugin", 0, seen);
        if (result != null) return result;
        throw new IllegalStateException("no context resolves SystemUI notification appearance resources");
    }

    private static Result find(Context candidate, String origin, int depth,
                               Map<Context, Boolean> seen) {
        Context current = candidate;
        int baseDepth = depth;
        for (int index = 0; current != null && index <= MAX_BASE_DEPTH; index++) {
            if (seen.put(current, Boolean.TRUE) != null) return null;
            Context application = current.getApplicationContext();
            Context effective = application == null ? current : application;
            if (supportsAppearance(effective)) {
                return new Result(effective, origin, baseDepth);
            }
            if (!(current instanceof ContextWrapper) || index == MAX_BASE_DEPTH) return null;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) return null;
            current = base;
            baseDepth++;
        }
        return null;
    }

    private static boolean supportsAppearance(Context context) {
        try {
            String packageName = "com.android.systemui";
            int width = context.getResources().getIdentifier(
                    "notification_panel_width", "dimen", packageName);
            int padding = context.getResources().getIdentifier(
                    "notification_side_paddings", "dimen", packageName);
            int radius = context.getResources().getIdentifier(
                    "notification_item_bg_radius", "dimen", packageName);
            int radiusPx = radius == 0 ? 0 : context.getResources().getDimensionPixelSize(radius);
            if (radiusPx <= 0) {
                radius = context.getResources().getIdentifier(
                        "notification_corner_radius", "dimen", packageName);
                radiusPx = radius == 0 ? 0 : context.getResources().getDimensionPixelSize(radius);
            }
            if (radiusPx <= 0) return false;
            int focus = context.getResources().getIdentifier(
                    "notification_focus_item_bg", "drawable", packageName);
            int normal = context.getResources().getIdentifier(
                    "notification_item_bg", "drawable", packageName);
            int panelPx = width == 0 ? 0 : context.getResources().getDimensionPixelSize(width);
            int paddingPx = padding == 0 ? 0 : context.getResources().getDimensionPixelSize(padding);
            return focus != 0 && normal != 0
                    && (panelPx > 0 || context.getResources().getDisplayMetrics().widthPixels > 0)
                    && paddingPx >= 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static final class Result {
        final Context context;
        final String origin;
        final int baseDepth;

        Result(Context context, String origin, int baseDepth) {
            this.context = context;
            this.origin = origin;
            this.baseDepth = baseDepth;
        }
    }
}
