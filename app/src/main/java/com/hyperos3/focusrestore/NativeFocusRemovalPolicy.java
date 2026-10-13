/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 删除屏障使用被删除通知的代次，不能使用监听回调到达的墙钟时间。 */
final class NativeFocusRemovalPolicy {
    static boolean rejects(Long removedPostTime, long candidatePostTime) {
        return removedPostTime != null && candidatePostTime <= removedPostTime;
    }

    static long latest(Long previous, long removedPostTime) {
        return previous == null ? removedPostTime : Math.max(previous, removedPostTime);
    }

    private NativeFocusRemovalPolicy() { }
}
