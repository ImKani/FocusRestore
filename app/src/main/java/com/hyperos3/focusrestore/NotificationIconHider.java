/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hides the notificationIcons container and restores its latest requested visibility.
 *
 * Source facts: Android View#setVisibility/onAttachedToWindow and Xposed method hooks are
 * public API contracts from https://developer.android.com/reference/android/view/View and
 * https://api.xposed.info/. The notificationIcons resource belongs to the observed
 * HyperOS/SystemUI private view hierarchy; exact ROM version, owner license, and
 * implementation details are unconfirmed. This project independently hooks the observed
 * interface and does not copy SystemUI implementation.
 */
final class NotificationIconHider {
    interface Logger { void log(String message); void error(String stage, Throwable throwable); }
    private final Logger logger;
    private final NotificationIconsVisibilityState visibilityState =
            new NotificationIconsVisibilityState(View.GONE);
    private ViewGroup root;
    private int rootId;
    private boolean requested;
    private boolean internalWrite;
    private boolean installed;
    private View.OnLayoutChangeListener layoutListener;

    NotificationIconHider(Logger logger) { this.logger = logger; }

    void install() {
        if (installed) return;
        installed = true;
        try {
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (internalWrite || !requested || !(param.thisObject instanceof View)
                                    || param.args == null || param.args.length == 0
                                    || !(param.args[0] instanceof Integer)) return;
                            View view = (View) param.thisObject;
                            if (view != root) return;
                            int requestedVisibility = (Integer) param.args[0];
                            int previousDesired = visibilityState.desiredVisibility();
                            int appliedVisibility = visibilityState.onVisibilityRequested(
                                    requestedVisibility);
                            if (appliedVisibility != requestedVisibility) {
                                param.args[0] = appliedVisibility;
                                if (previousDesired != requestedVisibility) {
                                    logger.log("notificationIcons visibility intercepted requested="
                                            + requestedVisibility + " applied=" + appliedVisibility
                                            + " desired=" + visibilityState.desiredVisibility());
                                }
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (requested && param.thisObject == root) apply();
                        }
                    });
            logger.log("notificationIcons hider hooks=hooked");
        } catch (Throwable throwable) { logger.error("notificationIcons hider install", throwable); }
    }

    /** Binds the actual notificationIcons root. Call again after SystemUI reinflates it. */
    void bindRoot(ViewGroup newRoot, int id) {
        if (root == newRoot && rootId == id) return;
        clearRootListener();
        restore();
        root = newRoot;
        rootId = id;
        if (root != null) {
            layoutListener = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (requested) apply();
            };
            root.addOnLayoutChangeListener(layoutListener);
            if (requested) beginHidingIfNeeded();
        }
        if (requested) apply();
        logger.log("notificationIcons root=" + (root == null ? "missing" : root.getClass().getName()) + " id=" + id);
    }

    /** Applies the latest request to the notificationIcons root. */
    void request(boolean hide) {
        requested = hide;
        if (!hide) restore();
        else {
            beginHidingIfNeeded();
            apply();
        }
    }

    /** OS3 parent-agent API: locate notificationIcons below the visible prompt and apply. */
    void applyForVisiblePrompt(View prompt, boolean hide) {
        ViewGroup resolved = findNotificationIcons(prompt);
        if (resolved != null) bindRoot(resolved, resolved.getId());
        request(hide);
        logger.log("notificationIcons promptApply hide=" + hide
                + " root=" + (resolved == null ? "missing" : resolved.getClass().getName()));
    }

    void clear() {
        requested = false;
        restore();
        clearRootListener();
        root = null;
        rootId = 0;
    }

    private void apply() {
        ViewGroup container = root;
        if (container == null || !requested) return;
        write(container, View.GONE);
        logger.log("notificationIcons hide container class=" + container.getClass().getName());
    }

    private void beginHidingIfNeeded() {
        if (root != null && !visibilityState.isHiding()) {
            visibilityState.beginHiding(root.getVisibility());
            logger.log("notificationIcons tracked id=" + root.getId()
                    + " desired=" + visibilityState.desiredVisibility());
        }
    }

    private void restore() {
        ViewGroup container = root;
        if (container == null || !visibilityState.isHiding()) {
            visibilityState.reset();
            return;
        }
        int restoreVisibility = visibilityState.finishHiding();
        write(container, restoreVisibility);
        logger.log("notificationIcons restored id=" + container.getId()
                + " visibility=" + restoreVisibility);
    }

    private void write(View view, int visibility) {
        internalWrite = true;
        try { view.setVisibility(visibility); } finally { internalWrite = false; }
    }

    private static ViewGroup findNotificationIcons(View prompt) {
        if (prompt == null) return null;
        View root = prompt.getRootView();
        if (!(root instanceof ViewGroup)) root = prompt;
        int id = root.getResources().getIdentifier("notificationIcons", "id", "com.android.systemui");
        View found = id == 0 ? null : root.findViewById(id);
        return found instanceof ViewGroup ? (ViewGroup) found : null;
    }

    private void clearRootListener() {
        if (root != null && layoutListener != null) root.removeOnLayoutChangeListener(layoutListener);
        layoutListener = null;
    }
}