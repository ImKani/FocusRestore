/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;
import org.junit.Test;
import static org.junit.Assert.*;
public class FocusAdaptiveWidthTest {
    @Test public void constrainedSpaceShrinksAndRecoversWithoutChangingDefault() {
        assertFalse(FocusAdaptiveWidth.iconOnly(false,true,20,30,40));
        assertTrue(FocusAdaptiveWidth.iconOnly(true,true,60,30,40));
        assertFalse(FocusAdaptiveWidth.iconOnly(true,true,100,30,40));
        assertTrue(FocusAdaptiveWidth.iconOnly(true,true,100,30,90));
    }
    @Test public void cannotInventIconOrTreatUnboundedParentAsShortage() {
        assertFalse(FocusAdaptiveWidth.iconOnly(true,false,20,30,40));
        assertFalse(FocusAdaptiveWidth.iconOnly(true,true,Integer.MAX_VALUE,30,40));
    }
}
