/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 设置页规则管理与读取授权。仓库在同一 App 进程内共用，页面退出不打断已确认提交。 */
final class IslandRuleImportUi {
    private static final String TAG = "FocusRestoreRules";
    private static IslandRuleRepository store;

    private final SettingsActivity activity;
    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(command -> {
        Thread thread = new Thread(command, "FocusRestore-rule-settings");
        thread.setDaemon(true);
        return thread;
    });
    private TextView statusView;
    private Button projectionButton;
    private IslandRuleEditorUi.Page projectionDialog;
    private long projectionPage;
    private boolean projectionBusy;
    private final IslandRuleManagerUi manager;
    private Button managerButton;
    private Runnable createFromCapture;
    private long stateRequest;
    private enum Access { STANDARD, EMPTY, REQUIRED, APPROVED, RECOVER }
    private Access access = Access.STANDARD;
    private byte[] approvedProjection;
    private String status = "";
    private long savedRevision;
    private final Runnable refreshStatus = this::render;
    private boolean resumed;
    private boolean destroyed;
    IslandRuleImportUi(SettingsActivity activity) {
        this.activity = activity;
        this.app = activity.getApplicationContext();
        manager = new IslandRuleManagerUi(activity, worker, this, this::setStatus);
    }

    void bind(TextView statusView, Button projection, Button manage, Runnable createFromCapture) {
        this.createFromCapture = createFromCapture;
        managerButton = manage;
        manager.setActive(resumed);
        managerButton.setOnClickListener(v -> openManager());
        this.statusView = statusView;
        projectionButton = projection;
        projectionButton.setOnClickListener(view -> viewProjection());
        refreshSavedRevision();
    }

    void unbind() {
        closeProjectionDialog();
        manager.setActive(false);
        if (managerButton != null) managerButton.setOnClickListener(null);
        managerButton = null;
        createFromCapture = null;
        if (projectionButton != null) projectionButton.setOnClickListener(null);
        projectionButton = null;
        statusView = null;
    }

    void resume() {
        resumed = true;
        manager.setActive(managerButton != null);
        render();
        refreshSavedRevision();
    }

    private void openManager() {
        projectionPage++;
        manager.open(approvedProjection == null ? null : this::viewProjection, createFromCapture);
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
                        && !projectionBusy && projectionDialog == null
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
        IslandRuleEditorUi.Page page = projectionDialog;
        projectionDialog = null;
        if (page == null) return;
        page.dialog.setOnDismissListener(null);
        page.release();
        page.dialog.dismiss();
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
        StringBuilder message = new StringBuilder("允许 SystemUI 读取应用范围、通知类型的结构标识、所选字段路径及其排列顺序。此设备上与 SystemUI 共享系统身份的组件也可能读取这些配置。示例文字只在本地编辑和用户选择的规则包中使用。新增、编辑或启用后需要重新确认。停用或删除只收缩已批准的范围。\n");
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (IslandRuleProjection.Row row : preview.snapshot.rows) {
            message.append('\n').append(IslandRuleManagerUi.listName(app, names, row.packageName));
            if (row.notificationType != null) {
                message.append('\n').append(IslandRuleEditorUi.typeLabel(row.notificationType))
                        .append(" · ").append(row.notificationType.paths.size()).append(" 个类型字段");
            }
            for (String path : row.paths) message.append('\n').append(path);
            message.append('\n');
        }
        return message.toString();
    }

    private void showProjectionConfirmation(IslandRuleProjectionConsent.Preview preview, String message, boolean canRevoke) {
        if (!resumed || destroyed || projectionDialog != null || activity.isFinishing() || activity.isDestroyed()) return;
        IslandRuleEditorUi ui = new IslandRuleEditorUi(activity);
        IslandRuleEditorUi.Page page = ui.new Page("规则读取授权");
        page.scrolling().addView(ui.primary(message, 14), ui.spaced(0));
        page.action("关闭", false, this::closeProjectionDialog);
        if (canRevoke || (preview != null && preview.approved)) {
            page.action(canRevoke ? "清除授权记录" : "撤销授权", false,
                    () -> updateProjection(page, null));
        } else if (preview != null && !preview.snapshot.rows.isEmpty()) {
            Button approve = page.action("允许读取", true, () -> updateProjection(page, preview));
            approve.setFilterTouchesWhenObscured(true);
        }
        projectionDialog = page;
        page.dialog.setOnDismissListener(dialog -> {
            if (projectionDialog == page) closeProjectionDialog();
        });
        page.show();
    }

    private void updateProjection(IslandRuleEditorUi.Page page, IslandRuleProjectionConsent.Preview preview) {
        if (!resumed || destroyed || projectionBusy || projectionDialog != page || activity.isFinishing()) return;
        projectionBusy = true;
        closeProjectionDialog();
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
        unbind();
        worker.shutdown();
    }

    private void setStatus(String message) { status = message; render(); }

    private void render() {
        handler.removeCallbacks(refreshStatus);
        if (statusView == null) return;
        boolean completeReady = IslandRuleAppliedStatus.confirms(savedRevision);
        boolean authorizationAction = access == Access.REQUIRED || access == Access.RECOVER;
        projectionButton.setVisibility(authorizationAction && !completeReady ? android.view.View.VISIBLE : android.view.View.GONE);
        projectionButton.setText(access == Access.RECOVER ? "重新检查读取授权" : "允许 SystemUI 读取应用范围和所选字段路径");
        projectionButton.setEnabled(!projectionBusy);
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
            store = new IslandRuleRepository(parent.toPath().resolve(IslandRuleRepository.DIRECTORY_NAME),
                    IslandRuleImportUi::syncDirectory);
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
        if ("USER_LOCKED".equals(failure.getMessage())) return "请先解锁设备再操作规则。";
        Log.w(TAG, "rule import IO failed: " + failure.getClass().getSimpleName());
        return "读取或保存规则失败，请检查文件访问与 App 私有存储。";
    }

}
