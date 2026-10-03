/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. 由 FocusRestore 项目维护。
 * 原始项目：https://github.com/ImKani/FocusRestore
 * 外部事实来源：HyperOS 3 SystemUI APK os3系统界面_16.03.251211.r.apk 的 com.android.systemui
 * 16.03.251211.r（source go/retraceme 517ab4bb…ce5979）与系统界面组件包 miui.systemui.plugin
 * 17.1.4.71.0 的资源表；分析记录见 Notes/analysis/0.25.0-构造焦点通知方案.md。
 * 说明：本文件为 FocusRestore 独立实现，不复制或重新授权 SystemUI / MIUI 代码。
 */
package com.hyperos3.focusrestore;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * 设备通知的"构造焦点通知"通路：模块自己发一条通知，让 ROM 把它当焦点通知显示在状态栏焦点位。
 *
 * <p>ROM 完全按 {@code Notification} 对象判定焦点身份（{@code NotificationEntry.setSbn} 里就是
 * {@code FocusUtils.isFocusNotification(notification)}），因此不需要注入 ROM 内部结构，只要 extras
 * 命中契约：
 *
 * <ul>
 *   <li>{@code FocusUtils.isFocusNotification}：{@code miui.focus.isFocus=true}，或
 *       {@code miui.focus.rv} 是 RemoteViews；</li>
 *   <li>{@code FocusUtils.showOnStatusBar}：焦点身份 + {@code miui.focus.ticker} 非空（或
 *       {@code miui.focus.rvBar}）；{@code FocusedNotifPromptController.onNotifHeadsUpResult} 也在
 *       {@code FEATURE_DYNAMIC_ISLAND=false}（模块已强制关闭）且该闸门通过时才建提示；</li>
 *   <li>{@code FocusUtils.getStatusBarTickerIcon}：{@code miui.focus.pics} 里由
 *       {@code miui.focus.pic_ticker} 指名的 Icon，作为焦点提示的图标。</li>
 * </ul>
 *
 * <p>投递走系统公开 API，但通知属于 {@code com.android.systemui} 这个包（模块代码运行在 SystemUI
 * 进程内），所以图标不能用模块自己的资源 id，一律转成 Bitmap 的 Icon；无权限时系统会静默丢弃，
 * 因此调用方要用 {@link #EXTRA_MODULE_MARKER} 确认是否真的进了通知管线，未确认就回退模块横幅。
 */
final class DeviceNotificationFocusPoster {
    private static final String TAG = "HyperOS3FocusRestore";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    /** ROM 侧设备通知图标（miui_statusbar_dnd_mode_on 等）实际所在的包。 */
    private static final String PLUGIN_PACKAGE = "miui.systemui.plugin";
    /** 焦点身份开关；{@code FocusUtils.isFocusNotification} 读它。 */
    private static final String EXTRA_IS_FOCUS = "miui.focus.isFocus";
    /** 状态栏焦点提示的文字；{@code FocusUtils.getStatusBarTicker} 读它。 */
    private static final String EXTRA_TICKER = "miui.focus.ticker";
    /** 提示图标：名字 → Icon 的 Bundle，以及指向其中一项的名字。 */
    private static final String EXTRA_PICS = "miui.focus.pics";
    private static final String EXTRA_PIC_TICKER = "miui.focus.pic_ticker";
    private static final String PIC_TICKER_NAME = "ticker";
    /** 模块自己的投递标记；主 Hook 用它确认这条通知真的进了通知管线。 */
    static final String EXTRA_MODULE_MARKER = "focusrestore.device.notification";

    private static final String CHANNEL_ID = "focusrestore_device_notification";
    private static final String NOTIFICATION_TAG = "focusrestore.device-notification";
    /**
     * 通知 id 的取值区间。
     *
     * <p>同 tag 同 id 的重复投递在 ROM 里只是"更新同一条通知"，不会重新触发状态栏焦点提示（0.25.1
     * 真机日志里多次 posted/delivered 之后没有对应的提示 setData）。每次事件换一个新 id 并取消上一条，
     * 系统就会当成新通知，提示每次都会重新出现。
     */
    private static final int NOTIFICATION_ID_BASE = 0x0F0C0517;
    private static final int NOTIFICATION_ID_MAX = NOTIFICATION_ID_BASE + 0x100;
    /** 焦点提示图标位图边长；ROM 的焦点提示图标视图是 48x48px，这里给足 24dp。 */
    private static final int ICON_SIZE_DP = 24;
    private static final long MIN_TIMEOUT_MS = 1_000L;
    /** 模型没有下发时长时（静音 / 勿扰的 duration 为 0）使用的停留时长，与原强提示一致。 */
    static final long DEFAULT_VISIBLE_MS = 5000L;

    /**
     * 模型只给充电事件下发时长，静音 / 勿扰为 0；为 0 或缺失时沿用默认停留时长。
     *
     * <p>返回值同时用于通知计时和日志，因此日志里的 {@code duration} 是实际生效值。
     */
    static long visibleMsFrom(Long modelDuration) {
        return modelDuration != null && modelDuration > 0 ? modelDuration : DEFAULT_VISIBLE_MS;
    }

    private final Context context;
    private final NotificationManager notificationManager;
    private boolean channelChecked;
    /** 当前通知 id 与上一条 id：换 id 让每次事件都算新通知，同时保证通知栏里只留一条。 */
    private int notificationId = NOTIFICATION_ID_BASE;
    private int previousNotificationId = -1;

    DeviceNotificationFocusPoster(Context context) {
        this.context = context;
        this.notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /** 本机这条通路是否可用；通知总开关关闭时调用方直接回退横幅。 */
    boolean isAvailable() {
        if (notificationManager == null) {
            Log.i(TAG, "device focus notification unavailable: notification service missing");
            return false;
        }
        try {
            boolean enabled = notificationManager.areNotificationsEnabled();
            if (!enabled) Log.i(TAG, "device focus notification unavailable: notifications disabled");
            return enabled;
        } catch (Throwable error) {
            Log.i(TAG, "device focus notification availability check failed: " + error);
            return false;
        }
    }

    /**
     * 按 ROM 的焦点契约构造并投递一条通知。
     *
     * <p>返回 false 表示这条通路不可用（参数不合法、构造或投递抛错），调用方回退横幅。返回 true 只
     * 代表已经交给系统：无权限时系统静默丢弃，调用方仍需用 {@link #EXTRA_MODULE_MARKER} 确认。
     */
    boolean post(String text, String iconResName, long visibleMs, PendingIntent target) {
        if (notificationManager == null || text == null || text.trim().isEmpty()) return false;
        try {
            ensureChannel();
            Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(smallIcon(iconResName))
                    .setContentTitle(text)
                    .setContentText(text)
                    .setShowWhen(false)
                    .setAutoCancel(true)
                    // 渠道已是低重要度；这里再清掉声音与振动，避免出现第二种提示方式。
                    .setSound(null)
                    .setVibrate(null)
                    .setDefaults(0)
                    .setTimeoutAfter(Math.max(MIN_TIMEOUT_MS, visibleMs));
            if (target != null) builder.setContentIntent(target);
            Notification notification = builder.build();
            Bundle extras = notification.extras;
            extras.putBoolean(EXTRA_IS_FOCUS, true);
            extras.putString(EXTRA_TICKER, text);
            extras.putBoolean(EXTRA_MODULE_MARKER, true);
            Icon tickerIcon = tickerIcon(iconResName);
            if (tickerIcon != null) {
                Bundle pictures = new Bundle();
                pictures.putParcelable(PIC_TICKER_NAME, tickerIcon);
                extras.putBundle(EXTRA_PICS, pictures);
                extras.putString(EXTRA_PIC_TICKER, PIC_TICKER_NAME);
            }
            int id = nextNotificationId();
            // 先收掉上一条：每次事件都换新 id（新通知才会重新触发焦点提示），但通知栏里只留一条。
            if (previousNotificationId != -1) {
                notificationManager.cancel(NOTIFICATION_TAG, previousNotificationId);
            }
            notificationManager.notify(NOTIFICATION_TAG, id, notification);
            previousNotificationId = id;
            notificationId = id;
            Log.i(TAG, "device focus notification posted id=" + id
                    + " text=" + text + " duration=" + Math.max(MIN_TIMEOUT_MS, visibleMs)
                    + " icon=" + iconResName + " resolvedIcon=" + (tickerIcon != null)
                    + " target=" + (target != null));
            return true;
        } catch (Throwable error) {
            Log.i(TAG, "device focus notification post failed: " + error);
            return false;
        }
    }

    /** 下一个通知 id：在固定区间内循环，避免长时间运行后 id 无限增长。 */
    private int nextNotificationId() {
        int next = notificationId + 1;
        return next > NOTIFICATION_ID_MAX ? NOTIFICATION_ID_BASE : next;
    }

    /** 取消本模块构造的焦点通知；可安全重复调用。 */
    void cancel() {
        if (notificationManager == null) return;
        try {
            if (previousNotificationId != -1) {
                notificationManager.cancel(NOTIFICATION_TAG, previousNotificationId);
                previousNotificationId = -1;
            }
            Log.i(TAG, "device focus notification canceled");
        } catch (Throwable error) {
            Log.i(TAG, "device focus notification cancel failed: " + error);
        }
    }

    /** 低重要度渠道：呈现交给 ROM 的焦点提示，这条通知自身不再出声、出横幅或角标。 */
    private void ensureChannel() {
        if (channelChecked) return;
        channelChecked = true;
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "设备通知焦点提示",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            notificationManager.createNotificationChannel(channel);
            Log.i(TAG, "device focus notification channel created id=" + CHANNEL_ID);
        } catch (Throwable error) {
            Log.i(TAG, "device focus notification channel failed: " + error);
        }
    }

    /** smallIcon：优先复用 ROM 组件包里的设备通知图标，取不到时退回框架充电图标。 */
    private Icon smallIcon(String iconResName) {
        Icon icon = tickerIcon(iconResName);
        return icon != null ? icon : chargingFallbackIcon();
    }

    /**
     * 焦点提示图标：把 ROM 下发的 drawable 转成 Bitmap 的 Icon 放进 {@code miui.focus.pics}。
     * 通知属于 SystemUI 包，直接引用组件包的资源 id 在解析时会失败，所以位图化后内联携带。
     *
     * <p>充电事件模型不带图标名，此时用框架的充电图标兜底，否则提示只有文字、图标位空着。
     */
    private Icon tickerIcon(String iconResName) {
        Drawable drawable = drawableFromPackage(PLUGIN_PACKAGE, iconResName);
        if (drawable == null) drawable = drawableFromPackage(SYSTEM_UI_PACKAGE, iconResName);
        if (drawable == null) {
            if (iconResName == null || iconResName.isEmpty()) return chargingFallbackIcon();
            return null;
        }
        Bitmap bitmap = rasterize(drawable);
        return bitmap == null ? null : Icon.createWithBitmap(bitmap);
    }

    /** 框架充电图标；取不到时返回 null，通知仍会带上模块自己写死的 smallIcon。 */
    private Icon chargingFallbackIcon() {
        try {
            Drawable drawable = context.getDrawable(android.R.drawable.ic_lock_idle_charging);
            if (drawable == null) return null;
            Bitmap bitmap = rasterize(drawable);
            return bitmap == null ? null : Icon.createWithBitmap(bitmap);
        } catch (Throwable error) {
            Log.i(TAG, "device focus notification fallback icon failed: " + error);
            return null;
        }
    }

    private Drawable drawableFromPackage(String packageName, String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            Resources resources = context.getPackageManager().getResourcesForApplication(packageName);
            if (resources == null) return null;
            int id = resources.getIdentifier(name, "drawable", packageName);
            if (id == 0) return null;
            return resources.getDrawable(id, null);
        } catch (Throwable error) {
            Log.i(TAG, "device focus notification icon " + packageName + "/" + name
                    + " failed: " + error);
            return null;
        }
    }

    private Bitmap rasterize(Drawable drawable) {
        int size = Math.max(1, Math.round(ICON_SIZE_DP
                * context.getResources().getDisplayMetrics().density));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        try {
            drawable.setBounds(0, 0, size, size);
            drawable.draw(new Canvas(bitmap));
        } catch (Throwable error) {
            Log.i(TAG, "device focus notification icon rasterize failed: " + error);
            bitmap.recycle();
            return null;
        }
        return bitmap;
    }
}
