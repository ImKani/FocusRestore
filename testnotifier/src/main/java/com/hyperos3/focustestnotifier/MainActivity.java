/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focustestnotifier;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/** 独立手动测试工具，合成样本与既有捕获载荷分组展示。 */
public class MainActivity extends Activity {
    private static final String TAG = "HyperOS3FocusRestore";
    private static final int REQUEST_POST_NOTIFICATIONS = 1001;
    private static final long CLEAR_SETTLE_MS = 400L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingPost;
    private CheckBox clearFirst;
    private int samplePosition;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) samplePosition = state.getInt("samplePosition", 0);
        samplePosition = NotificationTestCases.ALL.indexOf(NotificationTestCases.at(samplePosition));
        setContentView(buildUi(state == null || state.getBoolean("clearFirst", true)));
    }

    private View buildUi(boolean clearBeforePost) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.addView(text("FocusRestore 焦点通知测试 " + BuildConfig.VERSION_NAME, 20, true));
        root.addView(text("包名：" + getPackageName()
                + "\n测试模块转换时，请开启超级岛内容转换，并将本工具加入“强制转换超级岛应用”。"
                + "ROM可能给模板加上原生焦点标记；发送成功仅表示已提交通知。", 13, false));
        clearFirst = new CheckBox(this);
        clearFirst.setText("发送前先清除本工具其它测试通知（推荐）");
        clearFirst.setChecked(clearBeforePost);
        root.addView(clearFirst, matchWrap());
        root.addView(text("布局与图标样本", 18, true));
        ArrayAdapter<NotificationTestCases.Spec> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, NotificationTestCases.ALL);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        Spinner choice = new Spinner(this);
        choice.setAdapter(adapter);
        choice.setSelection(samplePosition);
        choice.setContentDescription("测试通知类型");
        final TextView hint = text(NotificationTestCases.at(samplePosition).hint, 13, false);
        choice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                samplePosition = position;
                hint.setText(NotificationTestCases.at(position).hint);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        root.addView(choice, matchWrap());
        root.addView(hint, matchWrap());
        button(root, "发送所选样本", () -> {
            final NotificationTestCases.Spec spec = NotificationTestCases.at(samplePosition);
            schedulePost(() -> NotificationTestSender.post(this, spec));
        });
        root.addView(text("关闭发送前清除后，多类样本可并存测试左右切换。媒体与设备通知请用真实播放或连接场景。", 13, false));
        button(root, "取消全部测试通知", () -> {
            cancelPending();
            try {
                clearNotifications();
                feedback("已请求清除本工具的测试通知。");
            } catch (RuntimeException failure) { failed("clear", failure); }
        });
        button(root, "通知权限设置", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            } catch (RuntimeException failure) { failed("notification-settings", failure); }
        });
        root.addView(text("既有协议回归样本", 18, true));
        root.addView(text("保留出行、外卖、RemoteViews及其它协议样本。完整/紧凑模式决定解析结果，历史预期以当前ROM表现核对。", 13, false));
        for (final FocusTestNotifier.Spec spec : FocusTestNotifier.specs()) {
            button(root, spec.label, () -> schedulePost(() -> {
                FocusTestNotifier.post(this, spec);
                return "已提交通知：" + spec.label;
            }));
            root.addView(text("预期：" + spec.expected, 12, false));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private interface PostAction { String post(); }

    private void schedulePost(PostAction action) {
        // 新发送、权限申请和清理都使旧排队发送失效；不在授权回调自动补发。
        cancelPending();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQUEST_POST_NOTIFICATIONS);
                feedback("允许通知后请再次点击发送；若不再提示，请打开通知权限设置。");
            } catch (RuntimeException failure) { failed("request-permission", failure); }
            return;
        }
        try {
            android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
            if (manager == null || !manager.areNotificationsEnabled()) {
                feedback("请在通知权限设置中允许本工具发送通知。");
                return;
            }
            boolean cleared = clearFirst.isChecked();
            if (cleared) clearNotifications();
            pendingPost = () -> {
                pendingPost = null;
                if (isFinishing() || isDestroyed()) return;
                try { feedback(action.post()); }
                catch (RuntimeException failure) { failed("post", failure); }
            };
            // 先让旧通知移除到达SystemUI，避免新样本仍被旧焦点挡住。
            handler.postDelayed(pendingPost, cleared ? CLEAR_SETTLE_MS : 0L);
        } catch (RuntimeException failure) { failed("prepare-post", failure); }
    }

    private void clearNotifications() {
        FocusTestNotifier.cancelAll(this);
        NotificationTestSender.clear(this);
    }

    private void cancelPending() {
        if (pendingPost != null) handler.removeCallbacks(pendingPost);
        pendingPost = null;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("samplePosition", samplePosition);
        state.putBoolean("clearFirst", clearFirst.isChecked());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        cancelPending();
        super.onDestroy();
    }

    private void failed(String stage, RuntimeException failure) {
        Log.e(TAG, "notification-test tool failed stage=" + stage, failure);
        feedback("操作失败（" + stage + "），请检查权限和通知渠道，详见日志。");
    }
    private void feedback(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void button(LinearLayout root, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        root.addView(button, matchWrap());
    }
    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setTextIsSelectable(true);
        view.setPadding(0, dp(4), 0, dp(8));
        return view;
    }
    private static ViewGroup.LayoutParams matchWrap() {
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
