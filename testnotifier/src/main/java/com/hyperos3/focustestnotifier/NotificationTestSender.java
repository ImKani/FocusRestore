/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focustestnotifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.widget.RemoteViews;

/**
 * 来源：本项目testnotifier/FocusTestNotifier及FocusPayloads的已观测extras接口；
 * 原始ROM为小米，私有协议许可未确认，接口核查见Notes/analysis/systemui-content-chain.md。
 * 独立构造合成样本，只发布本包通知，不修改ROM、不伪装小爱/设备或媒体会话。
 */
final class NotificationTestSender {
    private static final String TAG = "HyperOS3FocusRestore";
    static final String CHANNEL = "focusrestore_notification_tests";
    static final String NOTIFICATION_TAG = "focusrestore.manual-test";

    static String post(Context context, NotificationTestCases.Spec spec) {
        NotificationManager manager = manager(context);
        if (!manager.areNotificationsEnabled()) return "尚未允许通知，请在“通知权限设置”中开启。";
        NotificationChannel channel = new NotificationChannel(CHANNEL, "手动通知测试",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("由首页手动发送的焦点与普通通知样本");
        channel.enableVibration(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
        NotificationChannel existing = manager.getNotificationChannel(CHANNEL);
        if (existing != null && existing.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            return "测试通知渠道已关闭，请在“通知权限设置”中开启。";
        }
        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_test_small)
                .setContentTitle("FocusRestore · " + spec.label)
                .setContentText(spec.nativeFocus ? spec.left : "手动测试通知，点击返回设置")
                .setContentIntent(open).setAutoCancel(true).setWhen(System.currentTimeMillis());
        if (spec.plain) {
            builder.setStyle(new Notification.BigTextStyle().bigText("这是一条普通通知，用于与焦点通知对照。"));
        } else {
            Bundle extras = builder.getExtras();
            extras.putBoolean("miui.focus.enableAlert", true);
            extras.putBoolean("miui.focus.isPromoted", true);
            if (spec.nativeFocus) {
                extras.putBoolean("miui.focus.isFocus", true);
                extras.putString("miui.focus.ticker", spec.left);
                extras.putParcelable("miui.focus.rv", views(context, spec.left, false));
                extras.putParcelable("miui.focus.rvNight", views(context, spec.left, false));
                extras.putParcelable("miui.focus.rvBar", views(context, spec.left, true));
                extras.putParcelable("miui.focus.rvBarNight", views(context, spec.left, false));
            } else {
                extras.putString("miui.focus.param", spec.payload());
            }
            Bundle pictures = new Bundle();
            if (spec.icons == NotificationTestCases.TICKER) {
                pictures.putParcelable("miui.focus.pic_test_ticker",
                        Icon.createWithResource(context, R.drawable.ic_notification_test_ticker));
                extras.putString("miui.focus.pic_ticker", "miui.focus.pic_test_ticker");
                extras.putString("miui.focus.pic_ticker_dark", "miui.focus.pic_test_ticker");
            } else if (spec.icons == NotificationTestCases.ISLAND) {
                pictures.putParcelable("miui.focus.pic_test_island",
                        Icon.createWithResource(context, R.drawable.ic_notification_test_island));
            }
            extras.putBundle("miui.focus.pics", pictures);
        }
        manager.notify(NOTIFICATION_TAG, spec.id, builder.build());
        if (BuildConfig.DEBUG) {
            android.util.Log.i(TAG, "DIAG notification-test posted id=" + spec.id
                    + " native=" + spec.nativeFocus + " plain=" + spec.plain + " icons=" + spec.icons
                    + " baseInfo=" + (!spec.plain && !spec.nativeFocus)
                    + " version=" + BuildConfig.VERSION_NAME + " code=" + BuildConfig.VERSION_CODE);
            if (!spec.plain && !spec.nativeFocus) {
                String payload = builder.getExtras().getString("miui.focus.param");
                android.util.Log.i(TAG, "DIAG notification-test payload id=" + spec.id
                        + " structure=" + "baseInfo+param_island"
                        + " json=" + payload + " escaped=" + escapeNonAscii(payload));
            }
        }
        return "已提交通知：" + spec.label + "。请回桌面或展开通知栏观察。";
    }

    static void clear(Context context) {
        NotificationManager manager = manager(context);
        // 只清理本功能的tag与固定id，不用cancelAll影响本包其他通知。
        for (NotificationTestCases.Spec spec : NotificationTestCases.ALL) manager.cancel(NOTIFICATION_TAG, spec.id);
        if (BuildConfig.DEBUG) android.util.Log.i(TAG, "DIAG notification-test cleared count=" + NotificationTestCases.ALL.size());
    }

    private static RemoteViews views(Context context, String title, boolean darkText) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.notification_test_focus);
        views.setTextViewText(R.id.notification_test_title, title);
        views.setTextColor(R.id.notification_test_title, darkText ? Color.BLACK : Color.WHITE);
        return views;
    }

    private static NotificationManager manager(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) throw new IllegalStateException("NotificationManager unavailable");
        return manager;
    }
    private static String escapeNonAscii(String text) {
        if (text == null) return "null";
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value < 32 || value > 126) {
                String hex = Integer.toHexString(value);
                escaped.append("\\u");
                for (int pad = hex.length(); pad < 4; pad++) escaped.append('0');
                escaped.append(hex);
            } else escaped.append(value);
        }
        return escaped.toString();
    }
    private NotificationTestSender() { }
}
