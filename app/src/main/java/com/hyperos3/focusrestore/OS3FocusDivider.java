/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import java.util.function.Supplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 来源：作者授权的 Xiaomi SystemUI OS3 16.03.251211.r/code202501210，
 * MT APK MCP http://192.168.31.21:8787/mcp，status_bar.xml 与 DividerLine。
 * 原作者/版权所有者为小米及 Android 上游，ROM 私有许可未确认；仅依接口事实独立实现。
 * view_line 是 focused_parent 的子项，FocusedNotifPromptView 的兄弟；calculateSize
 * 会重设 line_width/line_height/起始间距，onDraw 用 mDrawPaint 绘制，不能换背景代替。
 */
final class OS3FocusDivider {
    interface Logger { void error(String stage, Throwable failure); }
    private final Supplier<HookSettings> settings;
    private final Logger logger;
    private boolean drawFailureLogged;
    private static final String LINE_STATE = "focusRestoreDividerOriginal";
    private static final String PROMPT_STATE = "focusRestoreDividerPadding";
    private static final String DECORATION = "focusRestoreDividerDecoration";
    private static final String CLIPPING = "focusRestoreDividerClipping";

    OS3FocusDivider(Supplier<HookSettings> settings, Logger logger) {
        this.settings = settings;
        this.logger = logger;
    }

    void install(ClassLoader loader) {
        try {
            Class<?> line = FocusReflection.findClass("com.android.systemui.statusbar.phone.DividerLine", loader);
            XC_MethodHook layout = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) applyLine((View) param.thisObject);
                }
            };
            XposedBridge.hookAllMethods(line, "calculateSize", layout);
            XposedBridge.hookAllMethods(line, "onAttachedToWindow", layout);
            XposedHelpers.findAndHookMethod(line, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    View view = (View) param.thisObject;
                    if (!isFocusLine(view)) return;
                    HookSettings current = settings.get();
                    if (!current.showFocusDivider) { param.setResult(null); return; }
                    if (current.focusDividerStyle == 0 && current.focusDividerWidthDp == 0) return;
                    Object value = XposedHelpers.getAdditionalInstanceField(view, DECORATION);
                    if (!(value instanceof FocusDividerDrawable)) return;
                    try {
                        Paint nativePaint = (Paint) XposedHelpers.getObjectField(view, "mDrawPaint");
                        FocusDividerDrawable decoration = (FocusDividerDrawable) value;
                        decoration.setColor(nativePaint.getColor());
                        decoration.setAlpha(nativePaint.getAlpha());
                        decoration.setBounds(0, 0, view.getWidth(), view.getHeight());
                        decoration.draw((Canvas) param.args[0]);
                        param.setResult(null);
                    } catch (Throwable failure) {
                        // 保留原生绘制作为降级，失败诊断只记一次，避免按帧刷日志。
                        if (!drawFailureLogged) {
                            drawFailureLogged = true;
                            logger.error("OS3 divider draw; keeping native drawing", failure);
                        }
                    }
                }
            });
        } catch (Throwable failure) { logger.error("OS3 divider hooks unavailable; keeping native divider", failure); }
    }

    void apply(View prompt) {
        if (!(prompt.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) prompt.getParent();
        int id = prompt.getResources().getIdentifier("view_line", "id", "com.android.systemui");
        View line = id == 0 ? null : parent.findViewById(id);
        if (line != null && "com.android.systemui.statusbar.phone.DividerLine".equals(line.getClass().getName())) {
            applyLine(line);
            applyPromptPadding(prompt);
        }
    }

    private static boolean isFocusLine(View line) {
        int id = line.getResources().getIdentifier("view_line", "id", "com.android.systemui");
        int parentId = line.getResources().getIdentifier("focused_parent", "id", "com.android.systemui");
        return id != 0 && line.getId() == id && parentId != 0
                && line.getParent() instanceof View && ((View) line.getParent()).getId() == parentId;
    }

    private void applyLine(View line) {
        if (!isFocusLine(line)) return;
        ViewGroup.LayoutParams raw = line.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        try {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) raw;
            Original original = original(line, LINE_STATE, params.width, params.height,
                    params.getMarginStart(), params.getMarginEnd());
            HookSettings current = settings.get();
            float density = line.getResources().getDisplayMetrics().density;
            int nativeWidth = dimension(line, "line_width", original.scaled(original.width, density));
            int height = dimension(line, "line_height", original.scaled(original.height, density));
            int start = current.focusDividerStartDp == FocusRestoreSettings.AUTO_FOCUS_DIVIDER_SPACING_DP
                    ? dimension(line, "status_bar_notification_padding_end", original.scaled(original.start, density))
                    : Math.round(current.focusDividerStartDp * density);
            int end = current.focusDividerEndDp == FocusRestoreSettings.AUTO_FOCUS_DIVIDER_SPACING_DP
                    ? original.scaled(original.end, density) : Math.round(current.focusDividerEndDp * density);
            int width = nativeWidth;
            if (current.focusDividerStyle != 0 || current.focusDividerWidthDp != 0) {
                FocusDividerDrawable decoration = new FocusDividerDrawable(density,
                        current.focusDividerStyle, current.focusDividerWidthDp, 0, nativeWidth);
                width = decoration.getIntrinsicWidth();
                XposedHelpers.setAdditionalInstanceField(line, DECORATION, decoration);
            } else {
                XposedHelpers.removeAdditionalInstanceField(line, DECORATION);
            }
            // 不接管ROM的visibility/alpha/动画：焦点消失仍由系统隐藏；关闭装饰只收掉占位。
            if (!current.showFocusDivider) { width = 0; start = 0; end = 0; }
            if (params.width != width || params.height != height
                    || params.getMarginStart() != start || params.getMarginEnd() != end) {
                params.width = width;
                params.height = height;
                params.setMarginStart(start);
                params.setMarginEnd(end);
                line.setLayoutParams(params);
            }
            line.invalidate();
            if (line.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) line.getParent();
                applyParentClipping(parent, current.showFocusDivider && (start < 0 || end < 0));
                for (int index = 0; index < parent.getChildCount(); index++) {
                    View child = parent.getChildAt(index);
                    if ("com.android.systemui.statusbar.phone.FocusedNotifPromptView".equals(child.getClass().getName())) {
                        applyPromptPadding(child);
                        break;
                    }
                }
            }
        } catch (Throwable failure) { logger.error("OS3 divider layout; keeping native layout", failure); }
    }

    private static void applyParentClipping(ViewGroup parent, boolean overlap) {
        Object saved = XposedHelpers.getAdditionalInstanceField(parent, CLIPPING);
        boolean[] original = saved instanceof boolean[] ? (boolean[]) saved : null;
        if (overlap) {
            if (original == null) {
                original = new boolean[]{parent.getClipChildren(), parent.getClipToPadding()};
                XposedHelpers.setAdditionalInstanceField(parent, CLIPPING, original);
            }
            // 用户明确允许负间距重叠，不让focused_parent裁掉移向时间侧的竖线。
            parent.setClipChildren(false);
            parent.setClipToPadding(false);
        } else if (original != null) {
            parent.setClipChildren(original[0]);
            parent.setClipToPadding(original[1]);
            XposedHelpers.removeAdditionalInstanceField(parent, CLIPPING);
        }
    }

    private void applyPromptPadding(View prompt) {
        Original original = original(prompt, PROMPT_STATE, 0, 0,
                prompt.getPaddingStart(), prompt.getPaddingEnd());
        HookSettings current = settings.get();
        float density = prompt.getResources().getDisplayMetrics().density;
        // ROM已有6dp paddingStart；自定义后移到竖线marginEnd，只保留一次右侧间距。
        int start = current.showFocusDivider && current.focusDividerEndDp == FocusRestoreSettings.AUTO_FOCUS_DIVIDER_SPACING_DP
                ? dimension(prompt, "focused_notif_view_padding_start", original.scaled(original.start, density)) : 0;
        int end = original.scaled(original.end, density);
        if (prompt.getPaddingStart() != start || prompt.getPaddingEnd() != end) {
            prompt.setPaddingRelative(start, prompt.getPaddingTop(), end, prompt.getPaddingBottom());
        }
    }

    private static Original original(View view, String key, int width, int height, int start, int end) {
        Object saved = XposedHelpers.getAdditionalInstanceField(view, key);
        if (saved instanceof Original) return (Original) saved;
        Original original = new Original(width, height, start, end,
                view.getResources().getDisplayMetrics().density);
        // 值只存标量，不强引用弱键View，避免additionalFields反向持有已释放的状态栏。
        XposedHelpers.setAdditionalInstanceField(view, key, original);
        return original;
    }

    private static int dimension(View view, String name, int fallback) {
        int id = view.getResources().getIdentifier(name, "dimen", "com.android.systemui");
        return id == 0 ? fallback : view.getResources().getDimensionPixelSize(id);
    }

    private static final class Original {
        final int width, height, start, end;
        final float density;
        Original(int width, int height, int start, int end, float density) {
            this.width = width; this.height = height; this.start = start; this.end = end;
            this.density = density;
        }
        int scaled(int value, float newDensity) { return Math.round(value * newDensity / density); }
    }
}
