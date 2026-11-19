/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.function.BiConsumer;

/** 捕获草稿和已保存规则共用一张顺序表；拖动只传登记路径，不传字段值。 */
final class IslandRuleOrderUi {
    private IslandRuleOrderUi() { }

    static void render(LinearLayout parent, List<IslandRulePaths.Entry> order,
            BiConsumer<IslandRulePaths.Entry, Integer> move) {
        parent.removeAllViews();
        Context context = parent.getContext();
        for (int i = 0; i < order.size(); i++) {
            IslandRulePaths.Entry path = order.get(i);
            final int index = i;
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView handle = new TextView(context);
            handle.setText(path.path);
            handle.setContentDescription("拖动排序 " + path.path);
            handle.setOnLongClickListener(v -> v.startDragAndDrop(
                    new ClipData("", new String[] {ClipDescription.MIMETYPE_TEXT_PLAIN}, new ClipData.Item("")),
                    new View.DragShadowBuilder(v), path, 0));
            row.setOnDragListener((v, event) -> {
                if (!(event.getLocalState() instanceof IslandRulePaths.Entry)) return false;
                if (event.getAction() == DragEvent.ACTION_DROP) move.accept((IslandRulePaths.Entry) event.getLocalState(), index);
                return true;
            });
            row.addView(handle, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(step(context, "上移", path, i > 0, () -> move.accept(path, index - 1)));
            row.addView(step(context, "下移", path, i + 1 < order.size(), () -> move.accept(path, index + 1)));
            parent.addView(row);
        }
    }

    private static Button step(Context context, String text, IslandRulePaths.Entry path, boolean enabled, Runnable action) {
        Button button = new Button(context);
        button.setText(text);
        button.setContentDescription(text + " " + path.path);
        button.setEnabled(enabled);
        button.setOnClickListener(v -> action.run());
        return button;
    }
}
