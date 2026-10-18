/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FocusPriorityPolicyTest {
    @Test
    public void islandTickerAloneDoesNotBlockConversion() {
        assertFalse(FocusPriorityPolicy.isOriginalFocus(
                false, false, false, false, true, true));
    }

    @Test
    public void standaloneTickerIsExistingFocusContent() {
        assertTrue(FocusPriorityPolicy.isOriginalFocus(
                false, false, false, false, true, false));
    }

    @Test
    public void explicitFocusAlwaysHasPriority() {
        assertTrue(FocusPriorityPolicy.isOriginalFocus(
                false, true, false, false, true, true));
    }

    @Test
    public void focusRemoteViewsAlwaysHavePriority() {
        assertTrue(FocusPriorityPolicy.isOriginalFocus(
                false, false, true, false, true, true));
        assertTrue(FocusPriorityPolicy.isOriginalFocus(
                false, false, false, true, true, true));
    }

    @Test public void explicitClockFocusWithoutBarContentCanConvertWithoutWhitelist() {
        boolean fallback = FocusPriorityPolicy.needsClockTimerFallback(true, false, false);
        assertTrue(fallback);
        assertTrue(FocusPriorityPolicy.shouldConvertIsland(true, false, false, fallback));
    }

    @Test public void nativeCountdownBarOrTickerKeepsPriorityUnlessWhitelisted() {
        assertFalse(FocusPriorityPolicy.needsClockTimerFallback(true, true, false));
        assertFalse(FocusPriorityPolicy.needsClockTimerFallback(true, false, true));
        assertFalse(FocusPriorityPolicy.shouldConvertIsland(true, false, false, false));
        assertTrue(FocusPriorityPolicy.shouldConvertIsland(true, true, false, false));
    }

    @Test public void unrelatedNativeFocusRetainsItsExistingPriority() {
        assertFalse(FocusPriorityPolicy.needsClockTimerFallback(false, false, false));
        assertFalse(FocusPriorityPolicy.shouldConvertIsland(true, false, false, false));
        assertTrue(FocusPriorityPolicy.shouldConvertIsland(false, false, false, false));
        assertTrue(FocusPriorityPolicy.shouldConvertIsland(true, false, true, false));
    }
}
