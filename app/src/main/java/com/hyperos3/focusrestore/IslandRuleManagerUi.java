/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/** App 内单条操作；打开列表时的修订贯穿确认和原子写入。 */
final class IslandRuleManagerUi {
    static final class Row {
        final IslandRuleRepository.Entry entry;
        final String appName;
        final String summary;
        final IslandRuleProjection.Row capture;
        Row(IslandRuleRepository.Entry entry, String appName, IslandRule rule, IslandRuleProjection.Row capture) {
            this.entry = entry;
            this.appName = appName;
            this.capture = capture;
            Map<String, String> paths = new HashMap<>();
            for (ExtractSpec extract : rule.extract) paths.put(extract.id, extract.path);
            List<String> ordered = new ArrayList<>();
            for (DisplaySpec.Projection projection : rule.display.order) ordered.add(paths.get(projection.ref));
            summary = "第 " + (entry.order + 1) + " 条 · " + (entry.enabled ? "已启用" : "已停用") + "\n"
                    + String.join(" → ", ordered);
        }
    }

    static final class Listing {
        final long baseline;
        final List<Row> rows;
        Listing(long baseline, List<Row> rows) { this.baseline = baseline; this.rows = rows; }
    }

    static Listing load(Context app, String capturePackage) throws IOException {
        List<Row> rows = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        try (IslandRuleRepository.ReadView view = IslandRuleImportUi.repository(app).openRead()) {
            for (IslandRuleRepository.Entry entry : view.entries) {
                byte[] bytes = view.read(entry);
                IslandRule rule = new IslandRuleCodec().decode(bytes).rule;
                if (rule == null) throw new IOException("RULE_LIST_FORMAT");
                if (capturePackage != null && !capturePackage.equals(rule.meta.packageName)) continue;
                IslandRuleProjection.Row capture = IslandRuleProjection.captureRow(bytes, rule);
                // 从捕获记录更新时只列同应用、可完整重建的规则，不能覆盖复杂匹配条件。
                if (capturePackage != null && capture == null) continue;
                String label = listName(app, names, rule.meta.packageName);
                rows.add(new Row(entry, label, rule, capture));
            }
            return new Listing(view.revision, rows);
        }
    }

    // 名称只在当前列表有效，让同名或已卸载应用可区分，并避免在授权清单暴露包名。
    static String listName(Context context, Map<String, String> names, String pkg) {
        String known = names.get(pkg);
        if (known != null) return known;
        String base = appName(context, pkg);
        String label = base;
        if ("未识别应用".equals(base) || names.containsValue(base)) {
            int index = names.size() + 1;
            do { label = base + " " + index++; } while (names.containsValue(label));
        }
        names.put(pkg, label);
        return label;
    }

    static String appName(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (label != null && label.length() > 0 && !pkg.contentEquals(label)) return label.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            // 应用已卸载或 ROM 限制包可见性，规则仍可按列表位置管理。
        }
        return "未识别应用";
    }

    private final Activity activity;
    private final Context app;
    private final ExecutorService worker;
    private final IslandRuleImportUi ruleUi;
    private final java.util.function.Consumer<String> status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long page;
    private AlertDialog dialog;
    private boolean active;

    IslandRuleManagerUi(Activity activity, ExecutorService worker, IslandRuleImportUi ruleUi, java.util.function.Consumer<String> status) {
        this.activity = activity;
        this.app = activity.getApplicationContext();
        this.worker = worker;
        this.ruleUi = ruleUi;
        this.status = status;
    }

    void setActive(boolean active) {
        this.active = active;
        if (!active) dismissPage();
    }

    void dismissPage() {
        page++;
        if (dialog != null) dialog.dismiss();
    }

    private boolean current(long generation) {
        return active && generation == page && !activity.isFinishing() && !activity.isDestroyed();
    }

    private Runnable reviewGrant;

    void open(Runnable reviewGrant) {
        this.reviewGrant = reviewGrant;
        open();
    }

    private void open() {
        if (!active || worker.isShutdown()) return;
        long generation = ++page;
        worker.execute(() -> {
            try {
                Listing listing = load(app, null);
                main.post(() -> { if (current(generation)) list(listing); });
            } catch (IOException | RuntimeException failure) { report(generation, failure); }
        });
    }

    private void list(Listing listing) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("已保存规则").setNegativeButton("关闭", null);
        if (listing.rows.isEmpty()) builder.setMessage("还没有规则。可从最近记录选择字段并新增规则。");
        else {
            String[] items = new String[listing.rows.size()];
            for (int i = 0; i < items.length; i++) {
                Row row = listing.rows.get(i);
                items[i] = row.appName + " · " + row.summary;
            }
            builder.setItems(items, (d, pos) -> actions(listing.baseline, listing.rows.get(pos)));
        }
        if (reviewGrant != null) builder.setNeutralButton("查看或撤销读取授权", (d, pos) -> reviewGrant.run());
        show(builder);
    }

    private void actions(long baseline, Row row) {
        String[] options = {"编辑规则", row.entry.enabled ? "停用规则" : "启用规则", "删除规则…"};
        show(new AlertDialog.Builder(activity).setTitle(row.appName + " · 第 " + (row.entry.order + 1) + " 条")
                .setItems(options, (d, which) -> {
                    if (which == 0) edit(baseline, row);
                    else if (which == 1) change(baseline, row, false);
                    else show(new AlertDialog.Builder(activity).setTitle("删除这条规则？")
                            .setMessage(row.appName + " · " + row.summary + "\n其他规则将保留。")
                            .setNegativeButton("取消", null).setPositiveButton("删除", (confirm, ignored) -> change(baseline, row, true)));
                }).setNegativeButton("返回列表", (d, which) -> open()));
    }

    private void edit(long baseline, Row row) {
        long generation = page;
        worker.execute(() -> {
            try (IslandRuleRepository.ReadView view = IslandRuleImportUi.repository(app).openRead()) {
                if (view.revision != baseline) throw new IslandRuleRepository.Failure(IslandRuleRepository.Code.BASELINE_CHANGED);
                byte[] original = view.readById(row.entry.id);
                main.post(() -> {
                    if (!current(generation)) return;
                    if (row.capture == null) editJson(baseline, row, original);
                    else editPaths(baseline, row, new IslandRuleCodec().decode(original).rule.meta.createdAt);
                });
            } catch (IOException | RuntimeException failure) { report(generation, failure); }
        });
    }

    private void editJson(long baseline, Row row, byte[] original) {
        EditText input = new EditText(activity);
        input.setSaveEnabled(false);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(IslandRuleRepository.MAX_RULE_BYTES)});
        input.setText(new String(original, StandardCharsets.UTF_8));
        show(new AlertDialog.Builder(activity).setTitle("编辑完整规则 · " + row.appName)
                .setMessage("此规则含自定义条件。保留需要的条件和字段后保存。")
                .setView(input).setNegativeButton("取消", null).setPositiveButton("保存更改", (d, w) ->
                        update(baseline, row, input.getText().toString().getBytes(StandardCharsets.UTF_8), false)));
    }

    private void editPaths(long baseline, Row row, String createdAt) {
        List<IslandRulePaths.Entry> order = new ArrayList<>();
        for (String path : row.capture.paths) order.add(IslandRulePaths.find(path));
        ScrollView scroll = new ScrollView(activity);
        LinearLayout content = column();
        scroll.addView(content);
        LinearLayout selected = column();
        content.addView(selected);
        Map<IslandRulePaths.Entry, CheckBox> choices = new LinkedHashMap<>();
        Runnable[] redraw = new Runnable[1];
        redraw[0] = () -> {
            IslandRuleOrderUi.render(selected, order, (path, index) -> {
                if (!order.contains(path) || index < 0 || index >= order.size()) return;
                order.remove(path);
                order.add(index, path);
                redraw[0].run();
            });
            for (Map.Entry<IslandRulePaths.Entry, CheckBox> item : choices.entrySet()) {
                boolean checked = order.contains(item.getKey());
                item.getValue().setChecked(checked);
                item.getValue().setEnabled(checked || order.size() < IslandCaptureEditor.MAX_SELECTION);
            }
            if (dialog != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!order.isEmpty());
        };
        for (IslandRulePaths.Entry path : IslandRulePaths.all()) {
            if (!IslandRulePaths.isRuntimeAllowed(path)) continue;
            CheckBox choice = new CheckBox(activity);
            choice.setText(path.path);
            choices.put(path, choice);
            content.addView(choice);
            choice.setOnClickListener(v -> {
                if (!order.remove(path) && order.size() < IslandCaptureEditor.MAX_SELECTION) order.add(path);
                redraw[0].run();
            });
        }
        show(new AlertDialog.Builder(activity).setTitle("编辑字段 · " + row.appName).setView(scroll)
                .setMessage("长按字段名可调整显示顺序。当前没有通知样本。")
                .setNegativeButton("取消", null).setPositiveButton("保存更改", (d, w) -> {
                    IslandCaptureEditor.Candidate candidate = IslandCaptureEditor.candidate(row.capture.packageName,
                            order, Instant.parse(createdAt).toEpochMilli());
                    if (candidate != null) update(baseline, row, candidate.bytes(), false);
                }));
        redraw[0].run();
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(activity);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private void update(long baseline, Row row, byte[] bytes, boolean force) {
        long generation = page;
        worker.execute(() -> {
            try {
                IslandRuleRepository store = IslandRuleImportUi.repository(app);
                try (IslandRuleRepository.Pending candidate = store.prepareUpdate(baseline, row.entry.id,
                        bytes, IslandRuleImportUi.environment(app))) {
                    if (candidate.canApprove(true) && !candidate.canApprove(false) && !force) {
                        main.post(() -> {
                            if (current(generation)) show(new AlertDialog.Builder(activity).setTitle("系统界面版本不同")
                                    .setMessage("这条规则声明的系统界面版本与当前设备不一致，仍然保存？")
                                    .setNegativeButton("取消", null).setPositiveButton("仍然保存", (d, w) -> update(baseline, row, bytes, true)));
                        });
                        return;
                    }
                    if (!candidate.canApprove(force)) throw new IslandRuleRepository.Failure(IslandRuleRepository.Code.VALIDATION);
                    long saved = store.commit(candidate, force, IslandRuleImportUi.environment(app));
                    committed(generation, saved, false);
                }
            } catch (IOException | RuntimeException failure) { report(generation, failure); }
        });
    }

    private void change(long baseline, Row row, boolean delete) {
        long generation = page;
        worker.execute(() -> {
            try {
                IslandRuleRepository store = IslandRuleImportUi.repository(app);
                boolean review = false;
                if (!delete && !row.entry.enabled) {
                    store.setEnabled(baseline, row.entry.id, true, IslandRuleImportUi.environment(app));
                } else {
                    IslandRuleProjectionConsent consent = null;
                    if (IslandRuleProjectionProvider.available(app)) {
                        try { consent = IslandRuleProjectionProvider.consent(app); }
                        catch (IOException | RuntimeException unavailable) {
                            Log.w("FocusRestoreRules", "rule reduction: consent storage unavailable", unavailable);
                            review = true;
                        }
                    }
                    // 授权记录不可用仍允许减少规则。仓库一旦提交，不能因授权落盘失败重做删除。
                    if (consent != null) {
                        review = consent.reduce(store, baseline, row.entry.id, delete)
                                == IslandRuleProjectionConsent.ReductionResult.SAVED_REAUTHORIZE;
                    } else if (delete) {
                        store.delete(baseline, row.entry.id);
                    } else {
                        store.setEnabled(baseline, row.entry.id, false, null);
                    }
                }
                committed(generation, baseline + 1L, review);
            } catch (IOException | RuntimeException failure) { report(generation, failure); }
        });
    }

    private void notifyReaders() {
        try { app.getContentResolver().notifyChange(IslandRuleProvider.HEAD, null, 0); }
        catch (RuntimeException failed) { Log.w("FocusRestoreRules", "rule update notification failed"); }
        IslandRuleProjectionProvider.notifyChanged(app.getContentResolver());
    }

    private void committed(long generation, long saved, boolean review) {
        notifyReaders();
        main.post(() -> {
            if (!current(generation)) { ruleUi.refreshSavedRevision(); return; }
            status.accept(review ? "App 已保存更改，请重新核对剩余规则的读取授权。" : "App 已保存更改。");
            ruleUi.afterCommit(saved, true);
        });
    }

    private void report(long generation, Throwable failure) {
        if (failure instanceof IslandRuleRepository.Failure) {
            IslandRuleRepository.Code code = ((IslandRuleRepository.Failure) failure).code;
            if (code == IslandRuleRepository.Code.COMMITTED_CLEANUP_FAILED || code == IslandRuleRepository.Code.DURABILITY_UNCERTAIN) {
                notifyReaders();
                main.post(ruleUi::refreshSavedRevision);
            }
        }
        String message = IslandRuleImportUi.failureText(failure);
        main.post(() -> { if (current(generation)) status.accept(message); });
    }

    private void show(AlertDialog.Builder builder) {
        AlertDialog next = builder.create();
        dialog = next;
        next.setOnDismissListener(d -> { if (dialog == next) dialog = null; });
        next.show();
    }
}
