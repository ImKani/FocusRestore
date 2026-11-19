/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 设置页只持有会话和样本编号。唯一文件输出为 SAF 所选文档。 */
final class IslandCaptureUi {
    static final int REQUEST = 4104;
    private static final long REFRESH_MS = 1_500;
    private static final long WRITE_TIMEOUT_MS = 60_000;
    private final SettingsActivity activity;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private final ExecutorService documents = Executors.newSingleThreadExecutor();
    private final ExecutorService cancellations = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::refresh;
    private Button startButton;
    private Button sampleButton;
    private Button exportButton;
    private Button endButton;
    private Button cancelButton;
    private TextView statusView;
    private Button recentButton;
    private Dialog dialog;
    private final IslandCaptureEditorUi recent;
    private boolean resumed;
    private boolean destroyed;
    private boolean busy;
    private boolean writerRunning;
    private String message = "捕获默认关闭。";
    private IBinder connection;
    private IslandCaptureRuntime.Status status;
    private int previewCount = -1;
    private long selectedSample;
    private Selection picker;
    private WriteOperation writing;
    private static final class Selection {
        final IBinder bridge;
        final long generation;
        final long sample;
        Selection(IBinder bridge, long generation, long sample) {
            this.bridge = bridge;
            this.generation = generation;
            this.sample = sample;
        }
    }

    private static final class WriteOperation {
        final CancellationSignal signal = new CancellationSignal();
        volatile boolean cancelled;
        volatile ParcelFileDescriptor descriptor;
        Runnable timeout;
    }

    IslandCaptureUi(SettingsActivity activity, IslandRuleImportUi ruleImport) {
        this.activity = activity;
        app = activity.getApplicationContext();
        recent = new IslandCaptureEditorUi(activity, text -> { message = text; render(); }, ruleImport);
    }

    void bind(Button start, Button samples, Button export, Button end, Button cancel, TextView view, Button recentButton) {
        startButton = start;
        sampleButton = samples;
        exportButton = export;
        endButton = end;
        cancelButton = cancel;
        statusView = view;
        this.recentButton = recentButton;
        recentButton.setOnClickListener(v -> {
            if (active() && resumed && previewCount > 0) recent.open(connection, status.generation);
        });
        start.setOnClickListener(v -> start());
        samples.setOnClickListener(v -> chooseSample());
        export.setOnClickListener(v -> chooseDocument());
        end.setOnClickListener(v -> end());
        cancel.setOnClickListener(v -> cancelWrite("导出已取消，文件可能不完整。"));
        render();
        refresh();
    }

    void unbind() {
        previewCount = -1;
        recent.close();
        main.removeCallbacks(poll);
        if (dialog != null) dialog.dismiss();
        dialog = null;
        startButton = sampleButton = exportButton = endButton = cancelButton = recentButton = null;
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
        picker = null;
        cancelWrite("导出已中断，文件可能不完整。");
        recent.destroy();
        commands.shutdownNow();
        documents.shutdown();
        cancellations.shutdown();
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
                catch (IOException unavailable) { /* 预览门禁独立于旧样本和导出。 */ }
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
        if (live == null || bridge != connection || previousGeneration != live.generation) selectedSample = 0;
        if (live != null && selectedSample != 0) {
            boolean present = false;
            for (IslandCaptureRuntime.Sample sample : live.samples) if (sample.sequence == selectedSample) present = true;
            if (!present) selectedSample = 0;
        }
        if (writing != null && (live == null || bridge != connection || previousGeneration != live.generation)) {
            cancelWrite("捕获授权已失效，文件可能不完整。");
        }
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
        if (writing != null) cancelWrite("捕获已结束，文件可能不完整。");
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

    private void chooseSample() {
        if (!active() || status.samples.isEmpty()) return;
        IslandCaptureRuntime.Status observed = status;
        IBinder bridge = connection;
        String[] rows = new String[observed.samples.size()];
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);
        int checked = -1;
        for (int i = 0; i < rows.length; i++) {
            IslandCaptureRuntime.Sample sample = observed.samples.get(i);
            rows[i] = "样本 " + sample.sequence + " · " + format.format(new Date(sample.capturedAt));
            if (selectedSample == sample.sequence) checked = i;
        }
        dialog = new AlertDialog.Builder(activity).setTitle("选择捕获样本")
                .setSingleChoiceItems(rows, checked, (d, which) -> {
                    long sequence = observed.samples.get(which).sequence;
                    if (active() && connection == bridge && status.generation == observed.generation) {
                        for (IslandCaptureRuntime.Sample sample : status.samples) {
                            if (sample.sequence == sequence) selectedSample = sequence;
                        }
                    }
                    d.dismiss();
                    render();
                }).setNegativeButton("取消", null).show();
    }

    private void chooseDocument() {
        if (!active() || selectedSample <= 0 || picker != null || writerRunning) return;
        picker = new Selection(connection, status.generation, selectedSample);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "FocusRestore-island-sample.json");
        try { activity.startActivityForResult(intent, REQUEST); }
        catch (RuntimeException failure) {
            picker = null;
            message = "系统文件选择器不可用。";
        }
        render();
    }

    void onDocumentResult(int resultCode, Intent result) {
        Selection selection = picker;
        picker = null;
        if (destroyed) return;
        Uri uri = result == null ? null : result.getData();
        if (selection == null || resultCode != Activity.RESULT_OK || uri == null || !"content".equals(uri.getScheme())) {
            message = resultCode == Activity.RESULT_OK ? "保存选择已失效，文件可能为空。" : "已取消导出。";
            render();
            return;
        }
        WriteOperation operation = new WriteOperation();
        writing = operation;
        writerRunning = true;
        message = "正在写入脱敏 JSON…";
        operation.timeout = () -> {
            if (writing == operation) cancelWrite("写入超时，文件可能不完整。");
        };
        main.postDelayed(operation.timeout, WRITE_TIMEOUT_MS);
        documents.execute(() -> writeDocument(operation, selection, uri));
        render();
    }

    private void writeDocument(WriteOperation operation, Selection selection, Uri uri) {
        IslandCaptureIpc.Ticket ticket = null;
        boolean saved = false;
        try {
            if (operation.cancelled) throw new IOException("CAPTURE_WRITE_CANCELLED");
            ticket = IslandCaptureIpc.prepare(selection.bridge, selection.generation, selection.sample);
            IslandCaptureIpc.Ticket fixed = ticket;
            IslandCaptureDocumentWriter.Source source = new IslandCaptureDocumentWriter.Source() {
                @Override public boolean authorized() throws IOException { return IslandCaptureIpc.check(selection.bridge, fixed); }
                @Override public byte[] read(int offset) throws IOException { return IslandCaptureIpc.chunk(selection.bridge, fixed, offset); }
            };
            IslandCaptureDocumentWriter.require(source, () -> operation.cancelled);
            ParcelFileDescriptor descriptor = app.getContentResolver().openFileDescriptor(uri, "wt", operation.signal);
            if (descriptor == null) throw new IOException("CAPTURE_DOCUMENT_UNAVAILABLE");
            operation.descriptor = descriptor;
            try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                IslandCaptureDocumentWriter.write(output, fixed.size, source, () -> operation.cancelled);
            } finally { operation.descriptor = null; }
            IslandCaptureDocumentWriter.require(source, () -> operation.cancelled);
            saved = true;
        } catch (IOException | RuntimeException failure) {
            // 文档提供方可能已创建或写入部分文件，保留用户选择的文档并明确报告。
        } finally {
            if (ticket != null) {
                try { IslandCaptureIpc.release(selection.bridge, ticket); }
                catch (IOException failure) { /* 失效连接已无法读取这份票据。 */ }
            }
            boolean success = saved;
            main.post(() -> {
                main.removeCallbacks(operation.timeout);
                writerRunning = false;
                if (destroyed) return;
                if (writing == operation) {
                    writing = null;
                    message = success ? "脱敏 JSON 已保存，可对照规则规范编写后导入。"
                            : "导出未完成，授权可能已失效或文档不可写，文件可能不完整。";
                }
                render();
            });
        }
    }

    private void cancelWrite(String feedback) {
        WriteOperation operation = writing;
        if (operation == null) return;
        writing = null;
        operation.cancelled = true;
        main.removeCallbacks(operation.timeout);
        // 关闭已打开的 FD，唤醒阻塞写入。取消标志也覆盖 FD 尚未返回的情况。
        cancellations.execute(() -> {
            try {
                operation.signal.cancel();
            } finally {
                ParcelFileDescriptor descriptor = operation.descriptor;
                if (descriptor != null) {
                    try { descriptor.close(); }
                    catch (IOException failure) { /* 写入线程仍会报告取消。 */ }
                }
            }
        });
        message = feedback;
        render();
    }

    private boolean active() { return status != null && status.generation > 0; }
    private void render() {
        if (statusView == null) return;
        boolean active = active();
        boolean ready = status != null && status.ready;
        boolean exporting = picker != null || writerRunning;
        startButton.setEnabled(ready && !active && !exporting);
        sampleButton.setText(selectedSample == 0 ? "选择样本" : "已选样本 " + selectedSample);
        sampleButton.setEnabled(active && !status.samples.isEmpty() && !exporting);
        exportButton.setEnabled(active && selectedSample != 0 && !exporting);
        endButton.setEnabled(active);
        recentButton.setEnabled(resumed && ready && active && previewCount > 0);
        cancelButton.setVisibility(writing == null ? View.GONE : View.VISIBLE);
        String preview = previewCount < 0 ? "" : " · " + previewCount + " 条最近记录";
        String mode = status != null && status.mode != 0 ? "OS" + status.mode + " " : "";
        startButton.setText("开启 " + mode + "样本捕获");
        statusView.setText(mode + (active ? "捕获中 · " + status.samples.size() + " 个样本" + preview
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
