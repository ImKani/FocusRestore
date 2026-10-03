/* SPDX-License-Identifier: GPL-3.0-only; Copyright (C) ImKani; FocusRestore: https://github.com/ImKani/FocusRestore */
package com.hyperos3.focusrestore;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设备通知（充电 / 静音 / 勿扰）转焦点通知的横幅窗口。
 *
 * <p>外观刻意对齐 ROM 的焦点通知：<b>不绘制任何底色</b>，只有图标 + 文字，避免又出现一块
 * 黑色"脑门"；文字用模型下发的前景色并加柔和阴影，保证在浅色壁纸上也可读。
 *
 * <p>内容与 ROM 同源：强提示携带的 {@code StrongToastModel.statusBarGuideModel} 里
 * left/right 的 {@code text}、{@code textColor} 与 {@code iconResName}；充电事件的
 * {@code guide} 为空，取 {@code charge} 文案。展示时长取模型 {@code duration}
 * （充电 5000ms；勿扰/静音为 0 时用同等默认值，与原"脑门"的停留时长一致）。
 *
 * <p>刻意不复用 {@link FocusBannerController}：该宿主的展示入口与模板选择都写死了
 * {@code StatusBarNotification}，而设备通知没有对应通知条目，为其开一条无 SBN 通路会牵动
 * 已经承载焦点横幅与媒体横幅的既有逻辑。这里的窗口独立开关，进程内事件驱动，无待机开销。
 */
final class DeviceNotificationBanner {
    interface Logger {
        void log(String message);

        void error(String stage, Throwable error);
    }

    /** 与原"脑门"停留时长对齐：充电模型给 5000ms，勿扰/静音给 0 时沿用同一默认值。 */
    private static final long DEFAULT_VISIBLE_MS = 5000L;
    private static final int TEXT_SHADOW_COLOR = 0xB3000000;
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String PLUGIN_PACKAGE = "miui.systemui.plugin";

    private final Context context;
    private final Logger logger;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::dismiss;

    private WindowManager windowManager;
    private LinearLayout root;

    DeviceNotificationBanner(Context context, Logger logger) {
        this.context = context;
        this.logger = logger;
    }

    /** 展示一条设备通知横幅；返回是否成功挂上窗口。 */
    boolean show(String text, Integer textColor, String iconResName, long visibleMs) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            logger.log("device banner rejected: not on main thread");
            return false;
        }
        if (text == null || text.trim().isEmpty()) {
            logger.log("device banner rejected: empty text");
            return false;
        }
        try {
            dismiss();
            windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                logger.log("device banner rejected: window service missing");
                return false;
            }
            Drawable icon = resolveIcon(iconResName);
            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            // 不画底色：焦点通知本身就是图标 + 文字，任何填充都会变成新的"黑窟窿"。
            root.setPadding(dp(12), dp(4), dp(12), dp(4));
            if (icon != null) {
                ImageView iconView = new ImageView(context);
                iconView.setImageDrawable(icon);
                root.addView(iconView, new LinearLayout.LayoutParams(dp(22), dp(22)));
            }
            TextView label = new TextView(context);
            label.setText(text);
            label.setTextSize(14);
            label.setTextColor(textColor == null ? Color.WHITE : textColor);
            label.setShadowLayer(6f, 0f, 1f, TEXT_SHADOW_COLOR);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (icon != null) labelParams.leftMargin = dp(6);
            root.addView(label, labelParams);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.token = new Binder();
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.y = dp(8);
            params.setTitle("FocusRestore device notification banner");
            windowManager.addView(root, params);

            long duration = visibleMs > 0 ? visibleMs : DEFAULT_VISIBLE_MS;
            handler.removeCallbacks(hideRunnable);
            handler.postDelayed(hideRunnable, duration);
            logger.log("device banner shown text=" + text + " color=" + textColor
                    + " icon=" + iconResName + " duration=" + duration);
            return true;
        } catch (Throwable error) {
            logger.error("device banner show", error);
            dismiss();
            return false;
        }
    }

    /** 收起横幅；可安全重复调用。 */
    void dismiss() {
        handler.removeCallbacks(hideRunnable);
        LinearLayout view = root;
        WindowManager manager = windowManager;
        root = null;
        windowManager = null;
        if (view == null || manager == null) return;
        try {
            manager.removeViewImmediate(view);
        } catch (Throwable error) {
            logger.error("device banner dismiss", error);
        }
    }

    /** ROM 侧图标以资源名下发（如 miui_statusbar_dnd_mode_on），依次在系统界面与插件包内解析。 */
    private Drawable resolveIcon(String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            int id = context.getResources().getIdentifier(name, "drawable", SYSTEM_UI_PACKAGE);
            if (id == 0) {
                id = context.getResources().getIdentifier(name, "drawable", PLUGIN_PACKAGE);
            }
            if (id == 0) {
                logger.log("device banner icon missing name=" + name);
                return null;
            }
            return context.getDrawable(id);
        } catch (Throwable error) {
            logger.error("device banner icon " + name, error);
            return null;
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
