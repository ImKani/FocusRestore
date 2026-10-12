/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class FocusTouchWidthTest {
    @Test public void customWidthTracksOrientationAndDisabledKeepsNaturalBounds() {
        assertEquals(240, FocusTouchWidth.dp(true, false, 240, 800));
        assertEquals(800, FocusTouchWidth.dp(true, true, 240, 800));
        assertEquals(0, FocusTouchWidth.dp(false, false, 240, 800));
        assertEquals(0, FocusTouchWidth.dp(false, true, 240, 800));
        assertEquals(124, FocusTouchWidth.measured(124, 0, 440, 700));
    }

    @Test public void smallContentGetsFullTouchWidthWithoutBreakingParentLimit() {
        assertEquals(440, FocusTouchWidth.measured(124, 440, 440, 700));
        assertEquals(300, FocusTouchWidth.measured(124, 440, 440, 300));
    }

    @Test public void systemWidthKeepsNaturallyWiderContent() {
        assertEquals(620, FocusTouchWidth.measured(620, 440, Integer.MAX_VALUE, 700));
        assertEquals(700, FocusTouchWidth.measured(800, 440, Integer.MAX_VALUE, 700));
    }

    @Test public void customTouchCanExceedDisplayLimitWithoutOverflow() {
        assertEquals(440, FocusTouchWidth.measured(900, 0, 440, 700));
        assertEquals(620, FocusTouchWidth.measured(900, 620, 440, 700));
        assertEquals(700, FocusTouchWidth.measured(900, 800, 440, 700));
        assertEquals(1, FocusTouchWidth.measured(124, 440, 440, 0));
    }

    @Test public void disabledRecoveryPreservesPreviousBoundsAnd96DpFallback() {
        assertEquals(45, FocusTouchWidth.recovery(0, 45, 96));
        assertEquals(96, FocusTouchWidth.recovery(0, 0, 96));
        assertEquals(240, FocusTouchWidth.recovery(240, 45, 96));
        assertEquals(800, FocusTouchWidth.recovery(800, 0, 96));
    }
}
