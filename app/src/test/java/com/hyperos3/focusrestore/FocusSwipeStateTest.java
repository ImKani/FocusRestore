/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.*;

public class FocusSwipeStateTest {
    @Test public void shortHorizontalDragIsConsumedWithoutSwitching() {
        FocusSwipeState state = new FocusSwipeState();
        state.begin(100, 10);
        assertTrue(state.move(120, 12, 8));
        assertEquals(0, state.finish(140, 12, 8, 50));
    }

    @Test public void thresholdIsStrictAndBothDirectionsWork() {
        FocusSwipeState state = new FocusSwipeState();
        for (int displacement : new int[]{-51, -50, 50, 51}) {
            state.begin(100, 10);
            assertEquals(Math.abs(displacement) > 50 ? Integer.signum(displacement) : 0,
                    state.finish(100 + displacement, 10, 8, 50));
        }
    }

    @Test public void downwardGestureKeepsDirectionWhenItLaterMovesSideways() {
        FocusSwipeState state = new FocusSwipeState();
        state.begin(100, 10);
        assertFalse(state.move(104, 25, 8));
        assertFalse(state.move(180, 25, 8));
        assertEquals(0, state.finish(180, 25, 8, 50));
    }

    @Test public void cancelAfterHorizontalLockCannotSwitchOrBecomeTap() {
        FocusSwipeState state = new FocusSwipeState();
        state.begin(100, 10);
        state.move(120, 10, 8);
        state.cancel();
        assertTrue(state.horizontal());
        assertFalse(state.tracking());
        assertEquals(0, state.finish(180, 10, 8, 50));
    }

    @Test public void returningBelowThresholdDoesNotCommit() {
        FocusSwipeState state = new FocusSwipeState();
        state.begin(100, 10);
        state.move(180, 10, 8);
        assertEquals(0, state.finish(105, 10, 8, 50));
        assertFalse(state.horizontal());
    }
}
