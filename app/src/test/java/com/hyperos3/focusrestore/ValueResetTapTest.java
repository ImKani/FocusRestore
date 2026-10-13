/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ValueResetTapTest {
    @Test public void singleTapDoesNotResetAndSameTargetDoubleTapDoes() {
        ValueResetTap taps = new ValueResetTap();
        assertFalse(taps.click(1, 1000L, 300));
        assertTrue(taps.click(1, 1299L, 300));
    }

    @Test public void alternatingValuesNeverCombineIntoDoubleTap() {
        ValueResetTap taps = new ValueResetTap();
        assertFalse(taps.click(1, 1000L, 300));
        assertFalse(taps.click(2, 1100L, 300));
        assertFalse(taps.click(1, 1200L, 300));
        assertTrue(taps.click(1, 1300L, 300));
    }

    @Test public void expiredTapStartsNewPairAndBoundaryRemainsValid() {
        ValueResetTap taps = new ValueResetTap();
        assertFalse(taps.click(1, 1000L, 300));
        assertFalse(taps.click(1, 1301L, 300));
        assertTrue(taps.click(1, 1601L, 300));
    }

    @Test public void tripleTapResetsOnlyOnceAndFourthStartsAnotherPair() {
        ValueResetTap taps = new ValueResetTap();
        assertFalse(taps.click(1, 1000L, 300));
        assertTrue(taps.click(1, 1100L, 300));
        assertFalse(taps.click(1, 1200L, 300));
        assertTrue(taps.click(1, 1300L, 300));
    }

    @Test public void pageOrPauseCleanupCancelsIncompletePair() {
        ValueResetTap taps = new ValueResetTap();
        assertFalse(taps.click(1, 1000L, 300));
        taps.clear();
        assertFalse(taps.click(1, 1100L, 300));
    }

    @Test public void backwardsClockAndInvalidWindowDoNotReset() {
        ValueResetTap taps = new ValueResetTap();
        assertFalse(taps.click(1, 1000L, 300));
        assertFalse(taps.click(1, 999L, 300));
        assertFalse(taps.click(1, 1000L, 0));
    }
}
