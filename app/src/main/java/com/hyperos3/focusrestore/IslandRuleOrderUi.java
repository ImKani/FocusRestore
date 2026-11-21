/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.text.TextUtils;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** 捕获草稿和已保存规则共用一张图标样式的顺序表。 */
final class IslandRuleOrderUi {
    private IslandRuleOrderUi() { }

    static void render(LinearLayout parent, List<IslandRulePaths.Entry> order,
            Function<IslandRulePaths.Entry, String> example,
            BiConsumer<IslandRulePaths.Entry, Integer> move) {
        reset(parent);
        SettingsActivity activity = (SettingsActivity) parent.getContext();
        List<IslandRulePaths.Entry> items = new ArrayList<>(order);
        BiConsumer<Integer, Integer> reorder = (from, to) -> move.accept(items.get(from), to);
        for (int i = 0; i < items.size(); i++) {
            LinearLayout card = addCard(activity, parent, items.get(i), example, i, null);
            SettingsSortRows.accessibleMoves(card, i, items.size(), reorder);
        }
        parent.setTag(new SettingsSortRows(activity, parent, reorder));
    }

    static void renderGroup(LinearLayout parent, List<IslandRulePaths.Entry> order,
            Function<IslandRulePaths.Entry, String> example, Set<IslandRulePaths.Entry> checked,
            BiConsumer<IslandRulePaths.Entry, Boolean> toggle,
            BiConsumer<List<IslandRulePaths.Entry>, Integer> move) {
        reset(parent);
        SettingsActivity activity = (SettingsActivity) parent.getContext();
        IslandRuleEditorUi ui = new IslandRuleEditorUi(activity);
        List<IslandRulePaths.Entry> items = new ArrayList<>(order);
        for (int i = 0; i < items.size(); i++) {
            IslandRulePaths.Entry path = items.get(i);
            LinearLayout card = addCard(activity, parent, path, example, i, ui);
            CheckBox check = ui.batchFieldCheck(card.getContentDescription().toString());
            check.setChecked(checked.contains(path));
            check.setOnClickListener(view -> toggle.accept(path, check.isChecked()));
            card.addView(check, 0, new LinearLayout.LayoutParams(activity.dp(48), -2));
            SettingsSortRows.accessibleMoves(card, i, items.size(),
                    (from, to) -> move.accept(Collections.singletonList(items.get(from)), to));
        }
        parent.setTag(new SettingsSortRows(activity, parent,
                row -> checked.contains(items.get(parent.indexOfChild(row))), (indexes, gap) -> {
                    List<IslandRulePaths.Entry> fields = new ArrayList<>(indexes.size());
                    for (int index : indexes) fields.add(items.get(index));
                    move.accept(fields, gap);
                }));
    }

    private static void reset(LinearLayout parent) {
        IslandRuleEditorUi.scrub(parent);
        parent.removeAllViews();
    }

    private static LinearLayout addCard(SettingsActivity activity, LinearLayout parent,
            IslandRulePaths.Entry path, Function<IslandRulePaths.Entry, String> example, int position,
            IslandRuleEditorUi fieldUi) {
        String value = example.apply(path);
        String title = TextUtils.isEmpty(value) ? "无示例文字" : value;
        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        String name = fieldUi == null ? title : IslandRuleEditorUi.fieldName(path);
        TextView primary = activity.text(name, 15, activity.COLOR_TEXT_PRIMARY);
        primary.setMaxLines(2);
        primary.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(primary);
        TextView detail = activity.text(fieldUi == null ? path.path : title, 12, activity.COLOR_TEXT_SECONDARY);
        if (fieldUi != null) {
            detail.setMaxLines(2);
            detail.setEllipsize(TextUtils.TruncateAt.END);
        }
        labels.addView(detail);
        LinearLayout card = SettingsSortRows.card(activity, labels);
        if (fieldUi != null) card.setBackground(fieldUi.fieldSurface(true, 18));
        String label = fieldUi == null ? title : name + "，" + title;
        card.setContentDescription(label + "，" + path.path + "，第 " + (position + 1) + " 位");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = activity.dp(4);
        parent.addView(card, params);
        return card;
    }
}
