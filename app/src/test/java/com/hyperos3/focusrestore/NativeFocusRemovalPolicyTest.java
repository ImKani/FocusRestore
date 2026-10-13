/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeFocusRemovalPolicyTest {
    @Test public void os3RepostBeforeDelayedRemovalRemainsClickable() {
        long newPost = 1791858225124L;
        long callbackTime = 1791858225135L;
        long removedPost = 1791858221757L;
        assertTrue("旧墙钟判定会误杀新通知", newPost <= callbackTime);
        assertFalse(NativeFocusRemovalPolicy.rejects(removedPost, newPost));
        assertTrue(NativeFocusRemovalPolicy.rejects(removedPost, removedPost));
        assertTrue(NativeFocusRemovalPolicy.rejects(removedPost, removedPost - 1));
    }

    @Test public void os4RepostBeforeDelayedRemovalRemainsClickable() {
        long newPost = 1791857727937L;
        long callbackTime = 1791857727962L;
        // 日志未打印旧通知 postTime；用明确更早的旧代次验证相同竞态条件。
        long removedPost = newPost - 1;
        assertTrue(newPost <= callbackTime);
        assertFalse(NativeFocusRemovalPolicy.rejects(removedPost, newPost));
    }

    @Test public void repeatedOutOfOrderRemovalsCannotResurrectOlderResults() {
        Long barrier = NativeFocusRemovalPolicy.latest(null, 200L);
        barrier = NativeFocusRemovalPolicy.latest(barrier, 100L);
        assertEquals(Long.valueOf(200L), barrier);
        assertFalse(NativeFocusRemovalPolicy.rejects(barrier, 300L));
        // 新代次就绪后仍保留屏障，旧协程结束不能覆盖较新来源。
        assertTrue(NativeFocusRemovalPolicy.rejects(barrier, 100L));
        assertTrue(NativeFocusRemovalPolicy.rejects(barrier, 200L));
        barrier = NativeFocusRemovalPolicy.latest(barrier, 300L);
        assertTrue(NativeFocusRemovalPolicy.rejects(barrier, 300L));
        assertFalse(NativeFocusRemovalPolicy.rejects(barrier, 301L));
    }

    @Test public void unremovedSourceHasNoBarrier() {
        assertFalse(NativeFocusRemovalPolicy.rejects(null, 0L));
    }
}
