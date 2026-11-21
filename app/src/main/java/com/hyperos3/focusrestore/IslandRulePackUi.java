/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/** 规则包专用 SAF 入口。选择器、暂存确认及文档写入各有明确的所有者。 */
final class IslandRulePackUi {
    static final int REQUEST_IMPORT = 4105;
    static final int REQUEST_EXPORT = 4106;
    private static final String REQUEST_STATE = "rulePack.request";
    private static final String INTERRUPTED_STATE = "rulePack.interrupted";
    private static final String BLOCKED = "上一项批量规则包任务尚未退出，暂不能重试。";
    private static final IslandRulePackSaf.Flight FLIGHT = new IslandRulePackSaf.Flight();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(
            action -> daemon(action, "FocusRestore-rule-pack"));
    private static final ExecutorService RELEASE = Executors.newSingleThreadExecutor(
            action -> daemon(action, "FocusRestore-rule-pack-release"));

    private final SettingsActivity activity;
    private final Context app;
    private final ContentResolver resolver;
    private final LongConsumer onSaved;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refreshAvailability = this::render;
    private Button importer;
    private Button exporter;
    private Button cancel;
    private TextView statusView;
    private boolean resumed;
    private boolean destroyed;
    private boolean abandonPicker;
    private int request;
    private String status = "在设备之间导入或导出规则包。";
    private Task current;
    private long pageGeneration;
    private IslandRulePackReviewUi review;
    private AlertDialog exportDialog;
    private Prepared pending;
    private IslandRuleRepository.PackExport outgoing;

    IslandRulePackUi(SettingsActivity activity, LongConsumer onSaved) {
        this.activity = activity;
        this.app = activity.getApplicationContext();
        resolver = app.getContentResolver();
        this.onSaved = onSaved;
    }

    void bind(Button importer, Button exporter, Button cancel, TextView statusView) {
        this.importer = importer;
        this.exporter = exporter;
        this.cancel = cancel;
        this.statusView = statusView;
        importer.setOnClickListener(view -> choose(REQUEST_IMPORT));
        exporter.setOnClickListener(view -> chooseExportMode());
        cancel.setOnClickListener(view -> cancel(IslandRulePackSaf.Stop.CANCELLED));
        render();
    }

    void unbind() {
        if (importer != null) {
            importer.setOnClickListener(null);
            exporter.setOnClickListener(null);
            cancel.setOnClickListener(null);
            if (request != 0) {
                abandonPicker = true;
                outgoing = null;
            }
        }
        importer = exporter = cancel = null;
        statusView = null;
        invalidateWork();
    }

    void restore(Bundle state) {
        if (state == null) return;
        int restored = state.getInt(REQUEST_STATE);
        if (restored == REQUEST_IMPORT || restored == REQUEST_EXPORT) {
            request = restored;
            abandonPicker = true;
        }
        if (request != 0 || state.getBoolean(INTERRUPTED_STATE)) status = "页面已重建，请重新操作规则包。";
    }

    void save(Bundle state) {
        state.putInt(REQUEST_STATE, request);
        state.putBoolean(INTERRUPTED_STATE, current != null || pending != null);
    }

    void resume() {
        resumed = true;
        render();
        confirmIfReady();
    }

    void pause() {
        resumed = false;
        invalidateWork();
    }

    void destroy() {
        destroyed = true;
        unbind();
        if (current != null) clearTimeout(current);
        current = null;
        outgoing = null;
    }

    private void invalidateWork() {
        pageGeneration++;
        main.removeCallbacks(refreshAvailability);
        if (exportDialog != null) exportDialog.dismiss();
        cancel(IslandRulePackSaf.Stop.CANCELLED);
        if (pending != null) abandonConfirmation();
        else closeReview();
    }

    private void closeReview() {
        IslandRulePackReviewUi shown = review;
        review = null;
        if (shown != null) shown.close();
    }

    private void abandonConfirmation() {
        Prepared prepared = pending;
        pending = null;
        closeReview();
        release(prepared);
        setStatus("已取消规则包确认，当前规则未修改。");
    }

    private boolean canShowReview() {
        return resumed && !destroyed && !activity.isFinishing() && !activity.isDestroyed()
                && importer != null && review == null;
    }

    private boolean canStart() {
        return resumed && !destroyed && !activity.isFinishing() && importer != null && !busy();
    }

    private boolean busy() { return current != null || request != 0 || pending != null || FLIGHT.occupied(); }

    private void choose(int code) {
        if (!canStart()) {
            outgoing = null;
            return;
        }
        request = code;
        abandonPicker = false;
        boolean importing = code == REQUEST_IMPORT;
        Intent intent = new Intent(importing ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(importing ? "*/*" : "application/octet-stream");
        if (!importing) intent.putExtra(Intent.EXTRA_TITLE, "FocusRestore-rules-r" + outgoing.revision + ".frpk");
        setStatus(importing ? "请选择 .frpk 规则包。" : "请选择规则包保存位置。");
        try {
            activity.startActivityForResult(intent, code);
        } catch (RuntimeException failure) {
            request = 0;
            outgoing = null;
            setStatus("无法打开系统文件选择器。");
        }
    }

    void onDocumentResult(int code, int resultCode, Intent result) {
        if (destroyed || code != request) return;
        request = 0;
        IslandRuleRepository.PackExport exported = outgoing;
        outgoing = null;
        boolean importing = code == REQUEST_IMPORT;
        Uri uri = result == null ? null : result.getData();
        IslandRulePackSaf.Picker selection = IslandRulePackSaf.picker(resultCode == Activity.RESULT_OK,
                uri == null ? null : uri.toString(), isDocument(uri));
        if (selection == IslandRulePackSaf.Picker.CANCELLED) {
            setStatus("已取消规则包文件选择。");
        } else if (selection == IslandRulePackSaf.Picker.INVALID) {
            setStatus("文件选择器未返回有效的 SAF 文档。");
        } else if (abandonPicker || importer == null || activity.isFinishing()) {
            if (importing) setStatus("规则包操作已失效，请重新导入。");
            else discardTarget(uri);
        } else if (importing) {
            startRead(uri);
        } else if (exported != null) {
            startWrite(uri, exported);
        } else {
            discardTarget(uri);
        }
    }

    private boolean isDocument(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) return false;
        try { return DocumentsContract.isDocumentUri(app, uri); }
        catch (RuntimeException failure) { return false; }
    }

    private void chooseExportMode() {
        if (!canStart() || exportDialog != null) return;
        CheckBox clearValues = new CheckBox(activity);
        clearValues.setText("清除示例文字（脱敏）");
        clearValues.setChecked(true);
        LinearLayout content = new LinearLayout(activity);
        int horizontal = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(horizontal, 0, horizontal, 0);
        content.addView(clearValues);
        exportDialog = new AlertDialog.Builder(activity)
                .setTitle("导出规则包")
                .setMessage("清除示例文字后，导入者可查看字段路径和顺序。取消勾选会将通知中的示例值写入文件，可能包含私人信息。")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (ignored, which) -> prepareExport(!clearValues.isChecked()))
                .create();
        exportDialog.setOnDismissListener(ignored -> exportDialog = null);
        exportDialog.show();
    }

    private void prepareExport(boolean includeSampleValues) {
        if (!canStart()) return;
        Task task = begin("正在生成规则包…", false);
        if (task == null) return;
        submit(task, main, () -> {
            try {
                IslandRuleRepository.PackExport result = IslandRulePackSaf.prepareExport(
                        IslandRuleImportUi.repository(app), task.operation, includeSampleValues);
                return () -> {
                    boolean deliver = task.operation.canDeliver(current == task, destroyed);
                    if (!finish(task, null) || !deliver) return;
                    if (canStart()) {
                        outgoing = result;
                        choose(REQUEST_EXPORT);
                    } else setStatus("规则包操作已失效，请重新导出。");
                };
            } catch (IOException | RuntimeException failure) {
                return failureResult(task, failure, "规则包生成失败，仓库中存在不可导出的规则或存储不可用。");
            }
        });
    }

    private void startRead(Uri uri) {
        Task task = begin("正在读取并校验规则文件…", false);
        if (task == null) return;
        submit(task, main, () -> {
            try {
                IslandRuleRepository repository = IslandRuleImportUi.repository(app);
                IslandRuleRepository.PendingBatch imported = IslandRulePackSaf.prepare(repository,
                        () -> task.input(resolver, uri), this::environment, task.operation);
                Prepared prepared = new Prepared(repository, imported);
                return () -> {
                    boolean deliver = task.operation.canDeliver(current == task, destroyed);
                    if (!finish(task, null) || !deliver || importer == null || activity.isFinishing()) {
                        release(prepared);
                        return;
                    }
                    pending = prepared;
                    setStatus("请核对规则包并确认导入。");
                    confirmIfReady();
                };
            } catch (IOException | RuntimeException failure) {
                return failureResult(task, failure, null);
            }
        });
    }

    private void confirmIfReady() {
        if (!canShowReview() || pending == null) return;
        Prepared prepared = pending;
        IslandRulePackReviewUi shown = IslandRulePackReviewUi.preview(activity, prepared.candidate,
                replacements -> commit(prepared, replacements));
        review = shown;
        shown.show(() -> {
            if (review == shown && pending == prepared) abandonConfirmation();
        });
    }

    private void commit(Prepared prepared, Set<Integer> replacements) {
        if (!resumed || destroyed || activity.isFinishing() || pending != prepared) return;
        Task task = begin("正在处理规则包…", false);
        if (task == null) {
            setStatus(BLOCKED);
            return;
        }
        long generation = pageGeneration;
        pending = null;
        closeReview();
        submit(task, main, () -> {
            IslandRuleRepository.BatchResult completed = null;
            boolean changed = false;
            String text;
            try {
                completed = IslandRulePackSaf.commit(prepared.repository, prepared.candidate, replacements,
                        this::environment, task.operation);
                changed = completed.changed;
                text = IslandRulePackReviewUi.resultHeading(completed) + "。\n"
                        + IslandRulePackReviewUi.resultDetail(completed);
            } catch (IOException | RuntimeException failure) {
                if (failure instanceof IslandRuleRepository.Failure) {
                    IslandRuleRepository.Code code = ((IslandRuleRepository.Failure) failure).code;
                    changed = code == IslandRuleRepository.Code.DURABILITY_UNCERTAIN
                            || code == IslandRuleRepository.Code.COMMITTED_CLEANUP_FAILED;
                    if (code == IslandRuleRepository.Code.DURABILITY_UNCERTAIN) {
                        text = "规则集已切换，保存状态待确认，请重新查看已保存规则。";
                    } else if (changed) {
                        text = "App 已保存规则，旧文件清理待完成。请重新查看已保存规则。";
                    } else text = IslandRulePackSaf.failure(failure);
                } else text = IslandRulePackSaf.failure(failure);
            } finally {
                prepared.close();
            }
            if (changed) {
                try { resolver.notifyChange(IslandRuleProvider.HEAD, null, 0); }
                catch (RuntimeException ignored) { /* OS4 定期对账会重新检查仓库修订。 */ }
                IslandRuleProjectionProvider.notifyChanged(resolver);
            }
            boolean manifestChanged = changed;
            IslandRuleRepository.BatchResult result = completed;
            String resultText = text;
            return () -> {
                boolean deliver = task.operation.canDeliver(current == task, destroyed);
                if (!finish(task, task.stopped == null || manifestChanged ? resultText : null)) return;
                if (manifestChanged) onSaved.accept(0);
                // 切页后仍刷新已生效的修订，但不再弹出旧页面的规则明细。
                if (deliver && result != null && generation == pageGeneration && canShowReview()) showResult(result);
            };
        });
    }

    private void showResult(IslandRuleRepository.BatchResult result) {
        IslandRulePackReviewUi shown = IslandRulePackReviewUi.result(activity, result, completed -> {
            if (review != completed) return;
            closeReview();
            long revision = result.confirmableRevision();
            // 只有主动完成结果页才继续请求授权。返回或切页仍可从设置页手动确认。
            if (revision > 0 && canShowReview()) onSaved.accept(revision);
        });
        review = shown;
        shown.show(() -> {
            if (review == shown) closeReview();
        });
    }

    private IslandRuleImportValidator.Environment environment() throws IOException {
        IslandRuleImportUi.requireUnlocked(app);
        return IslandRuleImportUi.environment(app);
    }

    private void startWrite(Uri uri, IslandRuleRepository.PackExport exported) {
        Task task = begin("正在写入规则包…", true);
        if (task == null) return;
        IslandRulePackSaf.OutputDocument document = outputDocument(task, resolver, uri);
        submitOutput(task, () -> {
            try {
                IslandRulePackSaf.write(exported, document, task.operation);
                return "规则包已导出，共 " + exported.bytes.length + " 字节。";
            } catch (IOException | RuntimeException failure) {
                return IslandRulePackSaf.failure(failure);
            }
        });
    }

    private void discardTarget(Uri uri) {
        Task task = begin("正在清理未完成的规则包文件…", true);
        if (task == null) return;
        IslandRulePackSaf.OutputDocument document = outputDocument(task, resolver, uri);
        submitOutput(task, () -> "规则包操作已失效。"
                + IslandRulePackSaf.cleanupText(IslandRulePackSaf.discard(document)));
    }

    private void submitOutput(Task task, Supplier<String> work) {
        // 提供方可能长期不返回；后台文档任务不持有已关闭的设置页。
        WeakReference<IslandRulePackUi> page = new WeakReference<>(this);
        submit(task, main, () -> {
            String message = work.get();
            return () -> {
                IslandRulePackUi owner = page.get();
                if (owner != null) owner.finish(task, message);
            };
        });
    }

    private static IslandRulePackSaf.OutputDocument outputDocument(Task task, ContentResolver resolver, Uri uri) {
        return new IslandRulePackSaf.OutputDocument() {
            @Override public OutputStream open() throws IOException { return task.output(resolver, uri); }
            @Override public IslandRulePackSaf.Cleanup discard() { return discardDocument(resolver, uri); }
        };
    }

    private static IslandRulePackSaf.Cleanup discardDocument(ContentResolver resolver, Uri uri) {
        try {
            if (DocumentsContract.deleteDocument(resolver, uri)) return IslandRulePackSaf.Cleanup.REMOVED;
        } catch (IOException | RuntimeException ignored) { /* 不支持删除时尝试截断。 */ }
        try (ParcelFileDescriptor file = resolver.openFileDescriptor(uri, "wt")) {
            if (file == null || file.getStatSize() != 0) return IslandRulePackSaf.Cleanup.UNCONFIRMED;
            file.checkError();
        } catch (IOException | RuntimeException ignored) {
            return IslandRulePackSaf.Cleanup.UNCONFIRMED;
        }
        return IslandRulePackSaf.Cleanup.EMPTIED;
    }

    private Runnable failureResult(Task task, Throwable failure, String generic) {
        String message = generic != null && !(failure instanceof IslandRulePackSaf.Stopped
                || failure instanceof IslandRuleRepository.Failure) ? generic : IslandRulePackSaf.failure(failure);
        boolean reportsCleanup = failure instanceof IslandRulePackSaf.WriteFailure;
        return () -> finish(task, task.stopped == null || reportsCleanup ? message : null);
    }

    private Task begin(String message, boolean ownsOutput) {
        Task task = new Task(ownsOutput);
        if (current != null || !FLIGHT.acquire(task)) {
            if (ownsOutput) setStatus(IslandRulePackSaf.timeoutText(true) + "请核对本次选择的文件。");
            else render();
            return null;
        }
        current = task;
        task.timeout = () -> {
            if (current != task || destroyed) return;
            long remaining = task.operation.remainingMs();
            if (remaining > 0) main.postDelayed(task.timeout, remaining);
            else updateDeadline(task);
        };
        main.postDelayed(task.timeout, task.operation.remainingMs());
        setStatus(message);
        return task;
    }

    private static void submit(Task task, Handler main, Supplier<Runnable> work) {
        WORKER.execute(() -> main.post(FLIGHT.run(task, task.operation, work)));
    }

    private void cancel(IslandRulePackSaf.Stop reason) {
        Task task = current;
        if (task == null || !task.operation.stop(reason)) return;
        task.stopped = reason;
        setStatus(IslandRulePackSaf.stopped(reason) + (task.ownsOutput ? "等待确认文件清理结果。" : ""));
    }

    private IslandRulePackSaf.Deadline updateDeadline(Task task) {
        IslandRulePackSaf.Deadline deadline = task.operation.deadline();
        if (deadline == IslandRulePackSaf.Deadline.EXPIRED) {
            task.stopped = IslandRulePackSaf.Stop.TIMEOUT;
            setStatus(IslandRulePackSaf.timeoutText(task.ownsOutput));
        } else if (deadline == IslandRulePackSaf.Deadline.COMMIT_PENDING) {
            task.commitPending = true;
            setStatus("仓库已开始原子切换，正在确认实际保存结果，不能取消。");
        }
        return deadline;
    }

    private boolean finish(Task task, String message) {
        if (!IslandRulePackSaf.Operation.canUpdate(current == task, destroyed)) return false;
        IslandRulePackSaf.Deadline deadline = updateDeadline(task);
        clearTimeout(task);
        current = null;
        if (deadline != IslandRulePackSaf.Deadline.EXPIRED && message != null) setStatus(message);
        else render();
        return true;
    }

    private void clearTimeout(Task task) {
        main.removeCallbacks(task.timeout);
        task.timeout = null;
    }

    private void setStatus(String message) {
        status = message;
        render();
    }

    private void render() {
        main.removeCallbacks(refreshAvailability);
        if (exportDialog != null) exportDialog.dismiss();
        if (destroyed || importer == null || !resumed) return;
        boolean occupied = FLIGHT.occupied();
        boolean waitingForExit = occupied && (current == null || current.stopped != null);
        importer.setEnabled(!busy());
        exporter.setEnabled(!busy());
        cancel.setVisibility(current != null && current.stopped == null && !current.commitPending ? View.VISIBLE : View.GONE);
        String text = waitingForExit ? status + "\n" + BLOCKED : status;
        if (!text.contentEquals(statusView.getText())) statusView.setText(text);
        // 新页面没有旧任务的回调；只在可见且等待旧工作位时刷新按钮。
        if (occupied && current == null) main.postDelayed(refreshAvailability, 500);
    }

    private static Thread daemon(Runnable command, String name) {
        Thread thread = new Thread(command, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void release(Prepared prepared) {
        if (prepared != null) RELEASE.execute(prepared::close);
    }

    private static final class Prepared {
        final IslandRuleRepository repository;
        final IslandRuleRepository.PendingBatch candidate;

        Prepared(IslandRuleRepository repository, IslandRuleRepository.PendingBatch candidate) {
            this.repository = repository;
            this.candidate = candidate;
        }

        void close() {
            try { candidate.close(); }
            catch (IOException | RuntimeException ignored) { /* 仓库会在下次操作回收未引用文件。 */ }
        }
    }

    private static final class Task {
        final IslandRulePackSaf.Operation operation = new IslandRulePackSaf.Operation(System::nanoTime);
        final boolean ownsOutput;
        Runnable timeout;
        IslandRulePackSaf.Stop stopped;
        boolean commitPending;

        Task(boolean ownsOutput) { this.ownsOutput = ownsOutput; }

        InputStream input(ContentResolver resolver, Uri uri) throws IOException {
            return new ParcelFileDescriptor.AutoCloseInputStream(open(resolver, uri, "r"));
        }

        OutputStream output(ContentResolver resolver, Uri uri) throws IOException {
            ParcelFileDescriptor file = open(resolver, uri, "wt");
            return new ParcelFileDescriptor.AutoCloseOutputStream(file) {
                @Override public void flush() throws IOException {
                    super.flush();
                    file.checkError();
                }
            };
        }

        private ParcelFileDescriptor open(ContentResolver resolver, Uri uri, String mode) throws IOException {
            operation.check();
            ParcelFileDescriptor file = resolver.openFileDescriptor(uri, mode);
            if (file == null) throw new IOException("NO_DOCUMENT_DESCRIPTOR");
            try { operation.check(); }
            catch (IOException cancelled) {
                try { file.close(); } catch (IOException | RuntimeException failure) { cancelled.addSuppressed(failure); }
                throw cancelled;
            }
            return file;
        }
    }
}
