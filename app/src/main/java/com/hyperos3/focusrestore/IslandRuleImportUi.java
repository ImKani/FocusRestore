/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.UserManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 设置页独立的 SAF 导入会话。仓库在同一 App 进程内共用，页面退出不打断已确认提交。 */
final class IslandRuleImportUi {
    static final int REQUEST = 4103;
    private static final String TAG = "FocusRestoreRules";
    private static final long TIMEOUT_MS = 60_000L;
    private static IslandRuleRepository store;

    private final Activity activity;
    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(command -> {
        Thread thread = new Thread(command, "FocusRestore-rule-import");
        thread.setDaemon(true);
        return thread;
    });
    private Button importButton;
    private Button cancelButton;
    private TextView statusView;
    private Button projectionButton;
    private AlertDialog projectionDialog;
    private long projectionPage;
    private boolean projectionBusy;
    private final IslandRuleManagerUi manager;
    private Button managerButton;
    private long stateRequest;
    private enum Access { STANDARD, EMPTY, REQUIRED, APPROVED, RECOVER }
    private Access access = Access.STANDARD;
    private byte[] approvedProjection;
    private String status = "";
    private long savedRevision;
    private final Runnable refreshStatus = this::render;
    private boolean choosing;
    private boolean resumed;
    private boolean destroyed;
    private boolean committing;
    private ReadOperation reading;
    private IslandRuleRepository repository;
    private IslandRuleRepository.Pending candidate;
    private AlertDialog dialog;

    IslandRuleImportUi(Activity activity) {
        this.activity = activity;
        this.app = activity.getApplicationContext();
        manager = new IslandRuleManagerUi(activity, worker, this, this::setStatus);
    }

    void bind(Button importer, Button cancel, TextView statusView, Button projection, Button manage) {
        managerButton = manage;
        manager.setActive(resumed);
        managerButton.setOnClickListener(v -> openManager());
        importButton = importer;
        cancelButton = cancel;
        this.statusView = statusView;
        projectionButton = projection;
        importButton.setOnClickListener(view -> choose());
        cancelButton.setOnClickListener(view -> cancelRead("已取消规则导入。"));
        projectionButton.setOnClickListener(view -> viewProjection());
        refreshSavedRevision();
    }

    void unbind() {
        closeProjectionDialog();
        manager.setActive(false);
        if (managerButton != null) managerButton.setOnClickListener(null);
        managerButton = null;
        if (projectionButton != null) projectionButton.setOnClickListener(null);
        projectionButton = null;
        importButton = null;
        cancelButton = null;
        statusView = null;
    }

    void restore(Bundle state) {
        if (state == null) return;
        choosing = state.getBoolean("rules.choosing");
        if (state.getBoolean("rules.committing")) {
            status = "页面在保存期间重建，当前页面无法确认保存结果。";
        } else if (state.getBoolean("rules.interrupted")) {
            status = "页面已重建，请重新导入规则。";
        } else if (choosing) {
            status = "请选择规则 JSON 文件。";
        }
        render();
    }

    void save(Bundle state) {
        state.putBoolean("rules.choosing", choosing);
        state.putBoolean("rules.committing", committing);
        state.putBoolean("rules.interrupted", reading != null || candidate != null);
    }

    void resume() {
        resumed = true;
        manager.setActive(managerButton != null);
        showConfirmation();
        render();
        refreshSavedRevision();
    }

    private void openManager() {
        projectionPage++;
        manager.open(approvedProjection == null ? null : this::viewProjection);
    }

    void beginCaptureEditor() {
        projectionPage++;
        manager.dismissPage();
    }

    void refreshSavedRevision() { afterCommit(0, false); }

    enum Next { NONE, REVIEW, LIST }

    static Next afterSave(long committed, long current, boolean currentPage,
            IslandRuleProjectionConsent.Preview preview, boolean strictReady, boolean fromManager) {
        if (!currentPage || committed <= 0 || committed != current) return Next.NONE;
        if (preview != null && preview.snapshot.revision == current && !preview.approved
                && !preview.snapshot.rows.isEmpty() && !strictReady) return Next.REVIEW;
        return fromManager ? Next.LIST : Next.NONE;
    }

    void afterCommit(long committed, boolean fromManager) {
        if (destroyed) return;
        long page = projectionPage;
        long request = ++stateRequest;
        approvedProjection = null;
        access = Access.STANDARD;
        savedRevision = 0;
        render();
        worker.execute(() -> {
            long revision = -1;
            Access state = Access.STANDARD;
            byte[] payload = null;
            IslandRuleProjectionConsent.Preview pendingReview = null;
            String reviewMessage = null;
            try {
                IslandRuleRepository owner = repository(app);
                revision = owner.revision();
                if (revision > 0 && IslandRuleProjectionProvider.available(app)) {
                    state = Access.RECOVER;
                    IslandRuleProjectionConsent.Preview preview = IslandRuleProjectionProvider.consent(app).preview(owner);
                    revision = preview.snapshot.revision;
                    if (preview.snapshot.rows.isEmpty()) state = Access.EMPTY;
                    else if (!preview.approved) {
                        state = Access.REQUIRED;
                        if (revision == committed) {
                            reviewMessage = projectionMessage(preview);
                            pendingReview = preview;
                        }
                    } else {
                        state = Access.APPROVED;
                        payload = preview.snapshot.payload();
                    }
                }
            } catch (IOException | RuntimeException failure) {
                Log.w(TAG, "rule saved or consent state unavailable", failure);
            }
            final long saved = revision;
            final Access checked = state;
            final byte[] approved = payload;
            final IslandRuleProjectionConsent.Preview proposal = pendingReview;
            final String message = reviewMessage;
            handler.post(() -> {
                if (destroyed || stateRequest != request) return;
                savedRevision = saved;
                access = checked;
                approvedProjection = approved;
                render();
                // 只有这次保存发起的交接能弹窗。退后台、切页或已有其他操作时留给手动入口。
                boolean currentPage = resumed && projectionButton != null && projectionPage == page
                        && !projectionBusy && projectionDialog == null && !busy()
                        && !activity.isFinishing() && !activity.isDestroyed();
                Next next = afterSave(committed, saved, currentPage, proposal,
                        proposal != null && IslandRuleAppliedStatus.confirms(saved), fromManager);
                if (next == Next.REVIEW) showProjectionConfirmation(proposal, message, false);
                else if (next == Next.LIST) openManager();
            });
        });
    }

    void pause() {
        resumed = false;
        manager.setActive(false);
        closeProjectionDialog();
        handler.removeCallbacks(refreshStatus);
    }

    private void closeProjectionDialog() {
        projectionPage++;
        if (projectionDialog == null) return;
        projectionDialog.setOnDismissListener(null);
        projectionDialog.dismiss();
        projectionDialog = null;
    }

    private void viewProjection() {
        if (!resumed || destroyed || projectionBusy || projectionDialog != null) return;
        long page = ++projectionPage;
        projectionBusy = true;
        render();
        worker.execute(() -> {
            IslandRuleProjectionConsent.Preview preview = null;
            String message;
            boolean available = IslandRuleProjectionProvider.available(app);
            try {
                if (available) {
                    preview = IslandRuleProjectionProvider.consent(app).preview(repository(app));
                    message = projectionMessage(preview);
                } else {
                    message = "当前设备无需此项读取授权。";
                }
            } catch (IOException | RuntimeException failure) {
                Log.w(TAG, "rule projection preview failed", failure);
                message = "无法核对当前读取授权。可先清除授权记录，再重新核对所选字段。";
            }
            IslandRuleProjectionConsent.Preview proposal = preview;
            boolean canRevoke = available && preview == null;
            String confirmationText = message;
            handler.post(() -> {
                if (destroyed) return;
                projectionBusy = false;
                render();
                if (!resumed || projectionButton == null || projectionPage != page || activity.isFinishing()) return;
                showProjectionConfirmation(proposal, confirmationText, canRevoke);
            });
        });
    }

    private String projectionMessage(IslandRuleProjectionConsent.Preview preview) {
        if (preview.snapshot.rows.isEmpty()) return "当前没有需要授权的规则。";
        StringBuilder message = new StringBuilder("允许 SystemUI 读取应用范围和所选字段路径，以及它们的排列顺序。此设备上与 SystemUI 共享系统身份的组件也可能读取这些配置。通知正文和样本不会传输。新增、编辑或启用规则后需要重新确认。停用或删除只收缩已批准的范围。\n");
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (IslandRuleProjection.Row row : preview.snapshot.rows) {
            message.append('\n').append(IslandRuleManagerUi.listName(app, names, row.packageName));
            for (String path : row.paths) message.append('\n').append(path);
            message.append('\n');
        }
        return message.toString();
    }

    private void showProjectionConfirmation(IslandRuleProjectionConsent.Preview preview, String message, boolean canRevoke) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("规则读取授权")
                .setMessage(message)
                .setNegativeButton("关闭", null);
        if (canRevoke) {
            builder.setNeutralButton("清除授权记录", (dialog, which) -> updateProjection(null));
        } else if (preview != null && preview.approved) {
            builder.setNeutralButton("撤销授权", (dialog, which) -> updateProjection(null));
        } else if (preview != null && !preview.snapshot.rows.isEmpty()) {
            builder.setPositiveButton("允许读取", (dialog, which) -> updateProjection(preview));
        }
        projectionDialog = builder.create();
        projectionDialog.setOnDismissListener(dialog -> projectionDialog = null);
        projectionDialog.show();
        Button approve = projectionDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (approve != null) approve.setFilterTouchesWhenObscured(true);
    }

    private void updateProjection(IslandRuleProjectionConsent.Preview preview) {
        if (!resumed || destroyed || projectionBusy || projectionDialog == null || activity.isFinishing()) return;
        projectionBusy = true;
        render();
        worker.execute(() -> {
            String result;
            try {
                IslandRuleProjectionConsent consent = IslandRuleProjectionProvider.consent(app);
                if (preview == null) {
                    consent.revoke();
                    result = "已撤销读取授权。";
                } else {
                    if (!IslandRuleProjectionProvider.available(app)) throw new IOException("RULE_PROJECTION_CALLER");
                    consent.approve(repository(app), preview);
                    result = "已允许 SystemUI 读取本次确认的应用范围和字段路径。";
                }
            } catch (IOException | RuntimeException failure) {
                Log.w(TAG, "rule projection consent change failed: " + failure.getClass().getSimpleName());
                result = preview == null ? "无法确认读取授权已撤销，请重新检查。"
                        : "读取尚未获准，请重新检查应用范围和字段路径。";
            }
            IslandRuleProjectionProvider.notifyChanged(app.getContentResolver());
            String confirmation = result;
            handler.post(() -> {
                if (destroyed) return;
                projectionBusy = false;
                refreshSavedRevision();
                setStatus(confirmation);
            });
        });
    }

    void destroy() {
        destroyed = true;
        cancelRead(null);
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
            dialog = null;
        }
        releaseCandidate();
        unbind();
        worker.shutdown();
    }

    private void choose() {
        if (destroyed || busy() || reading != null) return;
        choosing = true;
        status = "请选择规则 JSON 文件。";
        render();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            activity.startActivityForResult(intent, REQUEST);
        } catch (RuntimeException failure) {
            choosing = false;
            setStatus("无法打开系统文件选择器。");
        }
    }

    void onDocumentResult(int resultCode, Intent result) {
        if (!choosing || destroyed) return;
        choosing = false;
        Uri uri = result == null ? null : result.getData();
        if (resultCode != Activity.RESULT_OK || uri == null || !"content".equals(uri.getScheme())) {
            setStatus(resultCode == Activity.RESULT_OK ? "文件选择器未返回可用的规则文件。" : "已取消规则文件选择。");
            return;
        }
        ReadOperation operation = new ReadOperation();
        reading = operation;
        operation.timeout = () -> {
            if (reading == operation) cancelRead("读取规则超时。");
        };
        handler.postDelayed(operation.timeout, TIMEOUT_MS);
        setStatus("正在读取并校验规则…");
        operation.future = worker.submit(() -> readRule(operation, uri));
    }

    private void readRule(ReadOperation operation, Uri uri) {
        IslandRuleRepository.Pending pending = null;
        try {
            IslandRuleRepository owner = repository(app);
            ParcelFileDescriptor file = app.getContentResolver().openFileDescriptor(uri, "r", operation.signal);
            if (file == null) throw new IOException("NO_DOCUMENT_DESCRIPTOR");
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(file)) {
                operation.attach(input);
                pending = owner.prepare(input, null, true, 0, environment(app));
            } finally {
                operation.detach();
            }
            IslandRuleRepository.Pending prepared = pending;
            handler.post(() -> receive(operation, owner, prepared));
        } catch (IOException | RuntimeException failure) {
            if (pending != null) close(pending);
            handler.post(() -> {
                if (reading != operation) return;
                finishRead(operation);
                setStatus(failureText(failure));
            });
        }
    }

    private void receive(ReadOperation operation, IslandRuleRepository owner, IslandRuleRepository.Pending prepared) {
        if (reading != operation || destroyed) {
            // 已关停页面的 IO 线程时，仍需释放在取消边界附近产生的暂存。
            closeOffMain(prepared);
            return;
        }
        finishRead(operation);
        candidate = prepared;
        repository = owner;
        setStatus(prepared.empty || prepared.canApprove(true) ? "等待确认规则导入。" : "规则校验未通过。");
        showConfirmation();
    }

    private void showConfirmation() {
        if (!resumed || destroyed || activity.isFinishing() || candidate == null || dialog != null) return;
        IslandRuleRepository.Pending prepared = candidate;
        boolean acceptable = prepared.empty || prepared.canApprove(true);
        boolean force = acceptable && !prepared.canApprove(false);
        StringBuilder message = new StringBuilder();
        if (prepared.empty) {
            message.append("确认后将清空全部规则。");
        }
        if (prepared.entry != null) {
            message.append("新增并启用规则 · ").append(IslandRuleManagerUi.appName(app, prepared.validation.rule.meta.packageName))
                    .append("\n自定义规则可能在焦点通知中显示敏感信息。只导入你信任的规则。");
        }
        if (!prepared.empty) for (IslandRuleImportValidator.Finding finding : prepared.validation.findings) {
            if (BuildConfig.DEBUG) Log.d(TAG, "DIAG import finding=" + finding.code + " path=" + finding.path);
            if (finding.stage == IslandRuleImportValidator.Stage.L2) {
                message.append(finding.blocking ? "\n系统界面版本与规则声明不符。" : "\n无法核实规则声明的系统界面版本。");
            } else if (finding.blocking) message.append("\n规则格式、字段或匹配条件不受支持。");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(prepared.empty ? "确认清空规则集" : acceptable ? "确认导入自定义规则" : "规则被拒绝")
                .setMessage(message);
        if (acceptable) {
            builder.setNegativeButton("取消", null);
            builder.setPositiveButton(force ? "忽略版本差异并保存" : "确认保存", (ignored, which) -> saveConfirmed(prepared, force));
        } else {
            builder.setPositiveButton("关闭", null);
        }
        dialog = builder.create();
        dialog.setOnDismissListener(ignored -> {
            dialog = null;
            if (candidate == prepared) {
                releaseCandidate();
                setStatus(acceptable ? "已取消规则导入。" : "规则校验未通过，未保存。");
            }
        });
        dialog.show();
    }

    private void saveConfirmed(IslandRuleRepository.Pending prepared, boolean force) {
        if (destroyed || activity.isFinishing() || candidate != prepared || !resumed) return;
        IslandRuleRepository owner = repository;
        candidate = null;
        repository = null;
        committing = true;
        setStatus("正在保存规则…");
        worker.execute(() -> {
            String result;
            long saved = 0;
            boolean changed = false;
            try {
                requireUnlocked(app);
                saved = owner.commit(prepared, force, environment(app));
                changed = true;
                result = prepared.empty ? "已保存空规则集。" : "规则保存成功。";
            } catch (IOException | RuntimeException failure) {
                result = failureText(failure);
                if (failure instanceof IslandRuleRepository.Failure) {
                    IslandRuleRepository.Code code = ((IslandRuleRepository.Failure) failure).code;
                    changed = code == IslandRuleRepository.Code.DURABILITY_UNCERTAIN
                            || code == IslandRuleRepository.Code.COMMITTED_CLEANUP_FAILED;
                    if (code == IslandRuleRepository.Code.COMMITTED_CLEANUP_FAILED) saved = prepared.baselineRevision + 1;
                }
            } finally {
                close(prepared);
            }
            if (changed) {
                try { app.getContentResolver().notifyChange(IslandRuleProvider.HEAD, null, 0); }
                catch (RuntimeException failure) { Log.w(TAG, "rule notify failed; reconciliation will retry"); }
                IslandRuleProjectionProvider.notifyChanged(app.getContentResolver());
            }
            long confirmedSaved = saved;
            boolean manifestChanged = changed;
            String statusAfterSave = result;
            handler.post(() -> {
                if (destroyed) return;
                committing = false;
                if (manifestChanged) {
                    savedRevision = confirmedSaved;
                    refreshSavedRevision();
                }
                setStatus(statusAfterSave);
            });
        });
    }

    private void releaseCandidate() {
        IslandRuleRepository.Pending prepared = candidate;
        candidate = null;
        repository = null;
        if (prepared != null) closeOffMain(prepared);
    }

    private void closeOffMain(IslandRuleRepository.Pending prepared) {
        if (!worker.isShutdown()) worker.execute(() -> close(prepared));
        else startCloser(() -> close(prepared));
    }

    private static void close(IslandRuleRepository.Pending prepared) {
        try { prepared.close(); }
        catch (IOException | RuntimeException failure) { Log.w(TAG, "rule stage cleanup failed"); }
    }

    private void cancelRead(String reason) {
        ReadOperation operation = reading;
        if (operation == null) return;
        finishRead(operation);
        operation.cancel();
        if (reason != null) setStatus(reason);
    }

    private void finishRead(ReadOperation operation) {
        reading = null;
        handler.removeCallbacks(operation.timeout);
    }

    private boolean busy() { return choosing || candidate != null || committing || reading != null; }

    private void setStatus(String message) { status = message; render(); }

    private void render() {
        handler.removeCallbacks(refreshStatus);
        if (importButton == null) return;
        importButton.setEnabled(!busy());
        managerButton.setEnabled(!busy());
        boolean completeReady = IslandRuleAppliedStatus.confirms(savedRevision);
        boolean authorizationAction = access == Access.REQUIRED || access == Access.RECOVER;
        projectionButton.setVisibility(authorizationAction && !completeReady ? android.view.View.VISIBLE : android.view.View.GONE);
        projectionButton.setText(access == Access.RECOVER ? "重新检查读取授权" : "允许 SystemUI 读取应用范围和所选字段路径");
        projectionButton.setEnabled(!projectionBusy && !busy());
        cancelButton.setVisibility(reading == null ? android.view.View.GONE : android.view.View.VISIBLE);
        String application = savedRevision < 0 ? "无法读取已保存规则，请解锁后重新打开页面。"
                : savedRevision == 0 ? "尚无已保存的规则集" : "App 已保存";
        if (savedRevision > 0) {
            if (completeReady) application += "\n规则已就绪";
            else if (access == Access.EMPTY) application += "\n当前没有此设备可读取的已启用字段规则。";
            else if (access == Access.RECOVER) application += "\n无法核对读取授权，请重新检查。";
            else if (access == Access.REQUIRED) application += "\n规则等待读取授权";
            else if (access == Access.APPROVED && IslandRuleAppliedStatus.confirmsProjection(approvedProjection)) {
                application += "\n已授权的规则已就绪";
            } else application += "\n规则尚待 SystemUI 确认";
        }
        statusView.setText(status.isEmpty() ? application : application + "\n" + status);
        if (resumed && savedRevision > 0) handler.postDelayed(refreshStatus, 2_000);
    }

    static synchronized IslandRuleRepository repository(Context app) throws IOException {
        requireUnlocked(app);
        if (store == null) {
            // 当前 Application 使用默认 CE Context。意外收到 DP Context 时拒绝打开。
            if (app.isDeviceProtectedStorage()) throw new IOException("CREDENTIAL_STORAGE_UNAVAILABLE");
            java.io.File parent = app.getNoBackupFilesDir();
            if (parent == null) throw new IOException("CREDENTIAL_STORAGE_UNAVAILABLE");
            // 首次创建 no_backup 时，也确认它在 App 数据目录中的入口。
            syncDirectory(parent.toPath().getParent());
            store = new IslandRuleRepository(parent.toPath().resolve("island-rules"), IslandRuleImportUi::syncDirectory);
        }
        return store;
    }

    static void requireUnlocked(Context app) throws IOException {
        UserManager users = app.getSystemService(UserManager.class);
        if (users == null || !users.isUserUnlocked()) throw new IOException("USER_LOCKED");
    }

    static void syncDirectory(Path path) throws IOException {
        try {
            FileDescriptor fd = Os.open(path.toString(), OsConstants.O_RDONLY, 0);
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw new IOException("RULE_EXPECTED_DIRECTORY");
                Os.fsync(fd);
            } finally {
                Os.close(fd);
            }
        } catch (ErrnoException failure) {
            throw new IOException("RULE_DIRECTORY_SYNC_FAILED", failure);
        }
    }

    static IslandRuleImportValidator.Environment environment(Context app) {
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo("com.android.systemui", 0);
            return new IslandRuleImportValidator.Environment(info.versionName, info.getLongVersionCode());
        } catch (PackageManager.NameNotFoundException | RuntimeException failure) {
            return new IslandRuleImportValidator.Environment(null, null);
        }
    }

    static String failureText(Throwable failure) {
        if (BuildConfig.DEBUG) Log.w(TAG, "DIAG single rule: " + failure.getMessage());
        if (failure instanceof IslandRuleRepository.Failure) {
            IslandRuleRepository.Code code = ((IslandRuleRepository.Failure) failure).code;
            switch (code) {
                case RULE_SIZE: return "规则文件超过 65,536 字节。";
                case REPOSITORY_CAPACITY: return "规则文件合计超过 2 MiB，已保留当前版本。";
                case ACTIVE_LIMIT: return "活跃规则已达 50 条。";
                case BASELINE_CHANGED: return "规则仓库已更新，请重新选择文件并确认。";
                case ENVIRONMENT_CHANGED: return "SystemUI 版本信息已变化，请重新导入并确认。";
                case DURABILITY_UNCERTAIN: return "规则清单已切换，落盘待确认。";
                case COMMITTED_CLEANUP_FAILED: return "规则已保存到 App，旧文件清理失败。";
                case VALIDATION: return "规则格式、匹配条件或设备兼容性未通过校验。";
                case UNKNOWN_RULE: return "找不到该规则，请重新打开规则列表。";
                default: return "规则操作失败，请重新打开列表并检查可用空间。";
            }
        }
        if ("USER_LOCKED".equals(failure.getMessage())) return "请先解锁设备再导入规则。";
        Log.w(TAG, "rule import IO failed: " + failure.getClass().getSimpleName());
        return "读取或保存规则失败，请检查文件访问与 App 私有存储。";
    }

    private static void startCloser(Runnable action) {
        Thread thread = new Thread(action, "FocusRestore-rule-close");
        thread.setDaemon(true);
        thread.start();
    }

    private static final class ReadOperation {
        final CancellationSignal signal = new CancellationSignal();
        Future<?> future;
        Runnable timeout;
        private InputStream stream;
        private boolean cancelled;

        synchronized void attach(InputStream input) throws IOException {
            if (cancelled) throw new IOException("RULE_READ_CANCELLED");
            stream = input;
        }

        synchronized void detach() { stream = null; }

        void cancel() {
            InputStream open;
            synchronized (this) {
                cancelled = true;
                open = stream;
            }
            future.cancel(true);
            startCloser(() -> {
                try { signal.cancel(); }
                catch (RuntimeException failure) { Log.w(TAG, "rule provider cancellation failed"); }
                if (open == null) return;
                try { open.close(); }
                catch (IOException failure) { Log.w(TAG, "rule input close failed"); }
            });
        }
    }
}
