/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Suppresses only the ROM's scroll-to-top side effect for gestures starting over visible Focus. */
final class FocusScrollToTopGuard {
    interface FocusRegion { boolean contains(float rawX, float rawY, int displayId); }
    interface Logger {
        void log(String message);
        void error(String stage, Throwable error);
    }

    private final FocusRegion region;
    private final Logger logger;
    private final FocusTapSuppression snapshots = new FocusTapSuppression();
    private int suppressionLogs;

    FocusScrollToTopGuard(FocusRegion region, Logger logger) {
        this.region = region;
        this.logger = logger;
    }

    void install(ClassLoader loader, boolean os4) {
        // These feeds precede native GestureDetector callbacks and normal child dispatch.
        // Observing them does not consume or alter status bar touches, including shade drags.
        int feeds = observe(loader, "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                "onInterceptTouchEvent", MotionEvent.class);
        feeds += observe(loader, "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                "onTouchEvent", MotionEvent.class);
        feeds += observe(loader, "com.android.systemui.shade.QuickSettingsControllerImpl",
                "handleTouch", MotionEvent.class, boolean.class, boolean.class);
        String owner = (os4 ? "com.android.systemui.statusbar.gesture."
                : "com.android.systemui.shade.")
                + "StatusBarLongPressGestureDetector$gestureDetector$1";
        int taps = blockTap(loader, owner, "onSingleTapConfirmed");
        taps += blockTap(loader, owner, "onDoubleTapEvent");
        logger.log("focus scroll-to-top guard OS" + (os4 ? "4" : "3")
                + " feeds=" + feeds + " callbacks=" + taps + " scope=visible-focus-down-gesture");
    }

    private int observe(ClassLoader loader, String owner, String method, Class<?>... args) {
        try {
            Class<?> type = Class.forName(owner, false, loader);
            Method target = type.getDeclaredMethod(method, args);
            target.setAccessible(true);
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.args[0] instanceof MotionEvent)) return;
                    MotionEvent event = (MotionEvent) param.args[0];
                    if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
                    try {
                        int display = displayId(event);
                        long now = SystemClock.uptimeMillis();
                        synchronized (snapshots) {
                            if (snapshots.hasSnapshot(event.getDownTime(), event.getDeviceId(), display, now)) return;
                            boolean hit = region.contains(event.getRawX(), event.getRawY(), display);
                            snapshots.record(event.getDownTime(), event.getDeviceId(), display, hit, now);
                        }
                    } catch (Throwable error) { logger.error("focus scroll gesture observation", error); }
                }
            });
            return 1;
        } catch (Throwable error) {
            logger.error("focus scroll observer unavailable " + owner + "." + method, error);
            return 0;
        }
    }

    private int blockTap(ClassLoader loader, String owner, String method) {
        try {
            Class<?> type = Class.forName(owner, false, loader);
            Method target = type.getDeclaredMethod(method, MotionEvent.class);
            if (target.getReturnType() != boolean.class) throw new NoSuchMethodException("boolean tap expected");
            target.setAccessible(true);
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.args[0] instanceof MotionEvent)) return;
                    MotionEvent event = (MotionEvent) param.args[0];
                    try {
                        boolean blocked;
                        synchronized (snapshots) {
                            blocked = snapshots.blocks(event.getDownTime(), event.getDeviceId(),
                                    displayId(event), SystemClock.uptimeMillis());
                        }
                        if (!blocked) return;
                        // Verified OS3/OS4 methods only call clickTool.invokeInputManager and return
                        // true. Skip that effect; native focus tap and shade dispatch still run.
                        param.setResult(true);
                        if (suppressionLogs++ < 20) logger.log("focus scroll-to-top suppressed callback="
                                + method + " downTime=" + event.getDownTime());
                    } catch (Throwable error) { logger.error("focus scroll callback guard", error); }
                }
            });
            return 1;
        } catch (Throwable error) {
            logger.error("focus scroll callback unavailable " + owner + "." + method, error);
            return 0;
        }
    }

    private static int displayId(MotionEvent event) {
        // Hidden on some compile SDKs, but present on the target ROMs' InputEvent.
        try { return ((Number) XposedHelpers.callMethod(event, "getDisplayId")).intValue(); }
        catch (Throwable ignored) { return -1; }
    }

    static boolean containsVisible(View view, float rawX, float rawY, int displayId) {
        if (view == null || !view.isAttachedToWindow() || !view.isShown()
                || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        Display display = view.getDisplay();
        if (display == null || (displayId >= 0 && display.getDisplayId() != displayId)) return false;
        for (View current = view; current != null;) {
            if (current.getAlpha() <= 0f) return false;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        Rect visible = new Rect();
        if (!view.getLocalVisibleRect(visible)) return false;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        // getLocalVisibleRect is expressed in this View's scrolled content coordinates.
        float x = rawX - location[0] + view.getScrollX();
        float y = rawY - location[1] + view.getScrollY();
        return x >= visible.left && x < visible.right && y >= visible.top && y < visible.bottom;
    }
}