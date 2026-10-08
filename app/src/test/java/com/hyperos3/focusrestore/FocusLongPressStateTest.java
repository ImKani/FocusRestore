/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.*;

public class FocusLongPressStateTest {
    @Test public void firesOnlyOnceAndConsumesRelease() {
        FocusLongPressState state = new FocusLongPressState();
        state.begin(10f, 20f);
        assertTrue(state.fire());
        assertFalse(state.fire());
        assertTrue(state.consumed());
        state.cancelPending();
        assertTrue(state.consumed());
    }

    @Test public void movementBeyondSlopCancelsWithoutConsumingShortGesture() {
        FocusLongPressState state = new FocusLongPressState();
        state.begin(10f, 20f);
        state.move(18f, 28f, 8);
        assertTrue(state.pending());
        state.move(18.1f, 20f, 8);
        assertFalse(state.fire());
        assertFalse(state.consumed());
    }

    @Test public void cancellationPreventsDelayedAction() {
        FocusLongPressState state = new FocusLongPressState();
        state.begin(0f, 0f);
        state.cancelPending();
        assertFalse(state.fire());
        state.reset();
        assertFalse(state.pending());
        assertFalse(state.consumed());
    }

    @Test public void newGestureDoesNotInheritConsumedState() {
        FocusLongPressState state = new FocusLongPressState();
        state.begin(0f, 0f);
        state.fire();
        state.begin(20f, 30f);
        assertFalse(state.consumed());
        assertTrue(state.pending());
        assertTrue(state.fire());
    }
}
