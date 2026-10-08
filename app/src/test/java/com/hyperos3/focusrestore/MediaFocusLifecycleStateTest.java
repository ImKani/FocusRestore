/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 原生媒体 top 时机的独立模型测试；不依赖 Android 或 ROM 私有类。 */
public class MediaFocusLifecycleStateTest {
    private static final String FIRST = "0|com.example.first|1|null|10001";
    private static final String SECOND = "0|com.example.second|2|null|10002";

    @Test public void initiallyPausedDoesNotDisplay() {
        MediaFocusLifecycleState state = new MediaFocusLifecycleState();
        state.onTopChanged(FIRST, false);
        assertFalse(state.isEligible(FIRST));
        assertNull(state.selectedKey());
    }

    @Test public void playingStartsAndSameKeyPauseKeepsFocus() {
        MediaFocusLifecycleState state = new MediaFocusLifecycleState();
        state.onTopChanged(FIRST, false);
        state.onTopChanged(FIRST, true);
        assertTrue(state.isEligible(FIRST));
        state.onTopChanged(FIRST, false);
        assertTrue(state.isEligible(FIRST));
        assertEquals(FIRST, state.selectedKey());
    }

    @Test public void differentPausedTopRemovesPreviousFocus() {
        MediaFocusLifecycleState state = new MediaFocusLifecycleState();
        state.onTopChanged(FIRST, true);
        state.onTopChanged(SECOND, false);
        assertFalse(state.isEligible(FIRST));
        assertFalse(state.isEligible(SECOND));
        assertNull(state.selectedKey());
    }

    @Test public void differentPlayingTopReplacesPreviousFocus() {
        MediaFocusLifecycleState state = new MediaFocusLifecycleState();
        state.onTopChanged(FIRST, true);
        state.onTopChanged(SECOND, true);
        assertFalse(state.isEligible(FIRST));
        assertTrue(state.isEligible(SECOND));
        assertEquals(SECOND, state.selectedKey());
    }

    @Test public void nullAndEmptyTopClearFocus() {
        MediaFocusLifecycleState state = new MediaFocusLifecycleState();
        state.onTopChanged(FIRST, true);
        state.onTopChanged(null, true);
        assertNull(state.selectedKey());
        assertFalse(state.isEligible(null));
        state.onTopChanged(FIRST, true);
        state.onTopChanged("", true);
        assertNull(state.selectedKey());
    }

    @Test public void removalOnlyClearsMatchingKey() {
        MediaFocusLifecycleState state = new MediaFocusLifecycleState();
        state.onTopChanged(FIRST, true);
        state.onRemoved(SECOND);
        state.onRemoved(null);
        assertTrue(state.isEligible(FIRST));
        state.onRemoved(FIRST);
        assertNull(state.selectedKey());
        state.onTopChanged(FIRST, false);
        assertFalse(state.isEligible(FIRST));
        state.onTopChanged(FIRST, true);
        assertTrue(state.isEligible(FIRST));
    }

    @Test public void packageDismissClearsOnlyMatchingPackage() {
        MediaFocusLifecycleState state = new MediaFocusLifecycleState();
        state.onTopChanged(FIRST, true);
        state.dismissPackage(null);
        state.dismissPackage("");
        state.dismissPackage("com.example.second");
        assertTrue(state.isEligible(FIRST));
        state.dismissPackage("com.example.first");
        assertNull(state.selectedKey());
        // 暂停态旧 top 回调不复活，真正重新播放的 top 才重新显示。
        state.onTopChanged(FIRST, false);
        assertFalse(state.isEligible(FIRST));
        state.onTopChanged(FIRST, true);
        assertTrue(state.isEligible(FIRST));
    }

    @Test public void malformedKeyCannotDismissUnrelatedPackage() {
        MediaFocusLifecycleState state = new MediaFocusLifecycleState();
        state.onTopChanged("malformed", true);
        state.dismissPackage("malformed");
        assertTrue(state.isEligible("malformed"));
    }
}
