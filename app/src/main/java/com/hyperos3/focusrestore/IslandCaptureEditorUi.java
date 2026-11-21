/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** 只在当前安全窗口展示原文。App 本地批准使用 PREVIEW 复核，严格 SAVE 仍走独立 IPC。 */
final class IslandCaptureEditorUi {
    private static final String TAG = "HyperOS3FocusRestore";
    private static final long CHECK_MS = 750;
    private static final long VIEW_LEASE_MS = 4_000;
    private static final long PREVIEW_MS = 60_000;
    private final SettingsActivity activity;
    private final IslandRuleEditorUi ui;
    private final Context app;
    private final Consumer<String> feedback;
    private final IslandRuleImportUi ruleUi;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable heartbeat = this::checkLive;
    private Viewer current;

    private final class Viewer {
        final IslandCaptureProvider.BridgeRegistration registration;
        final long generation;
        final IBinder.DeathRecipient death = () -> main.post(() -> revoke(this));
        final BroadcastReceiver screen = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { revoke(Viewer.this); }
        };
        final Map<String, String> names = new LinkedHashMap<>();
        final Map<Long, String> snippets = new LinkedHashMap<>();
        volatile boolean closed;
        volatile long lease = now() + VIEW_LEASE_MS;
        boolean registered;
        boolean linked;
        boolean checking;
        boolean snippetPending;
        boolean loadingDetails;
        boolean loadedDetails;
        boolean confirming;
        boolean saving;
        volatile Set<Long> viewport = Collections.emptySet();
        IslandRuleEditorUi.Page page;
        ListView list;
        Rows rows;
        TextView textPreview;
        LinearLayout selected;
        Button save;
        volatile Preview preview;
        Confirmation confirmation;
        List<IslandCapturePreview.Summary> metadata = Collections.emptyList();

        Viewer(IslandCaptureProvider.BridgeRegistration registration, long generation) {
            this.registration = registration;
            this.generation = generation;
        }

        boolean live() {
            return !closed && now() < lease && locallyVisible()
                    && IslandCaptureProvider.currentRegistration(registration.bridge) == registration;
        }
    }

    private static final class Preview {
        final IslandCapturePreview.Summary record;
        final IslandCaptureEditor.Lifetime lifetime;
        final Runnable expire;
        volatile IslandCaptureIpc.PreviewTicket ticket;

        Preview(IslandCapturePreview.Summary record, long deadline, Runnable expire) {
            this.record = record;
            this.lifetime = new IslandCaptureEditor.Lifetime(deadline);
            this.expire = expire;
        }
    }

    private static final class Confirmation {
        final IslandCaptureIpc.PreviewTicket ticket;
        final IslandCaptureEditor.Candidate candidate;
        final long createdAt;
        final long baseline;
        final List<IslandRuleManagerUi.Row> targets;

        Confirmation(IslandCaptureIpc.PreviewTicket ticket, IslandCaptureEditor.Candidate candidate,
                long createdAt, IslandRuleManagerUi.Listing listing) {
            this.ticket = ticket;
            this.candidate = candidate;
            this.createdAt = createdAt;
            this.baseline = listing.baseline;
            this.targets = listing.rows;
        }
    }

    IslandCaptureEditorUi(SettingsActivity activity, Consumer<String> feedback, IslandRuleImportUi ruleUi) {
        this.activity = activity;
        this.ui = new IslandRuleEditorUi(activity);
        this.feedback = feedback;
        this.ruleUi = ruleUi;
        app = activity.getApplicationContext();
    }

    void open(IBinder bridge, long generation) {
        if (current != null || !locallyVisible()) return;
        IslandCaptureProvider.BridgeRegistration registration = IslandCaptureProvider.currentRegistration(bridge);
        if (registration == null || worker.isShutdown()) return;
        ruleUi.beginCaptureEditor();
        Viewer view = new Viewer(registration, generation);
        current = view;
        try {
            bridge.linkToDeath(view.death, 0);
            view.linked = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_BACKGROUND);
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(view.screen, filter, Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(view.screen, filter);
            view.registered = true;
            view.page = ui.new Page("最近捕获记录");
            view.page.dialog.setOnDismissListener(d -> revoke(view));
            view.page.show();
            view.page.watchFocus(() -> revoke(view));
            showList(view);
            checkLive();
        } catch (android.os.RemoteException | RuntimeException failure) {
            revoke(view);
            feedback.accept("最近记录暂不可用，请重新进入捕获会话。");
        }
    }

    void onStatus(IBinder bridge, IslandCaptureRuntime.Status status, boolean permitted) {
        Viewer view = current;
        if (view != null && (!permitted || status == null || !status.ready
                || status.generation != view.generation || bridge != view.registration.bridge || !view.live())) revoke(view);
    }

    void close() { if (current != null) revoke(current); }
    void destroy() { close(); worker.shutdown(); }

    private void revoke(Viewer view) {
        if (current != view) return;
        view.closed = true;
        current = null;
        main.removeCallbacks(heartbeat);
        releasePreview(view);
        releaseList(view);
        if (view.page != null) {
            view.page.release();
            clearEditorViews(view);
            view.page.dialog.setOnDismissListener(null);
            view.page.dialog.dismiss();
            view.page = null;
        }
        if (view.registered) app.unregisterReceiver(view.screen);
        if (view.linked) view.registration.bridge.unlinkToDeath(view.death, 0);
    }

    private void releasePreview(Viewer view) {
        Preview preview = view.preview;
        view.preview = null;
        view.confirmation = null;
        view.loadingDetails = false;
        view.loadedDetails = false;
        view.confirming = false;
        if (preview == null) return;
        main.removeCallbacks(preview.expire);
        preview.lifetime.revoke();
        IslandCaptureIpc.PreviewTicket ticket = preview.ticket;
        if (ticket != null) workerClose(ticket);
    }

    private static void closeTicket(IslandCaptureIpc.PreviewTicket ticket) {
        try { IslandCaptureIpc.closePreview(ticket); }
        catch (IOException | RuntimeException unavailable) { /* 服务端已撤销或会按期限回收。 */ }
    }

    private static long now() { return SystemClock.elapsedRealtime(); }

    private boolean locallyVisible() {
        try {
            PowerManager power = app.getSystemService(PowerManager.class);
            KeyguardManager keyguard = app.getSystemService(KeyguardManager.class);
            UserManager users = app.getSystemService(UserManager.class);
            return users != null && users.isUserForeground() && users.isUserUnlocked()
                    && power != null && power.isInteractive() && keyguard != null
                    && !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked();
        } catch (RuntimeException unknown) { return false; }
    }

    private void checkLive() {
        Viewer view = current;
        if (view == null) return;
        Preview preview = view.preview;
        if (!view.live() || (preview != null && !preview.lifetime.live(now()))) {
            revoke(view);
            return;
        }
        main.removeCallbacks(heartbeat);
        main.postDelayed(heartbeat, CHECK_MS);
        if (view.checking || view.loadingDetails || view.saving) return;
        view.checking = true;
        worker.execute(() -> {
            try {
                long checkedAt = now();
                List<IslandCapturePreview.Summary> metadata = IslandCaptureIpc.previewList(view.registration, view.generation);
                if (preview != null) IslandCaptureIpc.checkPreview(preview.ticket);
                main.post(() -> applyMetadata(view, preview, metadata, checkedAt));
            } catch (IOException | RuntimeException denied) {
                main.post(() -> {
                    if (current != view) return;
                    view.checking = false;
                    if (preview == view.preview) revoke(view);
                });
            }
        });
    }

    private void applyMetadata(Viewer view, Preview preview, List<IslandCapturePreview.Summary> metadata, long checkedAt) {
        if (current != view) return;
        view.checking = false;
        if (!view.live() || now() >= checkedAt + VIEW_LEASE_MS) { revoke(view); return; }
        if (preview != view.preview) return;
        view.lease = checkedAt + VIEW_LEASE_MS;
        if (preview != null) return;
        if (!sameRecords(view.metadata, metadata)) {
            clearRows(view);
            view.metadata = metadata;
            view.rows.notifyDataSetChanged();
        }
        view.list.post(() -> refreshRows(view));
    }

    private static boolean sameRecords(List<IslandCapturePreview.Summary> left, List<IslandCapturePreview.Summary> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (left.get(i).handle != right.get(i).handle) return false;
        return true;
    }

    private void showList(Viewer view) {
        view.page.title("最近捕获记录");
        view.list = new ListView(activity);
        view.list.setSaveEnabled(false);
        view.rows = new Rows(view);
        view.list.setRecyclerListener(IslandRuleEditorUi::scrub);
        view.list.setAdapter(view.rows);
        view.page.body.addView(view.list, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView empty = label("等待新的超级岛通知…", 14);
        view.page.body.addView(empty, ui.spaced(0));
        view.page.action("关闭", false, () -> revoke(view));
        view.list.setEmptyView(empty);
        view.list.setOnItemClickListener((parent, row, index, id) -> {
            if (view.live() && index >= 0 && index < view.metadata.size() && view.metadata.get(index).fieldCount > 0) {
                openRecord(view, view.metadata.get(index));
            }
        });
        view.list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) { }
            @Override public void onScroll(AbsListView v, int first, int count, int total) { refreshRows(view); }
        });
    }

    private final class Rows extends BaseAdapter {
        private final Viewer view;
        Rows(Viewer view) { this.view = view; }
        @Override public int getCount() { return view.metadata.size(); }
        @Override public IslandCapturePreview.Summary getItem(int position) { return view.metadata.get(position); }
        @Override public long getItemId(int position) { return getItem(position).handle; }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row = recycled instanceof LinearLayout ? (LinearLayout) recycled : ui.column();
            if (row.getChildCount() == 0) {
                row.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
                row.setMinimumHeight(dp(66));
                row.setPadding(dp(10), dp(6), dp(10), dp(6));
                row.setBackground(activity.roundedBg(activity.COLOR_SURFACE, 10));
                row.addView(label("", 13));
                TextView fragment = ui.primary("", 16);
                fragment.setMaxLines(2);
                fragment.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(fragment);
            }
            IslandCapturePreview.Summary item = getItem(position);
            row.setTag(item.handle);
            ((TextView) row.getChildAt(0)).setText(identity(item));
            ((TextView) row.getChildAt(1)).setText("");
            return row;
        }
    }

    private Set<Long> visible(Viewer view) {
        Set<Long> visible = new java.util.LinkedHashSet<>();
        if (view.list == null || !view.list.isShown() || !view.list.hasWindowFocus()) return visible;
        for (int i = 0; i < view.list.getChildCount(); i++) {
            View row = view.list.getChildAt(i);
            if (row.getTag() instanceof Long && row.getBottom() > 0 && row.getTop() < view.list.getHeight()) {
                visible.add((Long) row.getTag());
            }
        }
        return visible;
    }

    private void refreshRows(Viewer view) {
        if (current != view || view.preview != null || view.list == null) return;
        if (!view.live()) { revoke(view); return; }
        Set<Long> visible = visible(view);
        if (!view.viewport.equals(visible)) view.viewport = Collections.unmodifiableSet(visible);
        view.snippets.keySet().retainAll(visible);
        paintRows(view, visible);
        if (view.snippetPending || visible.isEmpty()) return;
        long[] handles = visible.stream().filter(id -> !view.snippets.containsKey(id))
                .limit(IslandCaptureEditor.MAX_VISIBLE_BATCH).mapToLong(Long::longValue).toArray();
        if (handles.length == 0) return;
        view.snippetPending = true;
        ListView requestedList = view.list;
        Set<Long> requestedViewport = view.viewport;
        worker.execute(() -> {
            try {
                if (view.closed) return;
                if (view.viewport != requestedViewport) {
                    main.post(() -> {
                        if (current != view || view.list != requestedList) return;
                        view.snippetPending = false;
                        refreshRows(view);
                    });
                    return;
                }
                List<String> snippets = IslandCaptureIpc.visibleSnippets(view.registration, view.generation, handles);
                main.post(() -> {
                    if (current != view || view.list != requestedList) return;
                    view.snippetPending = false;
                    if (!view.live()) { revoke(view); return; }
                    if (view.viewport == requestedViewport && view.preview == null) {
                        Set<Long> stillVisible = visible(view);
                        for (int i = 0; i < handles.length; i++) {
                            if (stillVisible.contains(handles[i])) view.snippets.put(handles[i], snippets.get(i));
                        }
                        paintRows(view, stillVisible);
                    }
                    refreshRows(view);
                });
            } catch (IOException | RuntimeException denied) {
                main.post(() -> {
                    // 已离开该列表时，字段页通过自己的票据校验会话。
                    if (current == view && view.list == requestedList) revoke(view);
                });
            }
        });
    }

    private void clearRows(Viewer view) {
        view.viewport = Collections.emptySet();
        view.snippets.clear();
        paintRows(view, Collections.emptySet());
    }

    private void paintRows(Viewer view, Set<Long> visible) {
        if (view.list == null) return;
        for (int i = 0; i < view.list.getChildCount(); i++) {
            View child = view.list.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) child;
                TextView fragment = (TextView) row.getChildAt(1);
                String value = visible.contains(row.getTag()) ? view.snippets.get(row.getTag()) : null;
                if (!TextUtils.equals(fragment.getText(), value == null ? "" : value)) fragment.setText(value);
            }
        }
    }

    private void releaseList(Viewer view) {
        clearRows(view);
        ListView list = view.list;
        // setAdapter 会同步触发列表回调，先让后续工作看见该列表已失效。
        view.list = null;
        view.rows = null;
        view.snippetPending = false;
        if (list != null) {
            list.setOnScrollListener(null);
            list.setOnItemClickListener(null);
            list.setAdapter(null);
            list.setRecyclerListener(null);
        }
        view.metadata = Collections.emptyList();
    }

    private void openRecord(Viewer view, IslandCapturePreview.Summary record) {
        if (view.preview != null || !view.live()) return;
        releaseList(view);
        clearContent(view);
        view.page.title("编辑显示规则");
        view.page.body.addView(label("正在读取登记字段…", 14));
        view.page.action("取消", false, () -> revoke(view));
        long deadline = now() + PREVIEW_MS;
        Preview preview = new Preview(record, deadline, this::checkLive);
        view.preview = preview;
        view.loadingDetails = true;
        main.postDelayed(preview.expire, Math.max(0, deadline - now()));
        readPage(view, preview, 0);
    }

    private void readPage(Viewer view, Preview preview, int offset) {
        worker.execute(() -> {
            IslandCaptureIpc.PreviewTicket opened = null;
            try {
                long checkedAt = now();
                if (!view.live() || !preview.lifetime.live(checkedAt)) throw new IOException("CAPTURE_PREVIEW_REVOKED");
                IslandCaptureIpc.PreviewTicket ticket = preview.ticket;
                if (ticket == null) {
                    opened = IslandCaptureIpc.openPreview(view.registration, view.generation, preview.record.handle);
                    ticket = opened;
                }
                IslandCapturePreview.Page page = IslandCaptureIpc.readPreviewPage(ticket, offset);
                IslandCaptureIpc.PreviewTicket reply = ticket;
                boolean ownsTicket = opened != null;
                main.post(() -> {
                    if (current != view || view.preview != preview || !view.live()
                            || !preview.lifetime.acceptPage(now(), page)) {
                        if (ownsTicket) workerClose(reply);
                        revoke(view);
                        return;
                    }
                    preview.ticket = reply;
                    view.lease = checkedAt + VIEW_LEASE_MS;
                    if (page.nextOffset >= 0) readPage(view, preview, page.nextOffset);
                    else {
                        view.loadingDetails = false;
                        view.loadedDetails = true;
                        renderEditor(view, preview);
                    }
                });
            } catch (IOException | RuntimeException denied) {
                if (opened != null) closeTicket(opened);
                main.post(() -> revoke(view));
            }
        });
    }

    private void workerClose(IslandCaptureIpc.PreviewTicket ticket) {
        if (!worker.isShutdown()) worker.execute(() -> closeTicket(ticket));
    }

    private void renderEditor(Viewer view, Preview preview) {
        if (!view.live() || !preview.lifetime.live(now())) { revoke(view); return; }
        clearContent(view);
        view.confirming = false;
        view.confirmation = null;
        view.page.title("编辑显示规则");
        IslandCaptureEditor editor = preview.lifetime.editor;
        LinearLayout content = view.page.scrolling();
        content.addView(ui.heading(viewName(preview.record.packageName), 17), ui.spaced(4));
        content.addView(label(IslandRuleEditorUi.typeLabel(editor.notificationType()), 13), ui.spaced(12));
        content.addView(ui.heading("正文预览", 15), ui.spaced(4));
        view.textPreview = ui.primary("", 17);
        view.textPreview.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(view.textPreview, ui.spaced(12));
        content.addView(ui.heading("显示顺序", 15), ui.spaced(4));
        content.addView(label("长按拖动，或用上下按钮调整顺序。", 13), ui.spaced(6));
        view.selected = ui.column();
        content.addView(view.selected, ui.spaced(12));
        content.addView(ui.heading("本条通知的字段", 15), ui.spaced(4));
        content.addView(label("最多选择 " + IslandCaptureEditor.MAX_SELECTION + " 个字段。", 13), ui.spaced(8));
        Map<IslandRulePaths.Entry, CheckBox> choices = new LinkedHashMap<>();
        for (IslandCapturePreview.Field field : editor.fields()) {
            IslandRulePaths.Entry path = field.path;
            CheckBox choice = ui.choice(IslandCaptureEditor.displayValue(field), path);
            content.addView(choice, ui.spaced(6));
            choices.put(path, choice);
            choice.setOnClickListener(v -> {
                if (current != view || view.preview != preview || view.confirming) return;
                if (!view.live() || !preview.lifetime.live(now())) { revoke(view); return; }
                editor.toggle(path);
                updateChoices(editor, choices);
                renderOrder(view, preview);
            });
        }
        if (!canConfirmSave(view)) {
            content.addView(label("当前会话可预览字段。保存需要 SystemUI 授权。", 13));
        }
        view.page.action("取消编辑", false, () -> cancelEdit(view, preview));
        view.save = view.page.action("保存规则", true, () -> prepareConfirmation(view, preview));
        updateChoices(editor, choices);
        renderOrder(view, preview);
    }

    private void cancelEdit(Viewer view, Preview preview) {
        if (current != view || view.preview != preview || view.saving) return;
        releasePreview(view);
        clearContent(view);
        showList(view);
        checkLive();
    }

    private static void updateChoices(IslandCaptureEditor editor, Map<IslandRulePaths.Entry, CheckBox> choices) {
        List<IslandRulePaths.Entry> order = editor.order();
        for (Map.Entry<IslandRulePaths.Entry, CheckBox> choice : choices.entrySet()) {
            boolean checked = order.contains(choice.getKey());
            choice.getValue().setChecked(checked);
            choice.getValue().setEnabled(checked || order.size() < IslandCaptureEditor.MAX_SELECTION);
        }
    }

    private static String example(IslandCaptureEditor editor, IslandRulePaths.Entry path) {
        for (IslandCapturePreview.Field field : editor.fields()) {
            if (field.path == path) return IslandRuleEditorUi.readableExample(IslandCaptureEditor.displayValue(field));
        }
        return IslandRuleEditorUi.NO_SAMPLE;
    }

    private void renderOrder(Viewer view, Preview preview) {
        IslandCaptureEditor editor = preview.lifetime.editor;
        String text = editor.preview();
        view.textPreview.setText(text.isEmpty() ? "预览暂无正文" : text);
        IslandRuleEditorUi.setActionEnabled(view.save,
                view.loadedDetails && canConfirmSave(view) && !editor.order().isEmpty());
        IslandRuleEditorUi.scrub(view.selected);
        IslandRuleOrderUi.render(view.selected, editor.order(), path -> example(editor, path),
                (path, index) -> move(view, preview, path, index));
    }

    private void move(Viewer view, Preview preview, IslandRulePaths.Entry path, int to) {
        if (current != view || view.preview != preview || view.confirming) return;
        if (!view.live() || !preview.lifetime.live(now())) { revoke(view); return; }
        IslandCaptureEditor editor = preview.lifetime.editor;
        if (editor.move(editor.order().indexOf(path), to)) renderOrder(view, preview);
    }

    private static boolean usesLocalApproval(Viewer view) {
        return !view.registration.saveSource && view.registration.sharedPlatformUid;
    }

    private static boolean canConfirmSave(Viewer view) {
        return view.registration.saveSource || usesLocalApproval(view);
    }

    private void prepareConfirmation(Viewer view, Preview preview) {
        if (current != view || view.preview != preview || view.confirming || view.saving || !canConfirmSave(view)) return;
        if (!view.live() || !preview.lifetime.live(now())) { revoke(view); return; }
        IslandCaptureIpc.PreviewTicket ticket = preview.ticket;
        long createdAt = System.currentTimeMillis();
        IslandCaptureEditor editor = preview.lifetime.editor;
        IslandCaptureEditor.Candidate candidate = editor.candidate(preview.record.packageName, createdAt);
        IslandNotificationType type = editor.notificationType();
        if (candidate == null) { feedback.accept("当前选项无法生成规则。"); return; }
        view.confirming = true;
        clearContent(view);
        view.page.body.addView(label("正在核对保存权限…", 14));
        view.page.action("取消", false, () -> cancelEdit(view, preview));
        worker.execute(() -> {
            try {
                requireSave(view, preview, ticket);
                IslandCaptureIpc.checkPreview(ticket);
                IslandRuleManagerUi.Listing listing = IslandRuleManagerUi.load(app, candidate.packageName, type);
                Confirmation confirmation = new Confirmation(ticket, candidate, createdAt, listing);
                main.post(() -> showConfirmation(view, preview, confirmation));
            } catch (IOException | RuntimeException denied) {
                main.post(() -> { if (view.preview == preview && view.confirming) denySave(view); });
            }
        });
    }

    private void showConfirmation(Viewer view, Preview preview, Confirmation confirmation) {
        if (current != view || view.preview != preview || !view.confirming || view.saving) return;
        if (!view.live() || !preview.lifetime.live(now())) { revoke(view); return; }
        view.confirmation = confirmation;
        clearContent(view);
        view.page.title("确认保存规则");
        IslandCaptureEditor.Candidate candidate = confirmation.candidate;
        IslandCaptureEditor editor = preview.lifetime.editor;
        LinearLayout details = view.page.scrolling();
        details.addView(ui.heading(viewName(candidate.packageName), 17), ui.spaced(4));
        details.addView(label(IslandRuleEditorUi.typeLabel(editor.notificationType()), 13), ui.spaced(8));
        for (IslandRulePaths.Entry path : candidate.paths) {
            details.addView(ui.fieldLabel(example(editor, path), path.path), ui.spaced(6));
        }
        view.page.action("返回编辑", false, () -> {
            if (current == view && view.confirmation == confirmation) renderEditor(view, preview);
        });
        details.addView(label("如果已有规则先命中，新增规则可能不会显示。可在已保存规则中管理先命中的规则。", 13),
                ui.spaced(8));
        if (confirmation.targets.isEmpty()) {
            view.page.action("新增规则", true, () -> save(view, preview, confirmation, null));
            return;
        }
        details.addView(ui.heading("保存方式", 15), ui.spaced(4));
        details.addView(label("更新会保留原规则的顺序和启停状态。", 13), ui.spaced(8));
        RadioGroup options = new RadioGroup(activity);
        Map<Integer, String> targets = new LinkedHashMap<>();
        details.addView(options, ui.spaced(8));
        for (IslandRuleManagerUi.Row target : confirmation.targets) {
            boolean narrow = target.notificationType == null && editor.notificationType() != null;
            String title = narrow ? "更新应用级规则，收窄到当前通知类型 · " : "更新原规则 · ";
            RadioButton option = ui.radio(title + target.summary, String.join(" → ", target.orderedPaths));
            targets.put(option.getId(), target.entry.id);
            options.addView(option, ui.spaced(6));
        }
        RadioButton create = ui.radio("新增规则", null);
        options.addView(create, ui.spaced(6));
        Button save = view.page.action("保存规则", true, () -> {
            int selected = options.getCheckedRadioButtonId();
            if (selected == create.getId() || targets.containsKey(selected)) {
                save(view, preview, confirmation, targets.get(selected));
            }
        });
        IslandRuleEditorUi.setActionEnabled(save, false);
        options.setOnCheckedChangeListener((group, selected) -> {
            save.setText(selected == create.getId() ? "新增规则" : "更新规则");
            IslandRuleEditorUi.setActionEnabled(save, selected == create.getId() || targets.containsKey(selected));
        });
    }

    private void requireSave(Viewer view, Preview preview, IslandCaptureIpc.PreviewTicket ticket) throws IOException {
        if (view.preview != preview || !view.live() || !preview.lifetime.live(now()) || ticket == null
                || preview.ticket != ticket || !ticket.matches(view.registration, view.generation, preview.record.handle)
                || !(usesLocalApproval(view) ? IslandCaptureProvider.trustedLocalApprovalSource(app, view.registration)
                        : IslandCaptureProvider.trustedSaveSource(app, view.registration))) {
            throw new IOException("CAPTURE_SAVE_REVOKED");
        }
    }

    private void save(Viewer view, Preview preview, Confirmation confirmation, String replaceId) {
        if (current != view || view.preview != preview || view.saving || view.confirmation != confirmation) return;
        boolean localRoute = usesLocalApproval(view);
        String mode = localRoute ? "app-local" : "strict-systemui";
        long baseline = confirmation.baseline;
        if (!view.live() || !preview.lifetime.live(now())) {
            saveDiagnostic("rejected", mode, baseline, -1, "preview-not-live");
            revoke(view);
            return;
        }
        final IslandRuleSample sample;
        try {
            sample = IslandRuleSample.capture(preview.lifetime.editor.fields());
        } catch (IOException | RuntimeException invalid) {
            saveDiagnostic("rejected", mode, baseline, -1, "sample-invalid");
            revoke(view);
            feedback.accept("无法保存此通知的示例，请重新捕获后再试。");
            return;
        }
        view.confirmation = null;
        view.saving = true;
        IslandCaptureIpc.PreviewTicket ticket = confirmation.ticket;
        IslandCaptureEditor.Candidate candidate = confirmation.candidate;
        IslandCaptureEditor.LocalApproval localApproval = localRoute
                ? new IslandCaptureEditor.LocalApproval(ticket, baseline, candidate) : null;
        saveDiagnostic("attempt", mode, baseline, -1, "none");
        clearContent(view);
        view.page.body.addView(label("正在保存…", 14));
        worker.execute(() -> {
            String message;
            long committedRevision = 0;
            boolean changed = false;
            try {
                requireSave(view, preview, ticket);
                IslandRuleRepository repository = IslandRuleImportUi.repository(app);
                IslandRuleImportValidator.Environment environment = IslandRuleImportUi.environment(app);
                try (IslandRuleRepository.Pending pending = replaceId == null
                        ? repository.prepare(new ByteArrayInputStream(candidate.bytes()), null, true, 0, environment, sample)
                        : repository.prepareUpdate(baseline, replaceId, candidate.bytes(), environment, sample)) {
                    if (pending.baselineRevision != baseline) throw new IOException("CAPTURE_SAVE_BASELINE_CHANGED");
                    if (!pending.canApprove(false) || pending.entry == null || !candidate.digest.equals(pending.entry.sha256)) {
                        throw new IOException("CAPTURE_SAVE_VALIDATION_FAILED");
                    }
                    requireSave(view, preview, ticket);
                    long token = localApproval == null
                            ? IslandCaptureIpc.issueSave(ticket, baseline, candidate, confirmation.createdAt) : 0;
                    long revision = repository.commitCapture(pending, IslandRuleImportUi.environment(app),
                            new IslandRuleRepository.CommitAuthorization() {
                                @Override public void consume() throws IOException {
                                    requireSave(view, preview, ticket);
                                    if (localApproval != null) {
                                        localApproval.consume(preview.ticket, pending, () -> IslandCaptureIpc.checkPreview(ticket));
                                        if (!view.live()) throw new IOException("CAPTURE_SAVE_REVOKED");
                                    } else {
                                        IslandCaptureIpc.consumeSave(ticket, token, baseline, candidate.digest);
                                        requireSave(view, preview, ticket);
                                    }
                                }

                                @Override public void publish(IslandRuleRepository.ManifestPublication publication)
                                        throws IOException {
                                    // 只把原子替换与本地撤销、注册换代排序，预览 IPC 和目录同步留在锁外。
                                    preview.lifetime.publish(IslandCaptureEditorUi::now, () ->
                                            IslandCaptureProvider.publishForRegistration(view.registration, () -> {
                                                if (view.closed || now() >= view.lease || view.preview != preview
                                                        || preview.ticket != ticket) {
                                                    throw new IOException("CAPTURE_SAVE_REVOKED");
                                                }
                                                publication.publish();
                                            }));
                                }
                            });
                    changed = true;
                    committedRevision = revision;
                    saveDiagnostic("committed", mode, baseline, revision, "none");
                    message = "App 已保存规则，可在已保存规则中管理。";
                }
            } catch (IslandRuleRepository.Failure failure) {
                changed = failure.code == IslandRuleRepository.Code.COMMITTED_CLEANUP_FAILED
                        || failure.code == IslandRuleRepository.Code.DURABILITY_UNCERTAIN;
                saveDiagnostic(changed ? "commit-uncertain" : "rejected", mode, baseline, -1,
                        failure.code.name().toLowerCase(java.util.Locale.ROOT));
                message = changed ? "规则提交后需要重新核对 App 仓库状态。" : "规则未保存。请重新检查规则仓库和批准范围。";
            } catch (IOException | RuntimeException denied) {
                saveDiagnostic("rejected", mode, baseline, -1, "authorization-validation-or-io");
                message = "规则未保存。授权、已确认修订或规则校验已失效。";
            }
            if (changed) {
                try { app.getContentResolver().notifyChange(IslandRuleProvider.HEAD, null, 0); }
                catch (RuntimeException unavailable) { /* 规则快照发布者会周期核对修订。 */ }
                IslandRuleProjectionProvider.notifyChanged(app.getContentResolver());
            }
            String result = message;
            boolean manifestChanged = changed;
            long saved = committedRevision;
            main.post(() -> {
                boolean currentViewer = current == view && view.live();
                if (current == view) {
                    revoke(view);
                    feedback.accept(result);
                }
                if (manifestChanged) ruleUi.afterCommit(currentViewer ? saved : 0, false);
            });
        });
    }

    private static void saveDiagnostic(String outcome, String mode, long baseline, long revision, String reason) {
        if (!BuildConfig.DEBUG) return;
        Log.i(TAG, "DIAG rule save outcome=" + outcome + " mode=" + mode
                + " baseline=" + baseline + " revision=" + revision + " reason=" + reason);
    }

    private void denySave(Viewer view) {
        if (current != view) return;
        saveDiagnostic("rejected", "unavailable", -1, -1, "save-unavailable");
        revoke(view);
        feedback.accept("规则保存不可用。请核对当前会话和 SystemUI 授权。");
    }

    private void clearContent(Viewer view) {
        view.page.clear();
        clearEditorViews(view);
    }

    private static void clearEditorViews(Viewer view) {
        view.textPreview = null;
        view.selected = null;
        view.save = null;
    }

    private String identity(IslandCapturePreview.Summary record) {
        return viewName(record.packageName) + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(new Date(record.capturedAt)) + "\n" + (record.fieldCount > 0 ? record.fieldCount + " 项" : record.reason);
    }

    private String viewName(String name) {
        Viewer view = current;
        return view == null ? "" : IslandRuleManagerUi.listName(app, view.names, name);
    }

    private TextView label(String text, int size) { return ui.secondary(text, size); }

    private int dp(int value) { return activity.dp(value); }
}
