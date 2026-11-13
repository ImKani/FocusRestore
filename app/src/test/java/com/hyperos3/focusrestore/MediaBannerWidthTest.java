/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class MediaBannerWidthTest {
    @Test public void landscapeKeepsNotificationPanelWidth() {
        assertEquals(984, MediaBannerWidth.resolve(2400, 1080, 1080, 48));
        assertEquals(984, MediaBannerWidth.resolve(1080, 2400, 1080, 48));
    }
    @Test public void missingOrFullWidthResourceUsesShortEdge() {
        assertEquals(1080, MediaBannerWidth.resolve(2400, 1080, 0, 0));
        assertEquals(984, MediaBannerWidth.resolve(2400, 1080, 2400, 48));
    }
    @Test public void smallPanelIsPreserved() {
        assertEquals(632, MediaBannerWidth.resolve(2560, 1600, 680, 24));
    }
    @Test public void invalidGeometryStaysPositiveWithoutOverflow() {
        assertEquals(1, MediaBannerWidth.resolve(0, 0, 0, Integer.MAX_VALUE));
        assertEquals(720, MediaBannerWidth.resolve(720, 0, 0, -10));
    }
}
