/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import de.robv.android.xposed.XposedHelpers;

/** Xiaomi SystemUI OS3 16.03.251211.r / OS4 17.03.260226.r 的 HUN 例外合同。
 * 来源：作者只读 MCP，Knowledge/sources/decompiled/{os3-native-focus-hun,native-focus-hun}-20261117。
 * 厂商实现许可未确认；独立读取通知标志和行状态，不改写/持有原生 View。
 */
final class BannerHunEligibility {
    private static boolean failureLogged;

    static boolean allows(boolean known, boolean publicContent, boolean transparent, boolean custom) {
        return known && !publicContent && !transparent && !custom;
    }

    static boolean sameGeneration(String expectedKey, long expectedPostTime, String actualKey, long actualPostTime) {
        return expectedKey != null && expectedKey.equals(actualKey) && expectedPostTime == actualPostTime;
    }

    static boolean read(View row, StatusBarNotification sbn) {
        if (row == null || sbn == null || sbn.getNotification() == null) return false;
        try {
            Object entry;
            try { entry = XposedHelpers.callMethod(row, "getEntry"); }
            catch (NoSuchMethodError missing) { entry = XposedHelpers.getObjectField(row, "mEntry"); }
            if (entry == null) return false;
            java.lang.reflect.Field field = XposedHelpers.findFieldIfExists(entry.getClass(), "mSbn");
            if (field == null) field = XposedHelpers.findFieldIfExists(entry.getClass(), "sbn");
            Object value = field == null ? null : field.get(entry);
            if (!(value instanceof StatusBarNotification)) return false;
            StatusBarNotification bound = (StatusBarNotification) value;
            if (!sameGeneration(sbn.getKey(), sbn.getPostTime(), bound.getKey(), bound.getPostTime())
                    || sbn.getUid() != bound.getUid() || !sbn.getPackageName().equals(bound.getPackageName())) return false;
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;
            boolean transparent = extras != null && (extras.getBoolean("miui.focus.transparentBg", false)
                    || extras.getBoolean("miui.oneline.transparentBg", false));
            boolean custom = extras != null && extras.getBoolean("miui.focus.hasCustomBg", false);
            boolean publicContent = Boolean.TRUE.equals(XposedHelpers.callMethod(row, "shouldShowPublic"));
            Object layout = XposedHelpers.callMethod(row, "getShowingLayout");
            Object wrapper = layout == null ? null : XposedHelpers.callMethod(layout, "getVisibleWrapper", 2);
            if (wrapper != null && wrapper.getClass().getName().endsWith("MiuiNotificationOneLineViewWrapper")) {
                transparent |= XposedHelpers.getBooleanField(wrapper, "mIsTransparentBg");
            }
            boolean allowed = allows(true, publicContent, transparent, custom);
            if (BuildConfig.DEBUG) Log.d("HyperOS3FocusRestore", "DIAG banner HUN eligibility=" + allowed
                    + " public=" + publicContent + " transparent=" + transparent + " custom=" + custom);
            return allowed;
        } catch (Throwable error) {
            if (!failureLogged) {
                failureLogged = true;
                Log.w("HyperOS3FocusRestore", "banner HUN eligibility unavailable; keep legacy recipe", error);
            }
            return false;
        }
    }

    private BannerHunEligibility() { }
}
