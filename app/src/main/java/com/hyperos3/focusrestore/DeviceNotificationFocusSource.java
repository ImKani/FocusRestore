/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. 由 FocusRestore 项目维护。
 * 原始项目：https://github.com/ImKani/FocusRestore
 * 外部事实来源：HyperOS 3 SystemUI APK os3系统界面_16.03.251211.r.apk 与其组件包
 * miui.systemui.plugin 17.1.4.71.0 的公开资源与模型字段；分析记录见 Notes/analysis。
 * 说明：本文件为 FocusRestore 独立实现，不复制或重新授权 SystemUI / MIUI 代码。
 */
package com.hyperos3.focusrestore;

import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设备通知（充电 / 静音 / 勿扰）横幅的内容源，交给 {@link FocusBannerController} 统一装窗。
 *
 * <p>外观刻意对齐 ROM 的焦点通知：<b>不绘制任何底色</b>，只有图标 + 文字，避免又出现一块
 * 黑色"脑门"；文字用模型下发的前景色并加柔和阴影，保证在浅色壁纸上也可读。
 *
 * <p>内容全部来自 ROM 的 {@code com.miui.toast.bean.StrongToastModel}：{@code statusBarGuideModel}
 * 左右两侧的 {@code text} / {@code textColor} / {@code iconResName}、充电事件的 {@code charge}
 * 文案、停留时长 {@code duration}，以及点击动作 {@code target}（ROM 的强提示点击同样是
 * {@code PendingIntent.send()}）。这些字段在系统界面 APK 中的读取位置由主 Hook 负责，本类只按
 * 已取出的值建视图，因此不依赖任何反射。
 *
 * <p>图标名（如 {@code miui_statusbar_dnd_mode_on}）定义在系统界面组件包中，SystemUI 自身的
 * Resources 看不到该包，必须取组件包的 Resources 解析；同一个名字在两个包内都存在时以组件包
 * 为准，与 ROM 的渲染一致。
 *
 * <p>摆放与时长跟随原强提示：实现 {@link FocusBannerSource.StatusBarRow}，让横幅与状态栏同一行、
 * 横向紧跟在状态栏时间右侧（ROM 的状态栏引导内容本来就在那一行、跟着时间显示），而不是落在
 * 状态栏下方的横幅位或屏幕居中；模型只给充电事件下发 {@code duration}，其余事件为 0，为 0 时用
 * {@link #DEFAULT_VISIBLE_MS}。
 */
final class DeviceNotificationFocusSource
        implements FocusBannerSource, FocusBannerSource.BodyAction, FocusBannerSource.StatusBarRow {
    private static final String TAG = "HyperOS3FocusRestore";
    /** ROM 侧图标实际所在的包：miui_statusbar_dnd_mode_* / miui_statuebar_silent_mode_* 都定义在这里。 */
    private static final String PLUGIN_PACKAGE = "miui.systemui.plugin";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    /**
     * 模型没有下发时长时（静音 / 勿扰的 {@code duration} 为 0）使用的停留时长，与原强提示一致。
     */
    static final long DEFAULT_VISIBLE_MS = 5000L;
    private static final int ICON_BOX_DP = 22;
    private static final int LABEL_SIZE_SP = 14;
    private static final float TEXT_SHADOW_RADIUS = 6f;
    private static final float TEXT_SHADOW_DY = 1f;
    private static final int TEXT_SHADOW_COLOR = 0xB3000000;
    private static final int HORIZONTAL_PADDING_DP = 12;
    private static final int VERTICAL_PADDING_DP = 4;
    private static final int ICON_TEXT_GAP_DP = 6;

    private final LinearLayout root;
    private final Context context;
    private final String text;
    private final String iconResName;
    private final PendingIntent target;
    private final int widthPx;
    private final int preferredLeftPx;
    private final String source;
    private boolean closed;

    private DeviceNotificationFocusSource(LinearLayout root, Context context, String text,
                                          String iconResName, PendingIntent target, int widthPx,
                                          int preferredLeftPx, String source) {
        this.root = root;
        this.context = context;
        this.text = text;
        this.iconResName = iconResName;
        this.target = target;
        this.widthPx = widthPx;
        this.preferredLeftPx = preferredLeftPx;
        this.source = source;
    }

    @Override
    public int preferredLeftPx() {
        return preferredLeftPx;
    }

    /**
     * 模型只给充电事件下发时长，静音 / 勿扰为 0；为 0 或缺失时沿用默认停留时长。
     *
     * <p>返回值同时用于窗口计时和日志，因此日志里的 {@code duration} 是实际生效值。
     */
    static long visibleMsFrom(Long modelDuration) {
        return modelDuration != null && modelDuration > 0 ? modelDuration : DEFAULT_VISIBLE_MS;
    }

    /**
     * 按设备通知的内容建一个未挂载的横幅根视图。
     *
     * <p>宽度由内容决定：先按屏幕宽度上限测量一次，宿主再按该宽度装窗，因此横幅像旧版一样收在
     * 图标和文字两侧，而不是拉成一条通栏。{@code preferredLeftPx} 是宿主摆放用的左边缘屏幕坐标
     * （状态栏时间右侧），负数表示没有可用参照、由宿主居中。参数不合法时抛
     * {@link IllegalStateException}，由调用方记录原因并放弃本次呈现；图标解析失败只降级为无图标，
     * 不影响文字。
     */
    static FocusBannerSource create(Context context, String text, Integer textColor,
                                    String iconResName, long visibleMs, PendingIntent target,
                                    String category, int preferredLeftPx) {
        if (context == null) throw new IllegalStateException("no context for the device banner");
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalStateException("empty device notification text");
        }
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        // 不画底色：焦点通知本身就是图标 + 文字，任何填充都会变成新的"黑窟窿"。
        root.setPadding(dp(context, HORIZONTAL_PADDING_DP), dp(context, VERTICAL_PADDING_DP),
                dp(context, HORIZONTAL_PADDING_DP), dp(context, VERTICAL_PADDING_DP));
        Drawable icon = resolveIcon(context, iconResName);
        if (icon != null) {
            ImageView iconView = new ImageView(context);
            iconView.setImageDrawable(icon);
            root.addView(iconView, new LinearLayout.LayoutParams(
                    dp(context, ICON_BOX_DP), dp(context, ICON_BOX_DP)));
        }
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(LABEL_SIZE_SP);
        label.setTextColor(textColor == null ? Color.WHITE : textColor);
        label.setShadowLayer(TEXT_SHADOW_RADIUS, 0f, TEXT_SHADOW_DY, TEXT_SHADOW_COLOR);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (icon != null) labelParams.leftMargin = dp(context, ICON_TEXT_GAP_DP);
        root.addView(label, labelParams);

        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        root.measure(View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int widthPx = Math.min(screenWidth, Math.max(root.getMeasuredWidth(), 1));
        String source = "device-notification"
                + (category == null || category.isEmpty() ? "" : " category=" + category)
                + " duration=" + visibleMs
                + " target=" + (target != null)
                + " left=" + preferredLeftPx;
        return new DeviceNotificationFocusSource(root, context, text, iconResName, target, widthPx,
                preferredLeftPx, source);
    }

    /**
     * 按模型下发的资源名解析图标。先取组件包的 Resources，再退回 SystemUI 自身：
     * {@code getIdentifier(name, type, packageName)} 只能在该 Resources 覆盖的包内查找，
     * 用它去查另一个包必然为 0，这正是旧版横幅图标一直缺失的原因。
     */
    private static Drawable resolveIcon(Context context, String name) {
        if (name == null || name.isEmpty()) return null;
        Drawable icon = drawableFromPackage(context, PLUGIN_PACKAGE, name);
        if (icon == null) icon = drawableFromPackage(context, SYSTEM_UI_PACKAGE, name);
        if (icon == null) Log.i(TAG, "device banner icon missing name=" + name);
        return icon;
    }

    private static Drawable drawableFromPackage(Context context, String packageName, String name) {
        try {
            Resources resources = context.getPackageManager().getResourcesForApplication(packageName);
            if (resources == null) return null;
            int id = resources.getIdentifier(name, "drawable", packageName);
            if (id == 0) return null;
            return resources.getDrawable(id, null);
        } catch (Throwable error) {
            Log.i(TAG, "device banner icon " + packageName + "/" + name + " failed: " + error);
            return null;
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public Context context() {
        return context;
    }

    @Override
    public int widthPx() {
        return widthPx;
    }

    @Override
    public int minHeightPx() {
        return 0;
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public String layoutSummary() {
        return "deviceBanner width=" + root.getWidth() + " height=" + root.getHeight()
                + " measuredWidth=" + root.getMeasuredWidth()
                + " measuredHeight=" + root.getMeasuredHeight()
                + " text=" + DebugText.preview(text)
                + " icon=" + iconResName;
    }

    @Override
    public void onAttached() {
        // 没有计时会话：停留时长由宿主按本源的 duration 统一定时。
    }

    /**
     * 设备通知没有通知条目，宿主传入的 {@code actual} 恒为 null；内容由模型事件驱动，
     * 因此只要本源没关闭就仍然代表当前内容。
     */
    @Override
    public boolean isCurrent(StatusBarNotification actual) {
        return !closed;
    }

    /** ROM 的强提示点击：先发 {@code target}，再收起窗口（发送失败也照常收起）。 */
    @Override
    public boolean onBodyTap() {
        PendingIntent intent = target;
        if (intent != null) {
            try {
                intent.send();
            } catch (PendingIntent.CanceledException canceled) {
                Log.i(TAG, "device banner target canceled: " + canceled);
            } catch (Throwable error) {
                Log.i(TAG, "device banner target failed: " + error);
            }
        }
        return true;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            root.removeAllViews();
        } catch (Throwable error) {
            Log.i(TAG, "device banner close failed: " + error);
        }
    }
}
