/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

/*
 * 原始项目：https://github.com/ImKani/FocusRestore
 * 外部事实来源：HyperOS 3 SystemUI APK os3系统界面_16.03.251211.r.apk 的 com.android.systemui
 * 16.03.251211.r（source go/retraceme 517ab4bb…ce5979）与系统界面组件包 miui.systemui.plugin
 * 17.1.4.71.0 的资源表；分析记录见 Notes/analysis/0.25.0-构造焦点通知方案.md。
 * 说明：本文件为 FocusRestore 独立实现，不复制或重新授权 SystemUI / MIUI 代码。
 * SystemUI / MIUI 资源及私有协议的版权所有者、原协议：未确认；沿用上述版本接口事实，
 * 本项目独立增加投递诊断、图标安全降级与请求序号。OS4 plugin 图片契约仍待验证。
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
 * 进程内），所以外包图标转成 Bitmap 的 Icon；小图标位图失败时使用框架资源 id。
 * 系统接受 notify 不代表实际显示，仍需通过 {@link #EXTRA_MODULE_MARKER} 确认通知管线；无横幅兜底。
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
    private static final String EXTRA_PIC_TICKER_DARK = "miui.focus.pic_ticker_dark";
    private static final String PIC_TICKER_NAME = "ticker";
    static final String EXTRA_MODULE_MARKER = "focusrestore.device.notification";
    static final String EXTRA_POST_SEQUENCE = "focusrestore.device.sequence";

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

    private long postSequence;

    DeviceNotificationFocusPoster(Context context) {
        this.context = context;
        this.notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /** 本机通知总开关；不可用时只记录原因，没有横幅兜底。 */
    boolean isAvailable() {
        if (notificationManager == null) {
            diag("availability service=missing");
            return false;
        }
        try {
            boolean enabled = notificationManager.areNotificationsEnabled();
            diag("availability enabled=" + enabled);
            return enabled;
        } catch (Throwable error) {
            failure("availability", error);
            return false;
        }
    }

    int lastPostedId() {
        return previousNotificationId;
    }

    long lastPostSequence() {
        return postSequence;
    }

    /** true 仅代表交给系统，不代表 ROM 已显示；投递失败没有横幅兜底。 */
    boolean post(String text, String iconResName, long visibleMs, PendingIntent target) {
        return post(text, iconResName, visibleMs, target, false);
    }

    boolean post(String text, String iconResName, long visibleMs, PendingIntent target,
                 boolean updateExisting) {
        return post(text, iconResName, visibleMs, target, updateExisting, null, null, null);
    }

    boolean post(String text, String iconResName, long visibleMs, PendingIntent target,
                 boolean updateExisting, String sourcePackage, String category, String format) {
        if (notificationManager == null || text == null || text.trim().isEmpty()) {
            diag("post rejected service=" + (notificationManager != null)
                    + " text=" + DebugText.preview(text));
            return false;
        }
        boolean updating = updateExisting && previousNotificationId != -1;
        int id = updating ? previousNotificationId : nextNotificationId();
        long sequence = postSequence + 1;
        // 更新传入的是原事件剩余期限，不能再套一秒下限而延长总窗口。
        long timeout = Math.max(updating ? 1L : MIN_TIMEOUT_MS, visibleMs);
        String stage = "availability";
        try {
            if (!isAvailable()) return false;
            stage = "channel";
            if (!ensureChannel()) return false;
            stage = "icon";
            // 同一请求只解析一次；smallIcon 与焦点提示复用位图，避免重复解码 ROM 资源。
            Icon icon = tickerIcon(iconResName, sourcePackage, category, format);
            Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID);
            if (icon != null) {
                builder.setSmallIcon(icon);
            } else {
                // setSmallIcon(null) 不满足系统校验；框架 id 不依赖模块或外包资源。
                builder.setSmallIcon(android.R.drawable.ic_lock_idle_charging);
                diag("icon fallback=framework-resource id=" + android.R.drawable.ic_lock_idle_charging);
            }
            stage = "build";
            builder.setContentTitle(text)
                    .setContentText(text)
                    .setShowWhen(false)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(updating)
                    // 渠道已是低重要度；避免同时出声、振动或额外横幅。
                    .setSound(null)
                    .setVibrate(null)
                    .setDefaults(0)
                    .setTimeoutAfter(timeout);
            if (target != null) builder.setContentIntent(target);
            Notification notification = builder.build();
            Bundle extras = notification.extras;
            if (extras == null) notification.extras = extras = new Bundle();
            extras.putBoolean(EXTRA_IS_FOCUS, true);
            extras.putString(EXTRA_TICKER, text);
            extras.putBoolean(EXTRA_MODULE_MARKER, true);
            extras.putLong(EXTRA_POST_SEQUENCE, sequence);
            if (icon != null) {
                Bundle pictures = new Bundle();
                pictures.putParcelable(PIC_TICKER_NAME, icon);
                extras.putBundle(EXTRA_PICS, pictures);
                // 保留旧契约与暗色选择键，不凭单个 dex 搜索结果删除动态/插件读取的字段。
                extras.putString(EXTRA_PIC_TICKER, PIC_TICKER_NAME);
                extras.putString(EXTRA_PIC_TICKER_DARK, PIC_TICKER_NAME);
            }
            diag("build id=" + id + " sequence=" + sequence + " update=" + updating
                    + " extras=" + DebugText.escapeNonAscii(extras.keySet().toString())
                    + " smallIcon=" + iconDescription(notification.getSmallIcon())
                    + " tickerIcon=" + iconDescription(icon) + " target=" + (target != null)
                    + " timeout=" + timeout + " text=" + DebugText.preview(text));
            // 动画帧更新同一通知不重新提醒；独立事件换 id 才能触发新焦点提示。
            if (!updating && previousNotificationId != -1) {
                stage = "cancel-previous";
                diag("cancel-before previousId=" + previousNotificationId + " nextId=" + id
                        + " sequence=" + sequence);
                notificationManager.cancel(NOTIFICATION_TAG, previousNotificationId);
                previousNotificationId = -1;
            }
            stage = "notify";
            diag("notify-before id=" + id + " sequence=" + sequence + " update=" + updating);
            notificationManager.notify(NOTIFICATION_TAG, id, notification);
            previousNotificationId = id;
            notificationId = id;
            postSequence = sequence;
            diag("notify-accepted id=" + id + " sequence=" + sequence
                    + " duration=" + timeout + " icon=" + DebugText.preview(iconResName));
            return true;
        } catch (Throwable error) {
            failure("post stage=" + stage + " id=" + id + " sequence=" + sequence
                    + " previousId=" + previousNotificationId + " icon="
                    + DebugText.preview(iconResName), error);
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
        if (notificationManager == null) {
            diag("cancel service=missing");
            return;
        }
        try {
            diag("cancel-before id=" + previousNotificationId + " sequence=" + postSequence);
            if (previousNotificationId != -1) {
                notificationManager.cancel(NOTIFICATION_TAG, previousNotificationId);
                previousNotificationId = -1;
            }
            diag("cancel-complete sequence=" + postSequence);
        } catch (Throwable error) {
            failure("cancel id=" + previousNotificationId + " sequence=" + postSequence, error);
        }
    }

    /** 每次检查渠道阻止状态；仅创建成功后缓存，异常允许下次请求重试。 */
    private boolean ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return true;
        try {
            NotificationChannel channel = notificationManager.getNotificationChannel(CHANNEL_ID);
            diag("channel exists=" + (channel != null) + " checked=" + channelChecked);
            if (channel == null) {
                channelChecked = false;
                channel = new NotificationChannel(CHANNEL_ID, "设备通知焦点提示",
                        NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                channel.enableVibration(false);
                channel.setSound(null, null);
                notificationManager.createNotificationChannel(channel);
                channel = notificationManager.getNotificationChannel(CHANNEL_ID);
                diag("channel created exists=" + (channel != null));
            }
            if (channel == null) return false;
            channelChecked = true;
            boolean blocked = channel.getImportance() == NotificationManager.IMPORTANCE_NONE;
            diag("channel id=" + CHANNEL_ID + " importance=" + channel.getImportance()
                    + " blocked=" + blocked);
            return !blocked;
        } catch (Throwable error) {
            channelChecked = false;
            failure("channel id=" + CHANNEL_ID, error);
            return false;
        }
    }

    /** 模型动画不能作为静态 Drawable；静态资源按来源查找后回退旧 ROM 两包。 */
    private Icon tickerIcon(String name, String sourcePackage, String category, String format) {
        if (format != null && !format.isEmpty() && !"png".equalsIgnoreCase(format)
                && !"svg".equalsIgnoreCase(format)) {
            diag("icon animation-fallback format=" + DebugText.preview(format)
                    + " source=" + DebugText.preview(sourcePackage));
            return chargingFallbackIcon();
        }
        String packageName = "android.miui".equals(sourcePackage) ? "android" : sourcePackage;
        String resourceType = category == null || category.isEmpty() ? "drawable" : category;
        Drawable drawable = null;
        // 来源字段来自 ROM 模型，但仍校验长度与包名字符，避免无界输入进入包管理器。
        if (packageName != null && packageName.length() <= 255
                && packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
                && resourceType.length() <= 64 && resourceType.matches("[A-Za-z0-9_]+")) {
            drawable = drawableFromPackage(packageName, name, resourceType);
        } else if (packageName != null) {
            diag("icon source-rejected package=" + DebugText.preview(packageName)
                    + " category=" + DebugText.preview(resourceType));
        }
        if (drawable == null && !(PLUGIN_PACKAGE.equals(packageName) && "drawable".equals(resourceType))) {
            drawable = drawableFromPackage(PLUGIN_PACKAGE, name, "drawable");
        }
        if (drawable == null && !(SYSTEM_UI_PACKAGE.equals(packageName) && "drawable".equals(resourceType))) {
            drawable = drawableFromPackage(SYSTEM_UI_PACKAGE, name, "drawable");
        }
        Bitmap bitmap = drawable == null ? null : rasterize(drawable);
        if (bitmap != null) return Icon.createWithBitmap(bitmap);
        diag("icon fallback=framework-bitmap name=" + DebugText.preview(name));
        return chargingFallbackIcon();
    }

    /** 框架位图也失败时由构造阶段 setSmallIcon(int) 保证有效的小图标。 */
    private Icon chargingFallbackIcon() {
        try {
            Drawable drawable = context.getDrawable(android.R.drawable.ic_lock_idle_charging);
            diag("icon framework-load id=" + android.R.drawable.ic_lock_idle_charging
                    + " loaded=" + (drawable != null));
            Bitmap bitmap = drawable == null ? null : rasterize(drawable);
            return bitmap == null ? null : Icon.createWithBitmap(bitmap);
        } catch (Throwable error) {
            failure("icon framework-fallback", error);
            return null;
        }
    }

    private Drawable drawableFromPackage(String packageName, String name, String resourceType) {
        if (name == null || name.isEmpty() || name.length() > 255) {
            diag("icon lookup-skipped package=" + packageName + " name=" + DebugText.preview(name));
            return null;
        }
        try {
            Resources resources = context.getPackageManager().getResourcesForApplication(packageName);
            if (resources == null) {
                diag("icon resources-missing package=" + packageName);
                return null;
            }
            int id = resources.getIdentifier(name, resourceType, packageName);
            diag("icon lookup package=" + packageName + " name=" + DebugText.preview(name)
                    + " requestedType=" + resourceType + " id=" + id
                    + " actualType=" + (id == 0 ? "missing" : resources.getResourceTypeName(id)));
            if (id == 0) return null;
            Drawable drawable = resources.getDrawable(id, null);
            diag("icon load package=" + packageName + " id=" + id + " drawable="
                    + (drawable == null ? "null" : drawable.getClass().getName()));
            return drawable;
        } catch (Throwable error) {
            failure("icon lookup package=" + packageName + " name=" + DebugText.preview(name)
                    + " type=" + resourceType, error);
            return null;
        }
    }

    private Bitmap rasterize(Drawable drawable) {
        Bitmap bitmap = null;
        try {
            int size = Math.max(1, Math.round(ICON_SIZE_DP
                    * context.getResources().getDisplayMetrics().density));
            bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(new Canvas(bitmap));
            diag("icon bitmap width=" + size + " height=" + size + " config=" + bitmap.getConfig());
            return bitmap;
        } catch (Throwable error) {
            failure("icon rasterize drawable=" + drawable.getClass().getName(), error);
            if (bitmap != null) bitmap.recycle();
            return null;
        }
    }

    private static String iconDescription(Icon icon) {
        if (icon == null) return "null";
        // getType 从 API 28 起公开；项目最低 API 27 仅记录类名而不调用新接口。
        return Build.VERSION.SDK_INT >= 28 ? "type=" + icon.getType() : icon.getClass().getName();
    }

    private static void diag(String message) {
        if (BuildConfig.DEBUG) Log.i(TAG, "DIAG device " + message);
    }

    private static void failure(String stage, Throwable error) {
        // Release 保留必要错误但不输出输入详情；Debug 保留阶段和完整异常原因。
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "DIAG device " + stage + " failed=" + DebugText.preview(error.toString()), error);
        } else {
            Log.w(TAG, "device focus notification failed: " + error.getClass().getName());
        }
    }
}