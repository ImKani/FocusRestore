/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 设置页只持有捕获会话和最近记录入口；不再从设置页写出任何文件。 */
final class IslandCaptureUi {
    private static final long REFRESH_MS = 1_500;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::refresh;
    private Button startButton;
    private Button endButton;
    private TextView statusView;
    private Button recentButton;
    private final IslandCaptureEditorUi recent;
    private boolean resumed;
    private boolean destroyed;
    private boolean busy;
    private String message = "捕获默认关闭。";
    private IBinder connection;
    private IslandCaptureRuntime.Status status;
    private int previewCount = -1;

    IslandCaptureUi(SettingsActivity activity, IslandRuleImportUi ruleImport) {
        app = activity.getApplicationContext();
        recent = new IslandCaptureEditorUi(activity, text -> { message = text; render(); }, ruleImport);
    }

    void bind(Button start, Button end, TextView view, Button recentButton) {
        startButton = start;
        endButton = end;
        statusView = view;
        this.recentButton = recentButton;
        recentButton.setOnClickListener(v -> openRecent());
        start.setOnClickListener(v -> start());
        end.setOnClickListener(v -> end());
        render();
        refresh();
    }

    boolean openRecent() {
        if (!resumed || recentButton == null || !active() || !status.ready || previewCount <= 0) return false;
        recent.open(connection, status.generation);
        return true;
    }

    void unbind() {
        previewCount = -1;
        recent.close();
        main.removeCallbacks(poll);
        startButton = endButton = recentButton = null;
        statusView = null;
    }

    void resume() { resumed = true; refresh(); }
    void pause() {
        resumed = false;
        previewCount = -1;
        recent.close();
        main.removeCallbacks(poll);
        render();
    }
    void destroy() {
        destroyed = true;
        unbind();
        recent.destroy();
        commands.shutdownNow();
    }

    private void refresh() {
        if (destroyed || !resumed || statusView == null || busy) return;
        main.removeCallbacks(poll);
        busy = true;
        commands.execute(() -> {
            IBinder bridge = null;
            IslandCaptureRuntime.Status live = null;
            try {
                bridge = IslandCaptureProvider.bridge(app);
                if (bridge != null) live = IslandCaptureIpc.status(bridge);
            } catch (IOException | RuntimeException failure) {
                // Provider 注册与 SystemUI 发布之间可能短暂不可用。
                try { IslandCaptureProvider.requestBridge(app); }
                catch (RuntimeException unavailable) { /* 下次刷新再试。 */ }
            }
            IBinder resultBridge = bridge;
            IslandCaptureRuntime.Status result = live;
            IslandCaptureProvider.BridgeRegistration previewRegistration = IslandCaptureProvider.currentRegistration(bridge);
            int currentPreviewCount = -1;
            if (previewRegistration != null && live != null && live.ready && live.generation > 0) {
                try { currentPreviewCount = IslandCaptureIpc.previewList(bridge, live.generation).size(); }
                catch (IOException unavailable) { /* 预览门禁独立于最近记录状态。 */ }
            }
            int resultPreviewCount = currentPreviewCount;
            main.post(() -> {
                if (destroyed) return;
                applyStatus(resultBridge, result);
                if (resumed && statusView != null && previewRegistration != null
                        && IslandCaptureProvider.currentRegistration(resultBridge) == previewRegistration) {
                    previewCount = resultPreviewCount;
                    recent.onStatus(resultBridge, result, resultPreviewCount >= 0);
                } else {
                    recent.close();
                }
                busy = false;
                render();
                schedule();
            });
        });
    }

    private void schedule() {
        if (resumed && statusView != null) {
            main.removeCallbacks(poll);
            main.postDelayed(poll, REFRESH_MS);
        }
    }

    private void applyStatus(IBinder bridge, IslandCaptureRuntime.Status live) {
        previewCount = -1;
        long previousGeneration = status == null ? 0 : status.generation;
        boolean invalidated = live == null || bridge != connection || previousGeneration != live.generation
                || !live.ready || live.generation <= 0;
        if (invalidated) recent.close();
        connection = bridge;
        status = live;
    }

    private void start() {
        if (!resumed || busy || active() || status == null || !status.ready || connection == null) return;
        IBinder bridge = connection;
        long epoch = status.epoch;
        mutate(bridge, () -> IslandCaptureIpc.start(bridge, epoch) > 0
                ? "捕获已开启，请触发要查看的焦点通知。" : "未能开启捕获，请查看当前捕获状态。");
    }

    private void end() {
        if (!active() || busy || connection == null) return;
        IBinder bridge = connection;
        long generation = status.generation;
        recent.close();
        mutate(bridge, () -> {
            IslandCaptureIpc.end(bridge, generation);
            return "捕获已结束，本次样本已清除。";
        });
    }

    private interface Mutation { String run() throws IOException; }
    private void mutate(IBinder bridge, Mutation mutation) {
        busy = true;
        render();
        commands.execute(() -> {
            IslandCaptureRuntime.Status live = null;
            String result;
            try {
                result = mutation.run();
                live = IslandCaptureIpc.status(bridge);
            } catch (IOException | RuntimeException failure) { result = "捕获会话不可用，请重新检查连接。"; }
            IslandCaptureRuntime.Status current = live;
            String feedback = result;
            main.post(() -> {
                if (destroyed) return;
                applyStatus(bridge, current);
                busy = false;
                message = feedback;
                render();
                schedule();
            });
        });
    }

    private boolean active() { return status != null && status.generation > 0; }
    private void render() {
        if (statusView == null) return;
        boolean active = active();
        boolean ready = status != null && status.ready;
        startButton.setEnabled(ready && !active);
        endButton.setEnabled(active);
        recentButton.setEnabled(resumed && ready && active && previewCount > 0);
        String preview = previewCount < 0 ? "" : " · " + previewCount + " 条最近记录";
        String mode = status != null && status.mode != 0 ? "OS" + status.mode + " " : "";
        startButton.setText("开启 " + mode + "样本捕获");
        statusView.setText(mode + (active ? "捕获中" + preview
                : captureStatusText(status)) + "\n" + message);
    }

    private static String captureStatusText(IslandCaptureRuntime.Status status) {
        if (status == null) return "捕获连接未建立。请确认 SystemUI 已加载模块。";
        switch (status.availability) {
            case READY: return "捕获已关闭";
            case WAITING_SOURCE: return "已连接，正在等待活动通知入口就绪。";
            case UNSUPPORTED_SOURCE: return "当前 SystemUI 的活动通知入口不受支持。";
            case LOCKED: return "请解锁设备并点亮屏幕。";
            case USER_UNAVAILABLE: return "当前用户未通过捕获授权，请使用前台主用户。";
            case OBSERVER_UNAVAILABLE: return "锁屏或用户状态监听不可用。";
            default: return "捕获连接未建立。请确认 SystemUI 已加载模块。";
        }
    }
}
