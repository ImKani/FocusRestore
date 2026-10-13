/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/**
 * 媒体焦点展示时机：首次/切换仅播放中加入，同 key 暂停保留，原生 top 为空则清除。
 * 接口事实来源：用户本地 MT MCP 的 OS4 SystemUI 17.03.260226.r / 202602260，
 * MiuiIslandMediaControllerImpl.addDynamicIslandView 与 mediaDataChangeListener。
 * 上述 ROM 接口的具体版权所有者及私有协议许可未确认。
 */
final class MediaFocusLifecycleState {
    private String selectedKey;

    synchronized void onTopChanged(String key, boolean playing) {
        if (key == null || key.isEmpty()) {
            selectedKey = null;
        } else if (!key.equals(selectedKey)) {
            // 切换到暂停的新目标时不展示；不能沿用旧媒体焦点。
            selectedKey = playing ? key : null;
        }
    }

    synchronized void dismissPackage(String packageName) {
        if (packageName == null || packageName.isEmpty() || selectedKey == null) return;
        int firstSeparator = selectedKey.indexOf('|');
        int secondSeparator = firstSeparator < 0 ? -1 : selectedKey.indexOf('|', firstSeparator + 1);
        if (secondSeparator > firstSeparator + 1
                && packageName.equals(selectedKey.substring(firstSeparator + 1, secondSeparator))) {
            selectedKey = null;
        }
    }

    synchronized void onRemoved(String key) {
        if (key != null && key.equals(selectedKey)) selectedKey = null;
    }

    synchronized boolean isEligible(String key) {
        return selectedKey != null && selectedKey.equals(key);
    }

    synchronized String selectedKey() {
        return selectedKey;
    }
}
