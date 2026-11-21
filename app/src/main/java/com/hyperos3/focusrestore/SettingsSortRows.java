/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.os.Bundle;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** 设置页图标和规则字段共同使用的长按拖动。松手才提交，取消只恢复原排列。 */
final class SettingsSortRows implements View.OnTouchListener, View.OnAttachStateChangeListener {
    private final SettingsActivity activity;
    private final LinearLayout container;
    private final int touchSlop;
    private final List<View> rows = new ArrayList<>();
    private final BiConsumer<Integer, Integer> commit;
    private final java.util.function.Predicate<View> isChecked;
    private final BiConsumer<List<Integer>, Integer> commitGroup;
    private final List<View> moving = new ArrayList<>();
    private List<View> groupPreview;
    private int groupGap;
    private float groupOffset;
    private float groupHeight;
    private boolean groupMoved;
    private final Runnable started = this::startDrag;
    private final Runnable autoScroll = this::scrollEdge;
    private View selected;
    private ScrollView scroll;
    private int pointerId = -1;
    private int initialSlot;
    private boolean dragging;
    private float downX;
    private float downY;
    private float grabOffset;
    private float lastRawY;
    private final int[] screenLocation = new int[2];

    SettingsSortRows(SettingsActivity activity, LinearLayout container, BiConsumer<Integer, Integer> commit) {
        this(activity, container, commit, null, null);
    }

    SettingsSortRows(SettingsActivity activity, LinearLayout container, java.util.function.Predicate<View> isChecked,
            BiConsumer<List<Integer>, Integer> commitGroup) {
        this(activity, container, null, isChecked, commitGroup);
    }

    private SettingsSortRows(SettingsActivity activity, LinearLayout container, BiConsumer<Integer, Integer> commit,
            java.util.function.Predicate<View> isChecked, BiConsumer<List<Integer>, Integer> commitGroup) {
        this.activity = activity;
        this.container = container;
        this.commit = commit;
        this.isChecked = isChecked;
        this.commitGroup = commitGroup;
        touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        container.setOnTouchListener(this);
        container.addOnAttachStateChangeListener(this);
    }

    static LinearLayout card(SettingsActivity activity, View label) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dp(14), activity.dp(8), activity.dp(14), activity.dp(8));
        row.setMinimumHeight(activity.dp(48));
        row.setBackground(activity.roundedBg(activity.COLOR_SURFACE_HIGH, 12));
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView handle = activity.text("≡", 20, activity.COLOR_TEXT_SECONDARY);
        handle.setGravity(Gravity.CENTER);
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(handle, new LinearLayout.LayoutParams(activity.dp(28), -2));
        row.setFocusable(true);
        return row;
    }

    static void accessibleMoves(View row, int slot, int count, BiConsumer<Integer, Integer> move) {
        int up = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId();
        int down = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (slot > 0) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(up, "上移"));
                if (slot + 1 < count) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(down, "下移"));
            }

            @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                if (action == up && slot > 0) { move.accept(slot, slot - 1); return true; }
                if (action == down && slot + 1 < count) { move.accept(slot, slot + 1); return true; }
                return super.performAccessibilityAction(host, action, arguments);
            }
        });
    }

    private void startDrag() {
        if (selected == null || !container.isAttachedToWindow()) return;
        if (commitGroup != null && !beginGroup()) return;
        dragging = true;
        ViewParent parent = container.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        for (; parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollView) {
                scroll = (ScrollView) parent;
                break;
            }
        }
        selected.setElevation(activity.dp(6));
        selected.setAlpha(0.94f);
        selected.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        scheduleEdgeScroll();
    }

    @Override public boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                finish(false);
                if (container.getChildCount() < 2) return false;
                float y = event.getY();
                for (int i = 0; i < container.getChildCount(); i++) {
                    View row = container.getChildAt(i);
                    rows.add(row);
                    if (y >= row.getTop() && y < row.getBottom()) selected = row;
                }
                if (selected == null) { rows.clear(); return false; }
                initialSlot = rows.indexOf(selected);
                pointerId = event.getPointerId(0);
                downX = event.getX();
                downY = y;
                grabOffset = y - selected.getTop();
                lastRawY = event.getRawY();
                container.postDelayed(started, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!validPointer(event)) return false;
                lastRawY = event.getRawY();
                if (!dragging) {
                    if (Math.abs(event.getX() - downX) > touchSlop
                            || Math.abs(event.getY() - downY) > touchSlop) finish(false);
                } else {
                    moveRow(event.getY());
                    scheduleEdgeScroll();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!validPointer(event)) return false;
                if (dragging) moveRow(event.getY());
                finish(true);
                return true;
            default:
                boolean handled = selected != null;
                finish(false);
                return handled;
        }
    }

    private boolean validPointer(MotionEvent event) {
        if (selected == null) return false;
        if (event.getPointerCount() == 1 && event.getPointerId(0) == pointerId) return true;
        finish(false);
        return false;
    }

    private float[] slotTops() {
        float top = container.getPaddingTop();
        float[] tops = new float[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            View row = rows.get(i);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) row.getLayoutParams();
            top += params.topMargin;
            tops[i] = top;
            top += row.getHeight() + params.bottomMargin;
        }
        return tops;
    }

    private boolean beginGroup() {
        if (isChecked.test(selected)) {
            for (View row : rows) if (isChecked.test(row)) moving.add(row);
        } else {
            moving.add(selected);
        }
        if (moving.size() == rows.size()) return false;
        groupGap = rows.indexOf(moving.get(0));
        groupPreview = IslandRuleFieldDraft.moveGroup(rows, moving, groupGap);
        groupOffset = grabOffset;
        groupHeight = 0f;
        for (View row : moving) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) row.getLayoutParams();
            if (row == selected) groupOffset += groupHeight + params.topMargin;
            groupHeight += params.topMargin + row.getHeight() + params.bottomMargin;
            row.setElevation(activity.dp(6));
            row.setAlpha(0.94f);
        }
        return true;
    }

    private void moveGroupRows(float y) {
        if (!groupMoved && Math.abs(y - downY) <= touchSlop) return;
        groupMoved = true;
        float draggedTop = y - groupOffset;
        float center = draggedTop + groupHeight / 2f;
        float top = container.getPaddingTop();
        int target = 0;
        for (View row : groupPreview) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) row.getLayoutParams();
            top += params.topMargin;
            if (!moving.contains(row) && center > top + row.getHeight() / 2f) target++;
            top += row.getHeight() + params.bottomMargin;
        }
        if (target != groupGap) {
            groupGap = target;
            groupPreview = IslandRuleFieldDraft.moveGroup(rows, moving, groupGap);
        }
        top = container.getPaddingTop();
        float floating = Math.max(top, Math.min(draggedTop, container.getHeight() - groupHeight
                - container.getPaddingBottom()));
        for (View row : groupPreview) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) row.getLayoutParams();
            top += params.topMargin;
            float placement = top;
            if (moving.contains(row)) {
                placement = floating + params.topMargin;
                floating = placement + row.getHeight() + params.bottomMargin;
            }
            row.setTranslationY(placement - row.getTop());
            top += row.getHeight() + params.bottomMargin;
        }
    }

    private void moveRow(float y) {
        if (commitGroup != null) {
            moveGroupRows(y);
            return;
        }
        int slot = rows.indexOf(selected);
        float[] centers = slotTops();
        for (int i = 0; i < centers.length; i++) centers[i] += rows.get(i).getHeight() / 2f;
        int target = FocusIconDragGeometry.targetSlot(y, grabOffset, selected.getHeight(), slot, centers);
        if (slot != target) rows.add(target, rows.remove(slot));
        float top = Math.max(0f, Math.min(y - grabOffset, container.getHeight() - selected.getHeight()));
        float[] tops = slotTops();
        for (int i = 0; i < rows.size(); i++) {
            View row = rows.get(i);
            row.setTranslationY((row == selected ? top : tops[i]) - row.getTop());
        }
    }

    private int edgeStep() {
        if (scroll == null || scroll.getHeight() < activity.dp(72)) return 0;
        scroll.getLocationOnScreen(screenLocation);
        float y = lastRawY - screenLocation[1];
        int edge = activity.dp(36);
        if (y < edge) return -activity.dp(8);
        if (y > scroll.getHeight() - edge) return activity.dp(8);
        return 0;
    }

    private void scheduleEdgeScroll() {
        container.removeCallbacks(autoScroll);
        if (dragging && edgeStep() != 0) container.postOnAnimation(autoScroll);
    }

    private void scrollEdge() {
        if (!dragging || scroll == null) return;
        int step = edgeStep();
        if (step == 0) return;
        int previous = scroll.getScrollY();
        scroll.scrollBy(0, step);
        if (previous == scroll.getScrollY()) return;
        container.getLocationOnScreen(screenLocation);
        moveRow(lastRawY - screenLocation[1]);
        scheduleEdgeScroll();
    }

    void finish(boolean accept) {
        container.removeCallbacks(started);
        container.removeCallbacks(autoScroll);
        if (selected == null) return;
        int target = rows.indexOf(selected);
        boolean changed = accept && dragging && commitGroup == null && target != initialSlot;
        List<Integer> sourceSlots = null;
        if (accept && dragging && groupMoved && !rows.equals(groupPreview)) {
            sourceSlots = new ArrayList<>();
            for (View row : moving) sourceSlots.add(rows.indexOf(row));
        }
        int destinationGap = groupGap;
        if (dragging) {
            for (View row : rows) row.setTranslationY(0f);
            if (commitGroup == null) {
                selected.setElevation(0f);
                selected.setAlpha(1f);
            } else {
                for (View row : moving) {
                    row.setElevation(0f);
                    row.setAlpha(1f);
                }
            }
            ViewParent parent = container.getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        }
        dragging = false;
        selected = null;
        scroll = null;
        pointerId = -1;
        rows.clear();
        moving.clear();
        groupPreview = null;
        groupMoved = false;
        if (changed) commit.accept(initialSlot, target);
        else if (sourceSlots != null) commitGroup.accept(sourceSlots, destinationGap);
    }

    void unbind() {
        finish(false);
        container.setOnTouchListener(null);
        container.removeOnAttachStateChangeListener(this);
    }

    @Override public void onViewAttachedToWindow(View view) { }
    @Override public void onViewDetachedFromWindow(View view) { finish(false); }
}
