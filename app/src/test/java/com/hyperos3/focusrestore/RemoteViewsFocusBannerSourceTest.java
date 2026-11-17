/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.*;

/** 只验证自带模板的选择/缺项回退；不运行Android RemoteViews或证明文字像素。 */
public class RemoteViewsFocusBannerSourceTest {
    @Test public void systemDarkSelectsSuppliedNightVariant() {
        assertTrue(RemoteViewsFocusBannerSource.chooseDarkVariant(true, true, true));
    }

    @Test public void systemLightSelectsSuppliedDayVariant() {
        assertFalse(RemoteViewsFocusBannerSource.chooseDarkVariant(false, true, true));
    }

    @Test public void missingNightFallsBackToDayEvenWhenDarkRequested() {
        assertFalse(RemoteViewsFocusBannerSource.chooseDarkVariant(true, true, false));
    }

    @Test public void nightOnlyNotificationKeepsExistingFallback() {
        assertTrue(RemoteViewsFocusBannerSource.chooseDarkVariant(false, false, true));
        assertTrue(RemoteViewsFocusBannerSource.chooseDarkVariant(true, false, true));
    }

    @Test public void noVariantsDoesNotInventNightContent() {
        assertFalse(RemoteViewsFocusBannerSource.chooseDarkVariant(false, false, false));
        assertFalse(RemoteViewsFocusBannerSource.chooseDarkVariant(true, false, false));
    }
}
