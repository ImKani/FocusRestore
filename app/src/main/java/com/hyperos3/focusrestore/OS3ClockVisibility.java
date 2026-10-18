/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 每个 OS3 状态栏独立持有的隐藏覆盖层；不保存可能已被模块隐藏的 View 快照。 */
final class OS3ClockVisibility {
    interface Host {
        boolean nativeClockShown();
        void hideForFocus();
        void showNativeClock();
        void hideNativeClock();
        void reconcileClockVisibility();
    }

    private boolean overridden;

    boolean update(boolean hide, Host host) {
        if (hide) {
            boolean changed = !overridden;
            // 先记下接管，部分视图失败后仍能在下次回调撤销覆盖。
            overridden = true;
            host.hideForFocus();
            return changed;
        } else if (overridden) {
            // 焦点退出或换类型后，恢复当前 ROM 决策，不能回放锁屏前的旧快照。
            if (host.nativeClockShown()) host.showNativeClock();
            else host.hideNativeClock();
            // ROM setPolicyVisibility 同值会早退，必须重新合成 policy/multitask 可见性。
            host.reconcileClockVisibility();
            overridden = false;
            return true;
        }
        return false;
    }
}
