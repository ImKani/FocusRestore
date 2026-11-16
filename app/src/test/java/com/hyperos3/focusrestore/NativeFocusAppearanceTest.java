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
    @Test public void hunBlendRequiresInstalledOs4GlassAndBothMaterialGates() {
        try {
            for (int mode : new int[]{0, FocusRestoreSettings.HOOK_MODE_OS3, FocusRestoreSettings.HOOK_MODE_OS4}) {
                NativeFocusAppearance.setInstalledHookMode(mode);
                for (boolean advanced : new boolean[]{false, true}) {
                    NativeFocusAppearance.setAdvancedMaterialEnabled(advanced);
                    for (boolean normal : new boolean[]{false, true}) {
                        NativeFocusAppearance.setUseNormalNotificationBackground(normal);
                        for (boolean glass : new boolean[]{false, true}) {
                            org.junit.Assert.assertEquals(mode == FocusRestoreSettings.HOOK_MODE_OS4
                                            && advanced && normal && glass,
                                    NativeFocusAppearance.useOs4GlassHunBlend(glass));
                        }
                    }
                }
            }
        } finally {
            NativeFocusAppearance.setInstalledHookMode(0);
            NativeFocusAppearance.setAdvancedMaterialEnabled(false);
            NativeFocusAppearance.setUseNormalNotificationBackground(false);
        }
    }

    @Test public void templateUsesSystemThemeExceptForEffectiveOs4Glass() {
        try {
            for (int mode : new int[]{0, FocusRestoreSettings.HOOK_MODE_OS3, FocusRestoreSettings.HOOK_MODE_OS4}) {
                NativeFocusAppearance.setInstalledHookMode(mode);
                for (boolean advanced : new boolean[]{false, true}) {
                    NativeFocusAppearance.setAdvancedMaterialEnabled(advanced);
                    for (boolean normal : new boolean[]{false, true}) {
                        NativeFocusAppearance.setUseNormalNotificationBackground(normal);
                        for (boolean systemDark : new boolean[]{false, true}) {
                            for (boolean glass : new boolean[]{false, true}) {
                                for (boolean failed : new boolean[]{false, true}) {
                                    boolean expected = systemDark || (mode == FocusRestoreSettings.HOOK_MODE_OS4
                                            && advanced && normal && glass && !failed);
                                    org.junit.Assert.assertEquals(expected,
                                            NativeFocusAppearance.useDarkTemplate(systemDark, glass, failed));
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            NativeFocusAppearance.setInstalledHookMode(0);
            NativeFocusAppearance.setAdvancedMaterialEnabled(false);
            NativeFocusAppearance.setUseNormalNotificationBackground(false);
        }
    }

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
