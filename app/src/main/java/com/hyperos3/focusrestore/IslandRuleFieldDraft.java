/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 编辑副本。用于显示和用于批量操作的字段集合各自独立。 */
final class IslandRuleFieldDraft {
    private final List<IslandRulePaths.Entry> initial;
    private final Set<IslandRulePaths.Entry> available;
    private final Set<IslandRulePaths.Entry> checked = new LinkedHashSet<>();
    private List<IslandRulePaths.Entry> order;
    private List<IslandRulePaths.Entry> previous;

    IslandRuleFieldDraft(List<IslandRulePaths.Entry> initial, Collection<IslandRulePaths.Entry> available) {
        this.initial = new ArrayList<>(initial);
        this.available = new LinkedHashSet<>(available);
        this.available.addAll(initial);
        order = new ArrayList<>(initial);
    }

    List<IslandRulePaths.Entry> order() { return Collections.unmodifiableList(order); }
    Set<IslandRulePaths.Entry> checked() { return Collections.unmodifiableSet(checked); }
    boolean dirty() { return !initial.equals(order); }
    boolean canUndo() { return previous != null; }

    boolean toggle(IslandRulePaths.Entry field) {
        List<IslandRulePaths.Entry> next = new ArrayList<>(order);
        if (!next.remove(field)) {
            if (!IslandRulePaths.isRuntimeAllowed(field) || !available.contains(field)
                    || next.size() >= IslandCaptureEditor.MAX_SELECTION) return false;
            next.add(field);
        }
        return change(next);
    }

    boolean selectAll() {
        List<IslandRulePaths.Entry> next = new ArrayList<>(order);
        appendAvailable(next, order);
        return change(next);
    }

    boolean clear() { return change(new ArrayList<>()); }

    boolean invert() {
        List<IslandRulePaths.Entry> next = new ArrayList<>();
        appendAvailable(next, order);
        return change(next);
    }

    private void appendAvailable(List<IslandRulePaths.Entry> next, Collection<IslandRulePaths.Entry> excluded) {
        // 按候选显示顺序补位。全选保留正文顺序，反选按同一顺序取到上限。
        for (IslandRulePaths.Entry field : available) {
            if (next.size() >= IslandCaptureEditor.MAX_SELECTION) break;
            if (!excluded.contains(field) && IslandRulePaths.isRuntimeAllowed(field)) next.add(field);
        }
    }

    void check(IslandRulePaths.Entry field, boolean selected) {
        if (!selected) checked.remove(field);
        else if (order.contains(field)) checked.add(field);
    }

    boolean deleteChecked() {
        List<IslandRulePaths.Entry> next = new ArrayList<>(order);
        next.removeAll(checked);
        return change(next);
    }

    boolean move(List<IslandRulePaths.Entry> group, int gap) {
        List<IslandRulePaths.Entry> next = moveGroup(order, group, gap);
        return next != null && change(next);
    }

    /** gap 是在剩余项中的插入位置，允许最前和末尾。组内和其余项都沿用原相对次序。 */
    static <T> List<T> moveGroup(List<T> order, Collection<T> marked, int gap) {
        if (marked.isEmpty() || !order.containsAll(marked)) return null;
        List<T> group = new ArrayList<>();
        List<T> remaining = new ArrayList<>();
        for (T field : order) (marked.contains(field) ? group : remaining).add(field);
        if (gap < 0 || gap > remaining.size()) return null;
        remaining.addAll(gap, group);
        return remaining;
    }

    boolean restore() { return change(new ArrayList<>(initial)); }

    boolean undo() {
        if (previous == null) return false;
        order = previous;
        previous = null;
        checked.retainAll(order);
        return true;
    }

    private boolean change(List<IslandRulePaths.Entry> next) {
        if (order.equals(next)) return false;
        previous = order;
        order = next;
        checked.retainAll(order);
        return true;
    }
}
