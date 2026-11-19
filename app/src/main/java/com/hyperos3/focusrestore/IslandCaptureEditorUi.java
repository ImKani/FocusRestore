/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.app.AlertDialog;
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
import android.view.Window;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
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
        AlertDialog dialog;
        LinearLayout root;
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
            view.root = column();
            view.root.setPadding(dp(20), dp(8), dp(20), dp(8));
            view.dialog = new AlertDialog.Builder(activity).setTitle("最近捕获记录")
                    .setView(view.root).setNegativeButton("关闭", null).create();
            Window window = view.dialog.getWindow();
            if (window == null) throw new IllegalStateException("CAPTURE_EDITOR_WINDOW_MISSING");
            secure(window);
            view.dialog.setOnDismissListener(d -> revoke(view));
            view.dialog.show();
            window.getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(focused -> {
                if (!focused) revoke(view);
            });
            showList(view);
            checkLive();
        } catch (android.os.RemoteException | RuntimeException failure) {
            revoke(view);
            feedback.accept("最近记录暂不可用，请重新进入捕获会话。");
        }
    }

    private static void secure(Window window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
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
        clearRows(view);
        view.metadata = Collections.emptyList();
        if (view.root != null) {
            scrub(view.root);
            view.root.removeAllViews();
        }
        if (view.dialog != null) view.dialog.dismiss();
        if (view.registered) app.unregisterReceiver(view.screen);
        if (view.linked) view.registration.bridge.unlinkToDeath(view.death, 0);
    }

    private void releasePreview(Viewer view) {
        Preview preview = view.preview;
        view.preview = null;
        view.confirmation = null;
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
                main.post(() -> revoke(view));
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
        view.dialog.setTitle("最近捕获记录");
        view.list = new ListView(activity);
        view.list.setSaveEnabled(false);
        view.rows = new Rows(view);
        view.list.setRecyclerListener(IslandCaptureEditorUi::scrub);
        view.list.setAdapter(view.rows);
        view.root.addView(view.list, new LinearLayout.LayoutParams(-1, dp(342)));
        TextView empty = label("等待新的超级岛通知…", 14);
        view.root.addView(empty);
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
            LinearLayout row = recycled instanceof LinearLayout ? (LinearLayout) recycled : column();
            if (row.getChildCount() == 0) {
                row.setLayoutParams(new AbsListView.LayoutParams(-1, dp(72)));
                row.addView(label("", 13));
                TextView fragment = label("", 15);
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
        Set<Long> requestedViewport = view.viewport;
        worker.execute(() -> {
            try {
                if (view.closed) return;
                if (view.viewport != requestedViewport) {
                    main.post(() -> {
                        view.snippetPending = false;
                        refreshRows(view);
                    });
                    return;
                }
                List<String> snippets = IslandCaptureIpc.visibleSnippets(view.registration, view.generation, handles);
                main.post(() -> {
                    if (current != view) return;
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
            } catch (IOException | RuntimeException denied) { main.post(() -> revoke(view)); }
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

    private void openRecord(Viewer view, IslandCapturePreview.Summary record) {
        if (view.preview != null || !view.live()) return;
        clearRows(view);
        view.list.setAdapter(null);
        clearContent(view);
        view.list = null;
        view.rows = null;
        view.metadata = Collections.emptyList();
        view.dialog.setTitle("编辑显示规则");
        view.root.addView(label("正在读取登记字段…", 14));
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
        IslandCaptureEditor editor = preview.lifetime.editor;
        ScrollView scroll = new ScrollView(activity);
        scroll.setSaveEnabled(false);
        LinearLayout fields = column();
        scroll.addView(fields);
        view.root.addView(label(identity(preview.record), 13));
        view.root.addView(label("各组共选最多 16 项。长按字段名拖动，或用上下按钮排序。", 13));
        view.textPreview = label("", 17);
        view.textPreview.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        view.root.addView(view.textPreview);
        view.root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(320)));
        view.selected = column();
        fields.addView(view.selected);
        Map<String, List<IslandCapturePreview.Field>> groups = new LinkedHashMap<>();
        for (IslandCapturePreview.Field field : editor.fields()) {
            groups.computeIfAbsent(field.path.templateGroup, key -> new ArrayList<>()).add(field);
        }
        List<CheckBox> choices = new ArrayList<>();
        List<IslandRulePaths.Entry> paths = new ArrayList<>();
        for (Map.Entry<String, List<IslandCapturePreview.Field>> group : groups.entrySet()) {
            fields.addView(label(group.getKey(), 16));
            for (IslandCapturePreview.Field field : group.getValue()) {
                IslandRulePaths.Entry path = field.path;
                CheckBox choice = new CheckBox(activity);
                choice.setSaveEnabled(false);
                choice.setText(path.path + "\n" + IslandCaptureEditor.displayValue(field));
                choice.setChecked(editor.order().contains(path));
                fields.addView(choice);
                choices.add(choice);
                paths.add(path);
                choice.setOnClickListener(v -> {
                    if (current != view || view.preview != preview || view.confirming) return;
                    if (!view.live() || !preview.lifetime.live(now())) { revoke(view); return; }
                    editor.toggle(path);
                    renderOrder(view, preview);
                    updateChoices(editor, choices, paths);
                });
            }
        }
        updateChoices(editor, choices, paths);
        LinearLayout actions = new LinearLayout(activity);
        view.root.addView(actions);
        actions.addView(button("返回列表", () -> {
            releasePreview(view);
            clearContent(view);
            showList(view);
            checkLive();
        }));
        view.save = button("确认保存…", () -> prepareConfirmation(view, preview));
        actions.addView(view.save);
        if (!canConfirmSave(view)) {
            view.root.addView(label("当前会话仅支持查看和编辑，暂时无法保存规则。", 13));
        }
        renderOrder(view, preview);
    }

    private static void updateChoices(IslandCaptureEditor editor, List<CheckBox> choices, List<IslandRulePaths.Entry> paths) {
        Set<IslandRulePaths.Entry> order = new HashSet<>(editor.order());
        for (int i = 0; i < choices.size(); i++) {
            IslandRulePaths.Entry path = paths.get(i);
            boolean checked = order.contains(path);
            choices.get(i).setChecked(checked);
            choices.get(i).setEnabled(checked || order.size() < IslandCaptureEditor.MAX_SELECTION);
        }
    }

    private void renderOrder(Viewer view, Preview preview) {
        IslandCaptureEditor editor = preview.lifetime.editor;
        String text = editor.preview();
        view.textPreview.setText(text.isEmpty() ? "预览暂无正文" : text);
        view.save.setEnabled(view.loadedDetails && canConfirmSave(view) && !editor.order().isEmpty());
        IslandRuleOrderUi.render(view.selected, editor.order(), (path, index) -> move(view, preview, path, index));
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
        IslandCaptureEditor.Candidate candidate = preview.lifetime.editor.candidate(preview.record.packageName, createdAt);
        if (candidate == null) { feedback.accept("当前选项无法生成规则。"); return; }
        view.confirming = true;
        clearContent(view);
        view.root.addView(label("正在核对保存权限…", 14));
        worker.execute(() -> {
            try {
                requireSave(view, preview, ticket);
                IslandCaptureIpc.checkPreview(ticket);
                IslandRuleManagerUi.Listing listing = IslandRuleManagerUi.load(app, candidate.packageName);
                Confirmation confirmation = new Confirmation(ticket, candidate, createdAt, listing);
                main.post(() -> showConfirmation(view, preview, confirmation));
            } catch (IOException | RuntimeException denied) { main.post(() -> denySave(view)); }
        });
    }

    private void showConfirmation(Viewer view, Preview preview, Confirmation confirmation) {
        if (current != view || view.preview != preview || !view.confirming || view.saving) return;
        if (!view.live() || !preview.lifetime.live(now())) { revoke(view); return; }
        view.confirmation = confirmation;
        clearContent(view);
        view.dialog.setTitle("确认单条规则");
        IslandCaptureEditor.Candidate candidate = confirmation.candidate;
        view.root.addView(label("匹配范围为「" + viewName(candidate.packageName) + "」所有已支持的通知。缺少所选字段时可能没有匹配正文。", 14));
        ScrollView scroll = new ScrollView(activity);
        scroll.setSaveEnabled(false);
        LinearLayout details = column();
        scroll.addView(details);
        for (IslandRulePaths.Entry path : candidate.paths) details.addView(label(path.path, 13));
        if (!confirmation.targets.isEmpty()) {
            details.addView(label("更新会保留原规则的位置和启停状态。新增规则可能排在原规则之后。", 14));
        }
        for (IslandRuleManagerUi.Row target : confirmation.targets) {
            details.addView(button("更新原规则 · " + target.summary, () -> save(view, preview, confirmation, target.entry.id)));
        }
        view.root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(220)));
        view.root.addView(button("返回编辑", () -> {
            if (current != view || view.confirmation != confirmation) return;
            view.dialog.setTitle("编辑显示规则");
            renderEditor(view, preview);
        }));
        view.root.addView(button("新增规则", () -> save(view, preview, confirmation, null)));
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
        view.confirmation = null;
        view.saving = true;
        IslandCaptureIpc.PreviewTicket ticket = confirmation.ticket;
        IslandCaptureEditor.Candidate candidate = confirmation.candidate;
        IslandCaptureEditor.LocalApproval localApproval = localRoute
                ? new IslandCaptureEditor.LocalApproval(ticket, baseline, candidate) : null;
        saveDiagnostic("attempt", mode, baseline, -1, "none");
        clearContent(view);
        view.root.addView(label("正在保存…", 14));
        worker.execute(() -> {
            String message;
            long committedRevision = 0;
            boolean changed = false;
            try {
                requireSave(view, preview, ticket);
                IslandRuleRepository repository = IslandRuleImportUi.repository(app);
                IslandRuleImportValidator.Environment environment = IslandRuleImportUi.environment(app);
                try (IslandRuleRepository.Pending pending = replaceId == null
                        ? repository.prepare(new ByteArrayInputStream(candidate.bytes()), null, true, 0, environment)
                        : repository.prepareUpdate(baseline, replaceId, candidate.bytes(), environment)) {
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
        scrub(view.root);
        view.root.removeAllViews();
        view.textPreview = null;
        view.selected = null;
        view.save = null;
    }

    private static void scrub(View view) {
        view.setContentDescription(null);
        view.setStateDescription(null);
        view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        if (view instanceof TextView) ((TextView) view).setText("");
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scrub(group.getChildAt(i));
        }
    }

    private String identity(IslandCapturePreview.Summary record) {
        return viewName(record.packageName) + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(new Date(record.capturedAt)) + "\n" + (record.fieldCount > 0 ? record.fieldCount + " 项" : record.reason);
    }

    private String viewName(String name) {
        Viewer view = current;
        return view == null ? "" : IslandRuleManagerUi.listName(app, view.names, name);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setSaveFromParentEnabled(false);
        return layout;
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(activity);
        view.setSaveEnabled(false);
        view.setTextSize(size);
        view.setText(text);
        return view;
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(activity);
        button.setSaveEnabled(false);
        button.setText(text);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private int dp(int value) { return Math.round(activity.getResources().getDisplayMetrics().density * value); }
}
