/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 单槽 Focus 的隐藏队列；null 是原生 Empty 的收起位置，不是已删除通知。
 * 接口事实来源：小米 miui.systemui.plugin 17.1.4.71.0 / 18.2.2.2.0，
 * SwipeEventCoordinator.handleAppEvent、Hidden/BigIslandStateHandler，用户只读 APK MCP
 * http://192.168.31.21:8787/mcp；私有协议许可未确认。独立实现队列，不复制 ROM 实现。
 */
final class FocusSwipeSelection {
    private final List<String> keys = new ArrayList<>();
    private final List<String> hidden = new ArrayList<>();
    private String selected;
    private boolean manual;
    private long revision;

    synchronized String reconcile(List<String> eligible, String automatic) {
        boolean added = false;
        for (String key : eligible) if (!keys.contains(key)) added = true;
        boolean removed = keys.removeIf(key -> !eligible.contains(key));
        for (String key : eligible) if (!keys.contains(key)) keys.add(key);
        hidden.removeIf(key -> key != null && !keys.contains(key));
        if (added || (selected != null && !keys.contains(selected)) || keys.isEmpty()) {
            manual = false;
        }
        // 普通更新不打断手动切换或收起；真正的新候选仍按既有优先级显示。
        if (!manual) {
            if (!Objects.equals(selected, automatic) || added || removed) revision++;
            selected = automatic;
            hidden.clear();
            for (String key : eligible) if (!Objects.equals(key, selected)) hidden.add(key);
            if (selected != null) hidden.add(null);
        } else if (removed) {
            revision++;
        }
        return selected;
    }

    synchronized boolean swipe(boolean towardsEnd, long expectedRevision) {
        if (revision != expectedRevision || keys.isEmpty() || hidden.isEmpty()) return false;
        // 左滑从队首取下一项，当前入队尾；右滑反向，Empty 同样参与循环。
        if (towardsEnd) {
            hidden.add(0, selected);
            selected = hidden.remove(hidden.size() - 1);
        } else {
            hidden.add(selected);
            selected = hidden.remove(0);
        }
        manual = true;
        revision++;
        return true;
    }

    synchronized void remove(String key) {
        if (!keys.remove(key)) return;
        hidden.remove(key);
        if (Objects.equals(selected, key)) manual = false;
        if (keys.isEmpty()) clear();
        else revision++;
    }

    synchronized void clear() {
        keys.clear();
        hidden.clear();
        selected = null;
        manual = false;
        revision++;
    }

    synchronized long revision() { return revision; }
    synchronized boolean manual() { return manual; }
    synchronized boolean hasCandidates() { return !keys.isEmpty(); }
    synchronized String selected() { return selected; }
    synchronized boolean allows(String key) { return !manual || Objects.equals(selected, key); }
}
