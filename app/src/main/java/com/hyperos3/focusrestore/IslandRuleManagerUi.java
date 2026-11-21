/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListPopupWindow;
import android.widget.ListView;
import android.widget.TextView;
import java.text.Collator;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** App 内规则管理，打开列表时的修订贯穿确认和原子写入。 */
final class IslandRuleManagerUi {
    static final class Row {
        final IslandRuleRepository.Entry entry;
        final String appName;
        final String packageName;
        final long blobModifiedAt;
        final String summary;
        final IslandRuleProjection.Row capture;
        final IslandNotificationType notificationType;
        final List<String> orderedPaths;
        Row(IslandRuleRepository.Entry entry, String appName, IslandRule rule, IslandRuleProjection.Row capture,
                long blobModifiedAt) {
            this.entry = entry;
            this.appName = appName;
            packageName = rule.meta.packageName;
            this.blobModifiedAt = blobModifiedAt;
            this.capture = capture;
            notificationType = rule.notificationType;
            Map<String, String> paths = new HashMap<>();
            for (ExtractSpec extract : rule.extract) paths.put(extract.id, extract.path);
            List<String> ordered = new ArrayList<>();
            for (DisplaySpec.Projection projection : rule.display.order) ordered.add(paths.get(projection.ref));
            orderedPaths = Collections.unmodifiableList(ordered);
            summary = "第 " + (entry.order + 1) + " 条 · " + (entry.enabled ? "已启用" : "已停用");
        }
    }

    static final class Listing {
        final long baseline;
        final List<Row> rows;
        Listing(long baseline, List<Row> rows) { this.baseline = baseline; this.rows = rows; }
    }

    static Listing load(Context app, String capturePackage, IslandNotificationType type) throws IOException {
        List<Row> rows = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        try (IslandRuleRepository.ReadView view = IslandRuleImportUi.repository(app).openRead()) {
            for (IslandRuleRepository.Entry entry : view.entries) {
                byte[] bytes = view.read(entry);
                IslandRule rule = new IslandRuleCodec().decode(bytes).rule;
                if (rule == null) throw new IOException("RULE_LIST_FORMAT");
                if (capturePackage != null && !capturePackage.equals(rule.meta.packageName)) continue;
                IslandRuleProjection.Row capture = IslandRuleProjection.captureRow(bytes, rule);
                // 同类规则和应用级规则可明确更新，复杂条件从管理页编辑。
                if (capturePackage != null && (capture == null || (capture.notificationType != null
                        && !Objects.equals(type, capture.notificationType)))) continue;
                String label = listName(app, names, rule.meta.packageName);
                // 现有清单没有逐条时间戳。只按正文文件的写入时间排序，启停不会改写正文。
                long modifiedAt = capturePackage == null ? view.blobModifiedAt(entry) : 0;
                rows.add(new Row(entry, label, rule, capture, modifiedAt));
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

    private final SettingsActivity activity;
    private final IslandRuleEditorUi ui;
    private final Context app;
    private final ExecutorService worker;
    private final IslandRuleImportUi ruleUi;
    private final java.util.function.Consumer<String> status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long page;
    private IslandRuleEditorUi.Page window;
    private boolean active;

    IslandRuleManagerUi(SettingsActivity activity, ExecutorService worker, IslandRuleImportUi ruleUi,
            java.util.function.Consumer<String> status) {
        this.activity = activity;
        this.ui = new IslandRuleEditorUi(activity);
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
        closeWindow();
    }

    private boolean current(long generation) {
        return active && generation == page && !activity.isFinishing() && !activity.isDestroyed();
    }

    private Runnable reviewGrant;

    private Runnable createFromCapture;

    void open(Runnable reviewGrant, Runnable createFromCapture) {
        this.reviewGrant = reviewGrant;
        this.createFromCapture = createFromCapture;
        open();
    }

    private void open() {
        if (!active || worker.isShutdown()) return;
        long generation = ++page;
        closeWindow();
        worker.execute(() -> {
            try {
                Listing listing = load(app, null, null);
                main.post(() -> { if (current(generation)) list(listing); });
            } catch (IOException | RuntimeException failure) { report(generation, failure); }
        });
    }

    private void list(Listing listing) {
        IslandRuleEditorUi.Page next = ui.new Page("已保存规则");
        RuleList list = listing.rows.isEmpty() ? null : new RuleList(next, listing);
        if (list == null) {
            LinearLayout card = ui.column();
            card.setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(12));
            card.setBackground(activity.roundedBg(activity.COLOR_SURFACE_HIGH, 14));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                    activity.dp(28), activity.dp(28));
            iconParams.bottomMargin = activity.dp(8);
            card.addView(ui.glyph(R.drawable.ic_rule_add, 28, 28, activity.COLOR_PRIMARY), iconParams);
            card.addView(ui.heading("还没有规则", 15), ui.spaced(4));
            card.addView(ui.secondary("从最近捕获的通知选取正文字段。", 13), ui.spaced(10));
            Runnable create = createFromCapture;
            if (create != null) {
                card.addView(ui.button("从最近捕获创建", true, () -> {
                    if (window != next) return;
                    dismissPage();
                    create.run();
                }), ui.spaced(0));
            }
            next.scrolling().addView(card, ui.spaced(0));
        }
        if (reviewGrant != null) {
            next.action("读取授权", false, () -> { dismissPage(); reviewGrant.run(); });
        }
        next.action("关闭", true, this::dismissPage);
        if (list != null) next.onRelease(list::release);
        show(next, false);
    }

    enum StatusFilter {
        ALL("全部"), ENABLED("启用"), DISABLED("停用");
        final String label;
        StatusFilter(String label) { this.label = label; }
    }

    enum DisplayOrder {
        SAVED("正文最近保存"), APP("应用名"), STATE("启停状态");
        final String label;
        DisplayOrder(String label) { this.label = label; }
    }

    enum BatchAction { ENABLE, DISABLE, DELETE }

    static List<Row> visibleRows(List<Row> source, String query, StatusFilter filter, DisplayOrder sort) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Row> rows = new ArrayList<>();
        for (Row row : source) {
            if ((filter == StatusFilter.ENABLED && !row.entry.enabled)
                    || (filter == StatusFilter.DISABLED && row.entry.enabled)) continue;
            if (needle.isEmpty() || row.appName.toLowerCase(Locale.ROOT).contains(needle)
                    || row.packageName.toLowerCase(Locale.ROOT).contains(needle)) rows.add(row);
        }
        Comparator<Row> compare;
        if (sort == DisplayOrder.SAVED) {
            compare = Comparator.comparingLong((Row row) -> row.blobModifiedAt).reversed();
        } else if (sort == DisplayOrder.STATE) {
            compare = Comparator.comparing((Row row) -> row.entry.enabled).reversed();
        } else {
            Collator collator = Collator.getInstance();
            compare = (left, right) -> collator.compare(left.appName, right.appName);
            compare = compare.thenComparing(row -> row.packageName);
        }
        rows.sort(compare.thenComparingLong(row -> row.entry.order));
        return rows;
    }



    private final class RuleList {
        final IslandRuleEditorUi.Page page;
        final Listing listing;
        final EditText search = new EditText(activity);
        final ListView items = new ListView(activity);
        final LinearLayout batchBar = ui.column();
        final TextView count = ui.primary("", 14);
        final TextView empty = ui.secondary("没有符合条件的规则。", 14);
        final Set<String> selected = new HashSet<>();
        List<Row> visible = Collections.emptyList();
        StatusFilter filter = StatusFilter.ALL;
        DisplayOrder sort = DisplayOrder.SAVED;
        ListPopupWindow popup;
        final Button filterButton = ui.tonal("", this::filterMenu);
        final Button sortButton = ui.tonal("", this::sortMenu);
        final Button selectAll = ui.tonal(R.drawable.ic_rule_select_all, "全选结果", this::selectAll);
        final Button enable = ui.tonal("启用", () -> apply(BatchAction.ENABLE));
        final Button disable = ui.tonal("停用", () -> apply(BatchAction.DISABLE));
        final Button delete = ui.danger(R.drawable.ic_rule_delete, "删除", () -> apply(BatchAction.DELETE));
        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return visible.size(); }
            @Override public Row getItem(int position) { return visible.get(position); }
            @Override public long getItemId(int position) { return getItem(position).entry.order; }
            @Override public boolean hasStableIds() { return true; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                RuleItem item = recycled instanceof RuleItem ? (RuleItem) recycled : new RuleItem();
                item.bind(RuleList.this, getItem(position));
                return item;
            }
        };
        final TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(false); }
            @Override public void afterTextChanged(Editable s) { }
        };

        RuleList(IslandRuleEditorUi.Page page, Listing listing) {
            this.page = page;
            this.listing = listing;
            search.setSaveEnabled(false);
            search.setSingleLine(true);
            search.setTextColor(activity.COLOR_TEXT_PRIMARY);
            search.setHintTextColor(activity.COLOR_TEXT_SECONDARY);
            search.setHint("搜索应用名称或包名");
            search.setTextSize(14);
            search.setPaddingRelative(activity.dp(14), 0, activity.dp(14), 0);
            search.setMinHeight(activity.dp(50));
            search.setBackground(activity.roundedBg(activity.COLOR_SURFACE, 12));
            search.setSelectAllOnFocus(false);
            search.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            search.addTextChangedListener(watcher);
            page.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            page.body.addView(search, ui.spaced(8));

            SettingsFlowLayout tools = new SettingsFlowLayout(activity, activity.dp(8), activity.dp(6));
            tools.addView(filterButton);
            tools.addView(sortButton);
            tools.addView(selectAll);
            page.body.addView(tools, ui.spaced(8));

            items.setSaveEnabled(false);
            items.setItemsCanFocus(true);
            items.setCacheColorHint(Color.TRANSPARENT);
            items.setClipToPadding(false);
            items.setPadding(0, activity.dp(3), 0, activity.dp(8));
            items.setDivider(new ColorDrawable(Color.TRANSPARENT));
            items.setDividerHeight(activity.dp(9));
            items.setSelector(new ColorDrawable(Color.TRANSPARENT));
            items.setAdapter(adapter);
            page.body.addView(items, new LinearLayout.LayoutParams(-1, 0, 1f));
            page.body.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1f));
            items.setEmptyView(empty);

            batchBar.setPadding(activity.dp(12), activity.dp(9), activity.dp(12), activity.dp(9));
            batchBar.setBackground(activity.roundedBg(activity.COLOR_SURFACE_HIGH, 14));
            LinearLayout selection = new LinearLayout(activity);
            selection.setGravity(Gravity.CENTER_VERTICAL);
            count.setTextSize(13);
            count.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            selection.addView(count, new LinearLayout.LayoutParams(0, -2, 1f));
            selection.addView(ui.glyphButton(R.drawable.ic_rule_clear, "清除选择", this::clearSelection));
            batchBar.addView(selection, ui.spaced(8));
            SettingsFlowLayout actions = new SettingsFlowLayout(activity, activity.dp(8), activity.dp(6));
            actions.addView(enable);
            actions.addView(disable);
            actions.addView(delete);
            batchBar.addView(actions, ui.spaced(0));
            page.body.addView(batchBar, ui.spaced(8));
            refresh(false);
        }

        void refresh(boolean resetScroll) {
            int firstVisible = items.getFirstVisiblePosition();
            View firstChild = items.getChildAt(0);
            int firstTop = firstChild == null ? 0 : firstChild.getTop();
            visible = visibleRows(listing.rows, search.getText().toString(), filter, sort);
            // 搜索和筛选不清空隐藏行的选择；批量操作会明确显示隐藏选择数量。
            adapter.notifyDataSetChanged();
            if (resetScroll) {
                items.setSelection(0);
            } else if (firstVisible > 0 && !visible.isEmpty()) {
                items.setSelectionFromTop(Math.min(firstVisible, visible.size() - 1), firstTop);
            }
            filterButton.setText("筛选 · " + filter.label);
            filterButton.setContentDescription("状态筛选 · " + filter.label);
            sortButton.setText("排序 · " + sort.label);
            sortButton.setContentDescription("列表排序 · " + sort.label + "。规则优先级保持原样。");
            refreshSelection();
        }

        void refreshSelection() {
            batchBar.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
            int visibleSelected = 0;
            for (Row row : visible) if (selected.contains(row.entry.id)) visibleSelected++;
            int hiddenSelected = Math.max(0, selected.size() - visibleSelected);
            count.setText(hiddenSelected > 0
                    ? "已选 " + selected.size() + " 条 · 当前结果外 " + hiddenSelected + " 条"
                    : "已选 " + selected.size() + " 条");

            boolean canEnable = false;
            boolean canDisable = false;
            for (Row row : listing.rows) {
                if (!selected.contains(row.entry.id)) continue;
                if (row.entry.enabled) canDisable = true;
                else canEnable = true;
            }
            boolean allVisibleSelected = !visible.isEmpty() && visibleSelected == visible.size();
            selectAll.setText(allVisibleSelected ? "取消全选" : "全选当前结果");
            IslandRuleEditorUi.setActionEnabled(selectAll, !visible.isEmpty());
            IslandRuleEditorUi.setActionEnabled(enable, canEnable);
            IslandRuleEditorUi.setActionEnabled(disable, canDisable);
        }

        void clearSelection() {
            selected.clear();
            adapter.notifyDataSetChanged();
            refreshSelection();
        }

        void selectAll() {
            if (visible.isEmpty()) return;
            boolean allSelected = true;
            for (Row row : visible) {
                if (!selected.contains(row.entry.id)) { allSelected = false; break; }
            }
            for (Row row : visible) {
                if (allSelected) selected.remove(row.entry.id);
                else selected.add(row.entry.id);
            }
            adapter.notifyDataSetChanged();
            refreshSelection();
        }

        void filterMenu() {
            showMenu(filterButton, StatusFilter.values(), item -> item.label, filter.ordinal(), item -> filter = item);
        }

        void sortMenu() {
            showMenu(sortButton, DisplayOrder.values(), item -> item.label, sort.ordinal(), item -> sort = item);
        }

        <T> void showMenu(Button anchor, T[] values, java.util.function.Function<T, String> label,
                int selected, java.util.function.Consumer<T> apply) {
            if (window != page || !anchor.isAttachedToWindow() || !anchor.isEnabled()) return;
            if (popup != null) popup.dismiss();
            String[] labels = new String[values.length];
            for (int index = 0; index < values.length; index++) labels[index] = label.apply(values[index]);
            ListPopupWindow menu = activity.createChoicePopup(anchor, labels, selected, position -> {
                if (window != page || popup == null || !anchor.isEnabled()) return;
                apply.accept(values[position]);
                dismissPopup();
                refresh(true);
            });
            popup = menu;
            menu.setOnDismissListener(() -> { if (popup == menu) popup = null; });
            try {
                menu.show();
                activity.afterShowChoicePopup(menu);
            } catch (RuntimeException unavailable) {
                popup = null;
                menu.dismiss();
            }
        }

        private void dismissPopup() {
            ListPopupWindow menu = popup;
            popup = null;
            if (menu != null) menu.dismiss();
        }

        void apply(BatchAction action) {
            if (window != page || selected.isEmpty()) return;
            List<Row> targets = new ArrayList<>();
            for (Row row : listing.rows) if (selected.contains(row.entry.id)) targets.add(row);
            if (action == BatchAction.DELETE) {
                android.view.inputmethod.InputMethodManager keyboard =
                        activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
                int visibleSelected = 0;
                for (Row row : visible) if (selected.contains(row.entry.id)) visibleSelected++;
                int hiddenSelected = targets.size() - visibleSelected;
                String message = hiddenSelected > 0
                        ? "删除已选 " + targets.size() + " 条规则？其中 " + hiddenSelected + " 条当前不在筛选结果中。"
                        : "删除 " + targets.size() + " 条规则？删除后无法撤销。";
                page.ask(message, "取消", "删除", () -> {
                    if (window == page) change(listing.baseline, targets, action);
                });
            } else {
                change(listing.baseline, targets, action);
            }
        }

        void release() {
            search.removeTextChangedListener(watcher);
            if (popup != null) popup.dismiss();
            for (int i = 0; i < items.getChildCount(); i++) IslandRuleEditorUi.scrub(items.getChildAt(i));
            items.setEmptyView(null);
            items.setAdapter(null);
            visible = Collections.emptyList();
            selected.clear();
        }
    }

    private int mixColor(int base, int tint, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        return Color.rgb(
                Math.round(Color.red(base) * (1f - a) + Color.red(tint) * a),
                Math.round(Color.green(base) * (1f - a) + Color.green(tint) * a),
                Math.round(Color.blue(base) * (1f - a) + Color.blue(tint) * a));
    }

    private final class RuleItem extends LinearLayout {
        final CheckBox choice = new CheckBox(activity);
        final LinearLayout editArea = new LinearLayout(activity);
        final TextView title = ui.heading("", 16);
        final TextView pkg = ui.secondary("", 12);
        final TextView metadata = ui.secondary("", 12);
        final ImageView pencil = ui.glyph(R.drawable.ic_rule_edit, 40, 20, activity.COLOR_TEXT_SECONDARY);

        RuleItem() {
            super(activity);
            setGravity(Gravity.CENTER_VERTICAL);
            setPaddingRelative(activity.dp(5), activity.dp(5), activity.dp(7), activity.dp(5));
            setMinimumHeight(activity.dp(78));
            setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, -2));
            setClipToOutline(true);

            choice.setSaveEnabled(false);
            choice.setFilterTouchesWhenObscured(true);
            choice.setGravity(Gravity.CENTER);
            choice.setMinHeight(activity.dp(52));
            choice.setPadding(0, 0, 0, 0);
            choice.setBackgroundColor(Color.TRANSPARENT);
            choice.setButtonTintList(new ColorStateList(new int[][] {
                    new int[] {android.R.attr.state_checked}, new int[0]},
                    new int[] {activity.COLOR_PRIMARY, activity.COLOR_TEXT_SECONDARY}));
            addView(choice, new LinearLayout.LayoutParams(activity.dp(48), -2));

            // 卡片正文区域作为唯一编辑入口；复选框只负责批量选择。
            editArea.setGravity(Gravity.CENTER_VERTICAL);
            editArea.setMinimumHeight(activity.dp(66));
            editArea.setFocusable(true);
            editArea.setScreenReaderFocusable(true);
            editArea.setFilterTouchesWhenObscured(true);
            LinearLayout labels = ui.column();
            labels.setPaddingRelative(activity.dp(8), activity.dp(7), activity.dp(5), activity.dp(7));
            labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            title.setTextSize(15);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            pkg.setTextSize(12);
            pkg.setMaxLines(1);
            pkg.setEllipsize(android.text.TextUtils.TruncateAt.END);
            metadata.setTextSize(12);
            metadata.setMaxLines(1);
            metadata.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(title);
            labels.addView(pkg, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, -2);
            metaParams.topMargin = activity.dp(2);
            labels.addView(metadata, metaParams);
            editArea.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

            pencil.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            editArea.addView(pencil, new LinearLayout.LayoutParams(activity.dp(40), activity.dp(40)));
            addView(editArea, new LinearLayout.LayoutParams(0, -2, 1f));
        }

        private void setCardState(boolean selected) {
            int surface = selected ? mixColor(activity.COLOR_SURFACE, activity.COLOR_PRIMARY, 0.10f)
                    : activity.COLOR_SURFACE;
            int stroke = selected ? activity.COLOR_PRIMARY : Color.argb(34,
                    Color.red(activity.COLOR_TEXT_SECONDARY), Color.green(activity.COLOR_TEXT_SECONDARY),
                    Color.blue(activity.COLOR_TEXT_SECONDARY));
            GradientDrawable shape = (GradientDrawable) activity.roundedBg(surface, 13);
            shape.setStroke(activity.dp(selected ? 2 : 1), stroke);
            setBackground(shape);
            editArea.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x26000000 | (activity.COLOR_PRIMARY & 0x00FFFFFF)),
                    activity.roundedBg(Color.TRANSPARENT, 10), activity.roundedBg(Color.WHITE, 10)));
        }

        void bind(RuleList list, Row row) {
            boolean chosen = list.selected.contains(row.entry.id);
            setCardState(chosen);
            String details = typeLabel(row) + "  ·  " + row.orderedPaths.size() + " 个字段  ·  "
                    + (row.entry.enabled ? "已启用" : "已停用");
            title.setText(row.appName);
            pkg.setText(row.packageName);
            SpannableStringBuilder detail = new SpannableStringBuilder(details);
            String state = row.entry.enabled ? "已启用" : "已停用";
            int stateStart = details.lastIndexOf(state);
            if (stateStart >= 0) {
                detail.setSpan(new ForegroundColorSpan(row.entry.enabled
                        ? activity.COLOR_PRIMARY : activity.COLOR_TEXT_SECONDARY), stateStart,
                        stateStart + state.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            metadata.setText(detail);
            choice.setContentDescription((chosen ? "取消选择" : "选择") + "规则 · " + row.appName
                    + " · " + row.packageName + " · " + typeLabel(row));
            choice.setChecked(chosen);
            choice.setOnClickListener(view -> {
                if (window != list.page) return;
                if (choice.isChecked()) list.selected.add(row.entry.id);
                else list.selected.remove(row.entry.id);
                setCardState(choice.isChecked());
                list.refreshSelection();
            });
            editArea.setContentDescription("编辑规则 · " + row.appName + " · " + row.packageName + " · " + details);
            editArea.setOnClickListener(view -> {
                if (window == list.page) edit(list.listing.baseline, row);
            });
        }
    }

    private static String typeLabel(Row row) {
        return row.capture == null && row.notificationType == null ? "自定义匹配"
                : IslandRuleEditorUi.typeLabel(row.notificationType);
    }

    private void edit(long baseline, Row row) {
        long generation = ++page;
        IslandRuleEditorUi.Page loading = ui.new Page("编辑规则");
        loading.body.addView(ui.secondary("正在读取规则和示例…", 14));
        loading.action("取消", false, this::open);
        show(loading, false);
        worker.execute(() -> {
            try (IslandRuleRepository.ReadView view = IslandRuleImportUi.repository(app).openRead()) {
                if (view.revision != baseline) throw new IslandRuleRepository.Failure(IslandRuleRepository.Code.BASELINE_CHANGED);
                byte[] original = view.readById(row.entry.id);
                IslandRule rule = new IslandRuleCodec().decode(original).rule;
                if (rule == null) throw new IOException("RULE_EDITOR_FORMAT");
                IslandRuleSample sample = view.readSample(row.entry);
                main.post(() -> {
                    if (!current(generation)) return;
                    if (row.capture == null) editComplex(baseline, row, sample, original);
                    else editPaths(baseline, row, rule, sample, original);
                });
            } catch (IOException | RuntimeException failure) {
                String message = IslandRuleImportUi.failureText(failure);
                main.post(() -> {
                    if (!current(generation)) return;
                    dismissPage();
                    status.accept(message);
                });
            }
        });
    }

    private LinearLayout editContent(IslandRuleEditorUi.Page next, Row row) {
        LinearLayout content = next.scrolling();
        content.addView(ui.heading(row.appName, 15), ui.spaced(3));
        content.addView(ui.secondary(typeLabel(row), 12), ui.spaced(12));
        return content;
    }

    private void editComplex(long baseline, Row row, IslandRuleSample sample, byte[] original) {
        IslandRuleEditorUi.Page next = ui.new Page("编辑规则");
        LinearLayout content = editContent(next, row);
        Map<IslandRulePaths.Entry, String> examples = examples(row, sample);
        content.addView(ui.secondary("这条规则包含自定义条件，可在高级操作中编辑。", 14), ui.spaced(12));
        content.addView(ui.heading("当前字段顺序", 15), ui.spaced(6));
        for (String path : row.orderedPaths) {
            String example = examples.getOrDefault(IslandRulePaths.find(path), IslandRuleEditorUi.NO_SAMPLE);
            content.addView(ui.fieldLabel(example, path), ui.spaced(6));
        }
        content.addView(ui.tonal(R.drawable.ic_rule_edit, "高级操作 · 编辑 JSON",
                () -> editJson(baseline, row, original)), ui.spaced(8));
        next.action("取消", false, this::open);
        show(next, true);
    }

    private void editJson(long baseline, Row row, byte[] original) {
        IslandRuleEditorUi.Page next = ui.new Page("高级编辑");
        LinearLayout content = editContent(next, row);
        EditText input = new EditText(activity);
        input.setSaveEnabled(false);
        input.setTextSize(12.5f);
        input.setTextColor(activity.COLOR_TEXT_PRIMARY);
        input.setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10));
        input.setMinHeight(activity.dp(160));
        GradientDrawable inputShape = (GradientDrawable) activity.roundedBg(activity.COLOR_SURFACE, 10);
        inputShape.setStroke(activity.dp(1), activity.COLOR_DIVIDER);
        input.setBackground(inputShape);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(IslandRuleRepository.MAX_RULE_BYTES)});
        String before = new String(original, StandardCharsets.UTF_8);
        input.setText(before);
        content.addView(input, ui.spaced(4));
        android.widget.TextView feedback = ui.secondary("", 13);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(feedback, ui.spaced(0));
        next.exitEdits(() -> !before.contentEquals(input.getText()), this::open);
        next.action("取消", false, next::cancelEdits);
        Button save = next.action("保存更改", true, () -> update(baseline, row,
                input.getText().toString().getBytes(StandardCharsets.UTF_8), next, false));
        IslandRuleEditorUi.setActionEnabled(save, false);
        final class Validation implements android.text.TextWatcher {
            private long editRevision;
            private final Runnable delayed = this::validate;

            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int beforeCount, int count) { }
            @Override public void afterTextChanged(android.text.Editable text) {
                editRevision++;
                main.removeCallbacks(delayed);
                IslandRuleEditorUi.setActionEnabled(save, false);
                if (before.contentEquals(text)) {
                    feedback.setText("");
                    return;
                }
                feedback.setText("正在校验…");
                main.postDelayed(delayed, 250L);
            }

            private void validate() {
                if (window != next || worker.isShutdown()) return;
                long revision = editRevision;
                byte[] bytes = input.getText().toString().getBytes(StandardCharsets.UTF_8);
                worker.execute(() -> {
                    String problem = IslandSavedRuleFields.validate(bytes);
                    main.post(() -> {
                        if (window != next || revision != editRevision) return;
                        feedback.setText(problem);
                        IslandRuleEditorUi.setActionEnabled(save, problem == null);
                    });
                });
            }

            void release() {
                editRevision++;
                main.removeCallbacks(delayed);
                input.removeTextChangedListener(this);
            }
        }
        Validation validation = new Validation();
        input.addTextChangedListener(validation);
        next.onRelease(validation::release);
        show(next, true);
    }

    private static Map<IslandRulePaths.Entry, String> examples(Row row, IslandRuleSample sample) {
        Map<IslandRulePaths.Entry, String> result = new LinkedHashMap<>();
        if (sample != null) {
            for (IslandRuleSample.Field field : sample.fields) {
                IslandRulePaths.Entry path = IslandRulePaths.find(field.path);
                if (IslandRulePaths.isRuntimeAllowed(path) && (row.notificationType == null
                        || row.notificationType.paths.contains(field.path))) {
                    result.put(path, field.value == null ? IslandRuleEditorUi.REDACTED
                            : IslandRuleEditorUi.readableExample(field.value));
                }
            }
        }
        // 未附带示例或示例不全时，补列规则已经保存的字段。
        for (String selected : row.orderedPaths) {
            IslandRulePaths.Entry path = IslandRulePaths.find(selected);
            if (IslandRulePaths.isRuntimeAllowed(path)) result.putIfAbsent(path, IslandRuleEditorUi.NO_SAMPLE);
        }
        return result;
    }

    private void editPaths(long baseline, Row row, IslandRule rule, IslandRuleSample sample, byte[] original) {
        IslandRuleEditorUi.Page next = ui.new Page("编辑规则");
        new IslandRuleFieldsUi(ui, next, editContent(next, row), rule, row.capture, sample, original,
                () -> window == next && !next.isBusy(), this::open, bytes -> update(baseline, row, bytes, next, false));
        show(next, true);
    }

    private void update(long baseline, Row row, byte[] bytes, IslandRuleEditorUi.Page editor, boolean force) {
        if (window != editor || editor.isBusy() || worker.isShutdown()) return;
        long generation = ++page;
        editor.notice(null);
        editor.setBusy(true);
        worker.execute(() -> {
            try {
                IslandRuleRepository store = IslandRuleImportUi.repository(app);
                try (IslandRuleRepository.Pending candidate = store.prepareUpdate(baseline, row.entry.id,
                        bytes, IslandRuleImportUi.environment(app))) {
                    if (!force && candidate.canApprove(true) && !candidate.canApprove(false)) {
                        main.post(() -> {
                            if (!current(generation) || window != editor) return;
                            editor.setBusy(false);
                            editor.ask("这条规则声明的系统界面版本与当前设备不一致，仍然保存？",
                                    "继续编辑", "仍然保存", () -> update(baseline, row, bytes, editor, true));
                        });
                        return;
                    }
                    if (!candidate.canApprove(force)) throw new IslandRuleRepository.Failure(IslandRuleRepository.Code.VALIDATION);
                    long saved = store.commit(candidate, force, IslandRuleImportUi.environment(app));
                    main.post(() -> { if (current(generation) && window == editor) closeWindow(); });
                    committed(generation, saved, false);
                }
            } catch (IOException | RuntimeException failure) {
                String message = report(generation, failure);
                main.post(() -> {
                    if (!current(generation) || window != editor) return;
                    editor.setBusy(false);
                    editor.notice(message);
                });
            }
        });
    }

    private void change(long baseline, List<Row> rows, BatchAction action) {
        Set<String> targets = new HashSet<>();
        for (Row row : rows) targets.add(row.entry.id);
        if (targets.isEmpty() || worker.isShutdown()) return;
        long generation = ++page;
        closeWindow();
        worker.execute(() -> {
            try {
                IslandRuleRepository store = IslandRuleImportUi.repository(app);
                boolean review = false;
                long saved;
                if (action == BatchAction.ENABLE) {
                    saved = store.changeEnabled(baseline, targets, true, IslandRuleImportUi.environment(app));
                } else {
                    boolean delete = action == BatchAction.DELETE;
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
                        IslandRuleProjectionConsent.Reduction reduction = consent.reduceMany(store, baseline, targets, delete);
                        saved = reduction.revision;
                        review = reduction.result == IslandRuleProjectionConsent.ReductionResult.SAVED_REAUTHORIZE;
                    } else if (delete) {
                        saved = store.deleteAll(baseline, targets);
                    } else {
                        saved = store.changeEnabled(baseline, targets, false, null);
                    }
                }
                if (saved != baseline) committed(generation, saved, review);
                else main.post(() -> { if (current(generation)) open(); });
            } catch (IOException | RuntimeException failure) {
                String message = report(generation, failure);
                main.post(() -> {
                    if (!current(generation)) return;
                    IslandRuleEditorUi.Page next = ui.new Page("规则操作结果");
                    next.scrolling().addView(ui.secondary(message, 14));
                    next.action("返回列表", true, this::open);
                    show(next, false);
                });
            }
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

    private String report(long generation, Throwable failure) {
        if (failure instanceof IslandRuleRepository.Failure) {
            IslandRuleRepository.Code code = ((IslandRuleRepository.Failure) failure).code;
            if (code == IslandRuleRepository.Code.COMMITTED_CLEANUP_FAILED || code == IslandRuleRepository.Code.DURABILITY_UNCERTAIN) {
                notifyReaders();
                main.post(ruleUi::refreshSavedRevision);
            }
        }
        String message = failure instanceof IslandRuleRepository.Failure
                && ((IslandRuleRepository.Failure) failure).code == IslandRuleRepository.Code.BASELINE_CHANGED
                ? "规则列表已更新，请重新选择。" : IslandRuleImportUi.failureText(failure);
        main.post(() -> { if (current(generation)) status.accept(message); });
        return message;
    }

    private void show(IslandRuleEditorUi.Page next, boolean hasExample) {
        closeWindow();
        window = next;
        next.dialog.setOnDismissListener(d -> {
            if (window == next) dismissPage();
        });
        next.show();
        if (hasExample) next.watchFocus(() -> {
            if (window == next) dismissPage();
        });
    }

    private void closeWindow() {
        IslandRuleEditorUi.Page previous = window;
        if (previous == null) return;
        window = null;
        previous.release();
        previous.dialog.setOnDismissListener(null);
        previous.dialog.dismiss();
    }
}
