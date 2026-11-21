/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 已保存规则的预览、正文选用和批量操作。提交仍使用管理页的 ID/修订校验。 */
final class IslandRuleFieldsUi {
    private final IslandRuleEditorUi ui;
    private final IslandRuleEditorUi.Page page;
    private final IslandSavedRuleFields document;
    private final IslandRuleFieldDraft draft;
    private final IslandRule rule;
    private final BooleanSupplier current;
    private final Map<IslandRulePaths.Entry, CheckBox> choices = new LinkedHashMap<>();
    private final Map<IslandRulePaths.Entry, IslandRuleSample.Field> examples = new LinkedHashMap<>();
    private final TextView preview;
    private final TextView validation;
    private final TextView count;
    private final Button selectAll;
    private final Button clear;
    private final Button invert;
    private final LinearLayout selected;
    private final Button delete;
    private final Button undo;
    private final Button restore;
    private final Button save;
    private byte[] proposal;

    IslandRuleFieldsUi(IslandRuleEditorUi ui, IslandRuleEditorUi.Page page, LinearLayout content,
            IslandRule rule, IslandRuleProjection.Row captured, IslandRuleSample sample, byte[] original,
            BooleanSupplier current, Runnable finish, Consumer<byte[]> update) {
        this.ui = ui;
        this.page = page;
        this.rule = rule;
        this.current = current;
        document = new IslandSavedRuleFields(original, captured);
        List<IslandRulePaths.Entry> order = new ArrayList<>();
        for (String path : captured.paths) order.add(IslandRulePaths.find(path));
        if (sample != null) {
            for (IslandRuleSample.Field field : sample.fields) {
                IslandRulePaths.Entry path = IslandRulePaths.find(field.path);
                if (IslandRulePaths.isRuntimeAllowed(path) && (captured.notificationType == null
                        || captured.notificationType.paths.contains(field.path))) examples.put(path, field);
            }
        }
        if (captured.notificationType != null) {
            for (String path : captured.notificationType.paths) examples.putIfAbsent(IslandRulePaths.find(path), null);
        }
        for (IslandRulePaths.Entry path : order) examples.putIfAbsent(path, null);
        Map<FieldGroup, List<IslandRulePaths.Entry>> groups = new java.util.EnumMap<>(FieldGroup.class);
        for (IslandRulePaths.Entry path : examples.keySet()) {
            groups.computeIfAbsent(FieldGroup.of(path), group -> new ArrayList<>()).add(path);
        }
        List<IslandRulePaths.Entry> candidates = new ArrayList<>();
        for (List<IslandRulePaths.Entry> paths : groups.values()) candidates.addAll(paths);
        draft = new IslandRuleFieldDraft(order, candidates);

        SettingsActivity activity = (SettingsActivity) content.getContext();

        // 预览采用独立的视觉卡片，示例正文和状态提示不再与字段清单混在一起。
        LinearLayout previewCard = ui.column();
        previewCard.setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12));
        previewCard.setBackground(activity.roundedBg(activity.COLOR_SURFACE_HIGH, 16));
        TextView previewLabel = ui.secondary("实时正文预览", 12);
        previewLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        previewCard.addView(previewLabel, ui.spaced(5));
        preview = ui.primary("", 16);
        preview.setLineSpacing(activity.dp(4), 1f);
        preview.setGravity(Gravity.CENTER_VERTICAL);
        preview.setMinHeight(activity.dp(44));
        preview.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        previewCard.addView(preview, ui.spaced(4));
        validation = ui.secondary("", 12);
        validation.setPadding(activity.dp(9), activity.dp(7), activity.dp(9), activity.dp(7));
        validation.setBackground(activity.roundedBg(Color.argb(20, 220, 90, 70), 9));
        validation.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        previewCard.addView(validation, ui.spaced(0));
        validation.setVisibility(View.GONE);
        content.addView(previewCard, ui.spaced(12));

        // 正文排序区单独成卡，顶部集中显示数量和操作说明。
        LinearLayout selectedCard = ui.column();
        selectedCard.setPadding(activity.dp(12), activity.dp(11), activity.dp(12), activity.dp(10));
        selectedCard.setBackground(activity.roundedBg(activity.COLOR_SURFACE, 14));
        LinearLayout selectedHeader = new LinearLayout(activity);
        selectedHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView selectedTitle = ui.heading("正文顺序", 15);
        selectedHeader.addView(selectedTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        count = ui.secondary("", 12);
        count.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        count.setGravity(Gravity.CENTER);
        count.setPadding(activity.dp(9), activity.dp(5), activity.dp(9), activity.dp(5));
        count.setBackground(activity.roundedBg(activity.COLOR_SURFACE_HIGH, 10));
        selectedHeader.addView(count, new LinearLayout.LayoutParams(-2, -2));
        selectedCard.addView(selectedHeader, ui.spaced(4));
        selectedCard.addView(ui.secondary("长按字段拖动排序；勾选多个字段后，可整体移动。", 12), ui.spaced(8));
        selected = ui.column();
        selected.setPadding(0, activity.dp(2), 0, activity.dp(2));
        selectedCard.addView(selected, ui.spaced(7));
        delete = ui.danger(R.drawable.ic_rule_delete, "移除选中", () -> change(draft::deleteChecked));
        undo = ui.tonal("撤销", () -> change(draft::undo));
        restore = ui.tonal("恢复初始", () -> change(draft::restore));
        addActions(selectedCard, activity, delete, undo, restore);
        content.addView(selectedCard, ui.spaced(12));

        // 可选字段分组展示；字段本身改成轻量标签，而不是标准表单复选框。
        LinearLayout availableHeader = new LinearLayout(activity);
        availableHeader.setGravity(Gravity.CENTER_VERTICAL);
        availableHeader.addView(ui.heading("添加字段", 15), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView availableHint = ui.secondary("点击添加", 12);
        availableHeader.addView(availableHint);
        content.addView(availableHeader, ui.spaced(6));
        selectAll = ui.tonal(R.drawable.ic_rule_select_all, "全选可用", () -> change(draft::selectAll));
        clear = ui.tonal(R.drawable.ic_rule_clear, "清空已选", () -> change(draft::clear));
        invert = ui.tonal("反选", () -> change(draft::invert));
        addActions(content, activity, selectAll, clear, invert);

        for (FieldGroup group : FieldGroup.values()) {
            List<IslandRulePaths.Entry> paths = groups.get(group);
            if (paths == null || paths.isEmpty()) continue;
            LinearLayout groupCard = ui.column();
            groupCard.setPadding(activity.dp(11), activity.dp(9), activity.dp(11), activity.dp(9));
            groupCard.setBackground(activity.roundedBg(activity.COLOR_SURFACE, 14));
            TextView groupTitle = ui.heading(group.label, 12);
            groupTitle.setTextColor(activity.COLOR_TEXT_SECONDARY);
            groupCard.addView(groupTitle, ui.spaced(7));
            SettingsFlowLayout chips = new SettingsFlowLayout(activity, activity.dp(8), activity.dp(6));
            groupCard.addView(chips, ui.spaced(0));
            for (IslandRulePaths.Entry path : paths) {
                CheckBox chip = ui.fieldChip(example(path), path);
                choices.put(path, chip);
                styleChoice(chip, activity, false, true);
                chips.addView(chip);
                chip.setOnClickListener(view -> {
                    if (!change(() -> draft.toggle(path))) {
                        chip.setChecked(draft.order().contains(path));
                        styleChoice(chip, activity, chip.isChecked(), chip.isEnabled());
                    }
                });
            }
            content.addView(groupCard, ui.spaced(8));
        }
        page.exitEdits(draft::dirty, finish);
        page.action("取消", false, page::cancelEdits);
        save = page.action("保存更改", true, () -> {
            if (current.getAsBoolean() && proposal != null) update.accept(proposal);
        });
        redraw();
    }

    private enum FieldGroup {
        BASIC("基础信息"), PROGRESS("步骤与进度"), ISLAND("岛内容"), OTHER("其他文本");
        final String label;
        FieldGroup(String label) { this.label = label; }

        static FieldGroup of(IslandRulePaths.Entry path) {
            switch (path.templateGroup) {
                case "baseInfo":
                case "fallback":
                case "legacy": return BASIC;
                case "stepInfo":
                case "progressInfo":
                case "multiProgressInfo": return PROGRESS;
                case "param_island":
                    return path.path.contains("/progressTextInfo/") || path.path.endsWith("/progress")
                            ? PROGRESS : ISLAND;
                default: return OTHER;
            }
        }
    }

    private void addActions(LinearLayout content, SettingsActivity activity, Button... buttons) {
        SettingsFlowLayout actions = new SettingsFlowLayout(activity, activity.dp(8), activity.dp(6));
        for (Button button : buttons) actions.addView(button);
        content.addView(actions, ui.spaced(10));
    }

    private void styleChoice(CheckBox chip, SettingsActivity activity, boolean checked, boolean enabled) {
        int surface = checked
                ? mixColor(activity.COLOR_SURFACE_HIGH, activity.COLOR_PRIMARY, 0.16f)
                : activity.COLOR_SURFACE_HIGH;
        int stroke = checked ? activity.COLOR_PRIMARY : Color.argb(36,
                Color.red(activity.COLOR_TEXT_SECONDARY), Color.green(activity.COLOR_TEXT_SECONDARY),
                Color.blue(activity.COLOR_TEXT_SECONDARY));
        GradientDrawable shape = (GradientDrawable) activity.roundedBg(surface, 10);
        shape.setStroke(activity.dp(checked ? 2 : 1), stroke);
        chip.setButtonDrawable(null);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setMinHeight(activity.dp(40));
        chip.setPaddingRelative(activity.dp(11), activity.dp(7), activity.dp(11), activity.dp(7));
        chip.setTextSize(12.5f);
        chip.setTextColor(enabled
                ? (checked ? activity.COLOR_PRIMARY : activity.COLOR_TEXT_PRIMARY)
                : activity.COLOR_TEXT_SECONDARY);
        chip.setTypeface(Typeface.DEFAULT, checked ? Typeface.BOLD : Typeface.NORMAL);
        chip.setMaxLines(2);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x26000000 | (activity.COLOR_PRIMARY & 0x00FFFFFF)),
                shape, activity.roundedBg(Color.WHITE, 10)));
        chip.setAlpha(enabled ? 1f : 0.55f);
    }

    private int mixColor(int base, int tint, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        return Color.rgb(
                Math.round(Color.red(base) * (1f - a) + Color.red(tint) * a),
                Math.round(Color.green(base) * (1f - a) + Color.green(tint) * a),
                Math.round(Color.blue(base) * (1f - a) + Color.blue(tint) * a));
    }

    private boolean change(BooleanSupplier edit) {
        if (!current.getAsBoolean() || !edit.getAsBoolean()) return false;
        redraw();
        return true;
    }

    void saving(boolean saving) {
        IslandRuleEditorUi.setActionEnabled(save, !saving && proposal != null);
    }

    private void redraw() {
        List<IslandRulePaths.Entry> order = draft.order();
        IslandRuleOrderUi.renderGroup(selected, order, this::example, draft.checked(), (path, checked) -> {
            if (!current.getAsBoolean()) return;
            draft.check(path, checked);
            IslandRuleEditorUi.setActionEnabled(delete, !draft.checked().isEmpty());
        }, (group, gap) -> {
            if (current.getAsBoolean() && draft.move(group, gap)) redraw();
        });
        int limit = IslandCaptureEditor.MAX_SELECTION;
        boolean full = order.size() >= limit;
        boolean hasUnused = false;
        for (Map.Entry<IslandRulePaths.Entry, CheckBox> choice : choices.entrySet()) {
            boolean used = order.contains(choice.getKey());
            boolean available = IslandRulePaths.isRuntimeAllowed(choice.getKey());
            hasUnused |= !used && available;
            boolean enabled = used || (!full && available);
            choice.getValue().setChecked(used);
            choice.getValue().setEnabled(enabled);
            styleChoice(choice.getValue(), (SettingsActivity) choice.getValue().getContext(), used, enabled);
        }
        count.setText(order.size() + " / " + limit + (full ? " 已满" : " 已选"));
        IslandRuleEditorUi.setActionEnabled(selectAll, hasUnused && !full);
        IslandRuleEditorUi.setActionEnabled(clear, !order.isEmpty());
        IslandRuleEditorUi.setActionEnabled(invert, hasUnused || !order.isEmpty());
        preview.setText(preview(order));
        IslandRuleEditorUi.setActionEnabled(delete, !draft.checked().isEmpty());
        IslandRuleEditorUi.setActionEnabled(undo, draft.canUndo());
        IslandRuleEditorUi.setActionEnabled(restore, draft.dirty());
        byte[] changed = draft.dirty() ? document.update(order) : null;
        String problem = draft.dirty() ? IslandSavedRuleFields.validate(changed) : null;
        validation.setText(problem);
        validation.setVisibility(problem == null ? View.GONE : View.VISIBLE);
        proposal = problem == null ? changed : null;
        saving(false);
    }

    private String example(IslandRulePaths.Entry field) {
        IslandRuleSample.Field sample = examples.get(field);
        if (sample == null) return IslandRuleEditorUi.NO_SAMPLE;
        if (sample.value == null) return IslandRuleEditorUi.REDACTED;
        return IslandRuleEditorUi.readableExample(sample.value);
    }

    private String preview(List<IslandRulePaths.Entry> order) {
        Map<String, String> bindings = new LinkedHashMap<>();
        List<DisplaySpec.Projection> projections = new ArrayList<>();
        for (IslandRulePaths.Entry path : order) {
            IslandRuleSample.Field sample = examples.get(path);
            if (sample == null || sample.value == null) continue;
            String id = "field" + (projections.size() + 1);
            bindings.put(id, sample.value);
            projections.add(new DisplaySpec.Projection("extra", id));
        }
        if (order.isEmpty()) return "请选择用于正文的字段。";
        if (projections.isEmpty()) return "没有可用于预览的示例文字。";
        DisplaySpec display = new DisplaySpec(projections, rule.display.separator, rule.display.dedupe);
        IslandRulePostProcessor.Result result = new IslandRulePostProcessor().processBindings(bindings, display, new IslandRuleBudget());
        return result.isSuccess() ? result.text : "示例未生成正文。";
    }
}
