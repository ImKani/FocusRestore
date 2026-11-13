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

/** Pure JVM coverage for the material opt-in; no Android reflection or pixel rendering. */
public class NativeFocusAppearanceTest {
    @Test public void advancedMaterialRequiresBothExplicitOptInAndNormalBackground() {
        try {
            NativeFocusAppearance.setAdvancedMaterialEnabled(false);
            NativeFocusAppearance.setUseNormalNotificationBackground(true);
            assertFalse(NativeFocusAppearance.useNativeMaterial());
            NativeFocusAppearance.setAdvancedMaterialEnabled(true);
            assertTrue(NativeFocusAppearance.useNativeMaterial());
            NativeFocusAppearance.setUseNormalNotificationBackground(false);
            assertFalse(NativeFocusAppearance.useNativeMaterial());
            NativeFocusAppearance.setAdvancedMaterialEnabled(false);
            assertFalse(NativeFocusAppearance.useNativeMaterial());
        } finally {
            NativeFocusAppearance.setAdvancedMaterialEnabled(false);
            NativeFocusAppearance.setUseNormalNotificationBackground(false);
        }
    }
}
