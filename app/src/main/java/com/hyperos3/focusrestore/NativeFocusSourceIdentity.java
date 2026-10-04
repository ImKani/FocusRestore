/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

/** Immutable identity of a template captured from the plugin's real notification pipeline. */
final class NativeFocusSourceIdentity {
    private final String key;
    private final String packageName;
    private final int uid;
    private final int userId;
    private final long postTime;
    private final String focusParam;

    NativeFocusSourceIdentity(String key, String packageName, int uid, int userId,
                              long postTime, String focusParam) {
        this.key = key;
        this.packageName = packageName;
        this.uid = uid;
        this.userId = userId;
        this.postTime = postTime;
        this.focusParam = focusParam;
    }

    /** Does not infer freshness from a shared key, Notification.when, or object identity alone. */
    boolean matches(NativeFocusSourceIdentity expected) {
        return expected != null && nonempty(key) && nonempty(packageName) && nonempty(focusParam)
                && key.equals(expected.key) && packageName.equals(expected.packageName)
                && uid == expected.uid && userId == expected.userId
                && postTime == expected.postTime && focusParam.equals(expected.focusParam);
    }

    private static boolean nonempty(String value) {
        return value != null && !value.isEmpty();
    }
}