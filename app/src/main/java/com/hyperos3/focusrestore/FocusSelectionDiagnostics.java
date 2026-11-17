/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.LinkedHashMap;
import java.util.Map;

/** 只记录选择事实；不改变通知资格，也不持有 Entry 或 View。 */
final class FocusSelectionDiagnostics {
    private final Map<String, String> decisions = new LinkedHashMap<>();

    synchronized String changed(String key, long postTime, long remaining,
                                boolean nativeAllowed, String entryBlocked) {
        // 正数倒计时不是状态变化；新通知代次必须重新报告，不能被旧 key 的拒绝原因吞掉。
        String signature = postTime + "|" + (remaining == 0 ? "expired"
                : remaining == Long.MAX_VALUE ? "unlimited" : "remaining")
                + "|" + nativeAllowed + "|" + entryBlocked;
        String previous = decisions.put(key, signature);
        if (decisions.size() > 128) decisions.remove(decisions.keySet().iterator().next());
        if (signature.equals(previous)) return null;
        return "OS4 candidate decision key=" + key + " postTime=" + postTime
                + " remainingMs=" + remaining + " nativeChipAllowed=" + nativeAllowed
                + " entryBlocked=" + (entryBlocked == null ? "allowed" : entryBlocked);
    }

    synchronized void removed(String key) { decisions.remove(key); }
    synchronized void clear() { decisions.clear(); }
}
