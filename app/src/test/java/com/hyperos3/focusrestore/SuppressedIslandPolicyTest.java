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

/** 纯 JVM 范围/插件生命周期回归，不替代 Android 广播与小爱窗口退出验收。 */
public class SuppressedIslandPolicyTest {
    @Test public void unblockedDebugConfigurationNeverCompletesAnActiveNativeIsland() {
        assertTrue(SuppressedIslandPolicy.active(true, true, true, true, true, 1));
        assertFalse(SuppressedIslandPolicy.active(true, true, false, true, true, 1));
        assertFalse(SuppressedIslandPolicy.active(true, true, true, false, true, 1));
        assertFalse(SuppressedIslandPolicy.active(true, true, false, false, true, 1));
    }

    @Test public void unloadAndFailedReinstallDisableCompensationUntilTheAddHookReturns() {
        assertTrue(SuppressedIslandPolicy.active(true, true, true, true, true, 1));
        assertFalse(SuppressedIslandPolicy.active(true, true, true, true, false, 0));
        assertFalse(SuppressedIslandPolicy.active(true, true, true, true, true, 0));
        assertTrue(SuppressedIslandPolicy.active(true, true, true, true, true, 2));
    }

    @Test public void releaseUsesEnforcedSuppressionRatherThanDebugPreferences() {
        assertTrue(SuppressedIslandPolicy.active(true, false, false, false, true, 1));
        assertFalse(SuppressedIslandPolicy.active(true, false, true, true, false, 1));
        assertFalse(SuppressedIslandPolicy.active(true, false, true, true, true, 0));
    }

    @Test public void os3KeepsItsNativeProtocol() {
        assertFalse(SuppressedIslandPolicy.active(false, true, true, true, true, 1));
        assertFalse(SuppressedIslandPolicy.active(false, false, true, true, true, 1));
    }

    @Test public void unrelatedProviderCallsAndPackagesKeepTheirOriginalResults() {
        assertTrue(SuppressedIslandPolicy.overridesQuery(true, "getIslandExpanded", "com.miui.voiceassist"));
        assertFalse(SuppressedIslandPolicy.overridesQuery(true, "setIslandExpanded", "com.miui.voiceassist"));
        assertFalse(SuppressedIslandPolicy.overridesQuery(true, "getIslandExpanded", "com.android.settings"));
        assertFalse(SuppressedIslandPolicy.overridesQuery(true, null, "com.miui.voiceassist"));
        assertFalse(SuppressedIslandPolicy.overridesQuery(true, "getIslandExpanded", null));
    }

    @Test public void disabledSuppressionDoesNotChangeEvenXiaoAiExpandedQueries() {
        assertFalse(SuppressedIslandPolicy.overridesQuery(false, "getIslandExpanded", "com.miui.voiceassist"));
    }
}
