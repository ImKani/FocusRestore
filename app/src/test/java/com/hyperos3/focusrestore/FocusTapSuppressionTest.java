package com.hyperos3.focusrestore;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FocusTapSuppressionTest {
    private final FocusTapSuppression taps = new FocusTapSuppression();

    @Test public void delayedConfirmationRetainsFocusHitAfterFocusDisappears() {
        taps.record(100L, 1, 0, true, 100L);
        assertTrue(taps.blocks(100L, 1, 0, 450L));
    }

    @Test public void immediateOutsideTapRemainsAllowed() {
        taps.record(100L, 1, 0, true, 100L);
        taps.record(200L, 1, 0, false, 200L);
        assertFalse(taps.blocks(200L, 1, 0, 230L));
        assertTrue(taps.blocks(100L, 1, 0, 450L));
    }

    @Test public void duplicateFeedCannotRewriteTheOriginalHitOrMiss() {
        taps.record(100L, 1, 0, true, 100L);
        taps.record(100L, 1, 0, false, 101L);
        assertTrue(taps.blocks(100L, 1, 0, 150L));
        taps.record(200L, 1, 0, false, 200L);
        taps.record(200L, 1, 0, true, 201L);
        assertFalse(taps.blocks(200L, 1, 0, 250L));
    }

    @Test public void secondTapUsesItsOwnRegion() {
        taps.record(100L, 1, 0, false, 100L);
        taps.record(230L, 1, 0, true, 230L);
        assertTrue(taps.blocks(230L, 1, 0, 240L));
        assertFalse(taps.blocks(100L, 1, 0, 240L));
    }

    @Test public void devicesAndDisplaysDoNotShareSuppression() {
        taps.record(100L, 1, 0, true, 100L);
        assertFalse(taps.blocks(100L, 2, 0, 120L));
        assertFalse(taps.blocks(100L, 1, 1, 120L));
        assertFalse(taps.blocks(101L, 1, 0, 120L));
    }

    @Test public void missingAndExpiredGesturesAreAllowed() {
        assertFalse(taps.blocks(100L, 1, 0, 100L));
        taps.record(100L, 1, 0, true, 100L);
        assertFalse(taps.blocks(100L, 1, 0, 2101L));
        assertFalse(taps.hasSnapshot(100L, 1, 0, 2101L));
    }

    @Test public void recordCountIsBoundedWithoutEvictingRecentGesture() {
        for (long time = 100; time < 109; time++) taps.record(time, 1, 0, true, time);
        assertFalse(taps.blocks(100L, 1, 0, 120L));
        assertTrue(taps.blocks(108L, 1, 0, 120L));
    }

    @Test public void backwardsClockDiscardsStaleSnapshots() {
        taps.record(100L, 1, 0, true, 100L);
        assertFalse(taps.blocks(100L, 1, 0, 90L));
    }
}
