/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.View;

import de.robv.android.xposed.XposedHelpers;

/** Owns a native time keeper for one private View tree; never registers a global SBN key. */
final class NativeFocusTimerSession {
    private final Context context;
    private final View root;
    private final StatusBarNotification bindingSbn;
    private final ClassLoader pluginLoader;
    private final NativeFocusTemplateRenderer.Logger logger;
    private final Handler handler;
    private final long deadline;
    private Object keeper;
    private boolean closed;

    static NativeFocusTimerSession attach(Context context, View root,
                                         StatusBarNotification bindingSbn,
                                         ClassLoader pluginLoader,
                                         NativeFocusTemplateRenderer.Logger logger) {
        NativeFocusTimerSession session = new NativeFocusTimerSession(
                context, root, bindingSbn, pluginLoader, logger);
        session.handler.post(session::tryBind);
        return session;
    }

    private NativeFocusTimerSession(Context context, View root, StatusBarNotification sbn,
                                    ClassLoader pluginLoader,
                                    NativeFocusTemplateRenderer.Logger logger) {
        this.context = context;
        this.root = root;
        this.bindingSbn = sbn;
        this.pluginLoader = pluginLoader;
        this.logger = logger;
        this.deadline = SystemClock.uptimeMillis() + 1500L;
        // This Handler is private to the Render, including the native keeper's tick runnable.
        this.handler = new Handler(Looper.getMainLooper()) {
            @Override public void dispatchMessage(Message message) {
                try {
                    if (!closed) super.dispatchMessage(message);
                } catch (Throwable error) {
                    logger.error("native timer callback key=" + bindingSbn.getKey(), error);
                    close();
                }
            }
        };
    }

    private void tryBind() {
        if (closed || keeper != null) return;
        Bundle extras = bindingSbn.getNotification().extras;
        int type = extras == null ? 0 : extras.getInt("timerType", 0);
        int id = extras == null ? 0 : extras.getInt("miui.focus.chronometerId", 0);
        // Module binding is asynchronous and writes the actual chronometer id into the private SBN.
        if (!root.isAttachedToWindow() || type == 0 || id == 0 || root.findViewById(id) == null) {
            if (SystemClock.uptimeMillis() < deadline) handler.postDelayed(this::tryBind, 48L);
            else if (type != 0) logger.log("native timer unavailable key=" + bindingSbn.getKey()
                    + " reason=chronometer-not-bound id=" + id);
            return;
        }
        try {
            Class<?> keeperClass = XposedHelpers.findClass(
                    "miui.systemui.notification.NotificationTimeKeeper", pluginLoader);
            Class<?> typeClass = XposedHelpers.findClass(
                    "miui.systemui.notification.NotificationTimeKeeper$ChronometerType", pluginLoader);
            Class<?> infoClass = XposedHelpers.findClass(
                    "miui.systemui.notification.NotificationChronometerManager$TimerInfo", pluginLoader);
            Object regular = XposedHelpers.getStaticObjectField(typeClass, "REGULAR");
            keeper = XposedHelpers.newInstance(keeperClass, context, handler);
            XposedHelpers.callMethod(keeper, "setChronometerId", id);
            boolean progress = extras.getBoolean("isAutoProgress", false);
            int progressId = progress ? extras.getInt("StatusProgressLayout", 0) : 0;
            XposedHelpers.callMethod(keeper, "addChronometerFromView", root, id,
                    regular, progress, progressId);
            XposedHelpers.callMethod(keeper, "setFocusNotificationIn", true);
            Object info = XposedHelpers.newInstance(infoClass, type,
                    Long.valueOf(extras.getLong("timerWhen")),
                    extras.getLong("timerTotal", 0L),
                    Long.valueOf(extras.getLong("timerSystemCurrent")));
            XposedHelpers.callMethod(keeper, "updateInfo", info);
            logger.log("native timer attached key=" + bindingSbn.getKey()
                    + " type=" + type + " owner=private-view-tree");
        } catch (Throwable error) {
            logger.error("native timer bind key=" + bindingSbn.getKey(), error);
            close();
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        Object current = keeper;
        keeper = null;
        try {
            if (current != null) XposedHelpers.callMethod(current, "reset");
        } catch (Throwable error) {
            logger.error("native timer reset key=" + bindingSbn.getKey(), error);
        } finally {
            handler.removeCallbacksAndMessages(null);
        }
    }
}