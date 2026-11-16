/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** 纯Java首帧等待状态测试，不模拟Android遍历或验证GPU材质可见性。 */
public class BannerMaterialStartTest {
    @Test public void unrequestedMaterialAllowsTheFirstDrawImmediately() {
        BannerMaterialStart start = new BannerMaterialStart();

        assertTrue(start.allowDraw(false, false, -1, 0, 0, 1000L));
    }

    @Test public void readyMaterialDefersOnceThenAllowsAnUnchangedTraversal() {
        BannerMaterialStart start = new BannerMaterialStart();

        assertFalse(start.allowDraw(true, true, 1, 100, 60, 1000L));
        assertTrue(start.allowDraw(true, true, 1, 100, 60, 1016L));
    }

    @Test public void revisionChangeRequiresAnotherStableTraversal() {
        BannerMaterialStart start = new BannerMaterialStart();

        assertFalse(start.allowDraw(true, true, 1, 100, 60, 1000L));
        assertFalse(start.allowDraw(true, true, 2, 100, 60, 1016L));
        assertTrue(start.allowDraw(true, true, 2, 100, 60, 1032L));
    }

    @Test public void widthChangeRequiresAnotherStableTraversal() {
        BannerMaterialStart start = new BannerMaterialStart();

        assertFalse(start.allowDraw(true, true, 1, 100, 60, 1000L));
        assertFalse(start.allowDraw(true, true, 1, 120, 60, 1016L));
        assertTrue(start.allowDraw(true, true, 1, 120, 60, 1032L));
    }

    @Test public void heightChangeRequiresAnotherStableTraversal() {
        BannerMaterialStart start = new BannerMaterialStart();

        assertFalse(start.allowDraw(true, true, 1, 100, 60, 1000L));
        assertFalse(start.allowDraw(true, true, 1, 100, 80, 1016L));
        assertTrue(start.allowDraw(true, true, 1, 100, 80, 1032L));
    }

    @Test public void zeroDimensionsCannotCountAsStableReadyMaterial() {
        for (int[] bounds : new int[][]{{0, 60}, {100, 0}, {0, 0}}) {
            BannerMaterialStart start = new BannerMaterialStart();

            assertFalse(start.allowDraw(true, true, 1, bounds[0], bounds[1], 1000L));
            assertFalse(start.allowDraw(true, true, 1, bounds[0], bounds[1], 1016L));
            assertFalse(start.allowDraw(true, true, 1, 100, 60, 1032L));
            assertTrue(start.allowDraw(true, true, 1, 100, 60, 1048L));
        }
    }

    @Test public void becomingReadyDoesNotReuseAPendingTraversalForStability() {
        BannerMaterialStart start = new BannerMaterialStart();

        assertFalse(start.allowDraw(true, false, 1, 100, 60, 1000L));
        assertFalse(start.allowDraw(true, true, 1, 100, 60, 1016L));
        assertTrue(start.allowDraw(true, true, 1, 100, 60, 1032L));
    }

    @Test public void pendingMaterialDefersAtMostThreeDraws() {
        BannerMaterialStart start = new BannerMaterialStart();

        assertFalse(start.allowDraw(true, false, 1, 100, 60, 1000L));
        assertFalse(start.allowDraw(true, false, 1, 100, 60, 1001L));
        assertFalse(start.allowDraw(true, false, 1, 100, 60, 1002L));
        assertTrue(start.allowDraw(true, false, 1, 100, 60, 1003L));
    }

    @Test public void repeatedRevisionsCannotExtendTheThreeDeferralLimit() {
        BannerMaterialStart start = new BannerMaterialStart();

        assertFalse(start.allowDraw(true, true, 1, 100, 60, 1000L));
        assertFalse(start.allowDraw(true, true, 2, 120, 60, 1001L));
        assertFalse(start.allowDraw(true, true, 3, 140, 80, 1002L));
        assertTrue(start.allowDraw(true, true, 4, 160, 100, 1003L));
    }

    @Test public void waitLimitAllowsDrawAtExactlyOneHundredTwentyMilliseconds() {
        BannerMaterialStart start = new BannerMaterialStart();

        assertFalse(start.allowDraw(true, false, 1, 100, 60, 1000L));
        assertFalse(start.allowDraw(true, false, 1, 100, 60, 1119L));
        assertTrue(start.allowDraw(true, false, 1, 100, 60, 1120L));
    }

    @Test public void changingStateDoesNotRestartTheWaitLimit() {
        BannerMaterialStart start = new BannerMaterialStart();

        assertFalse(start.allowDraw(true, true, 1, 100, 60, 1000L));
        assertFalse(start.allowDraw(true, true, 2, 120, 80, 1119L));
        assertTrue(start.allowDraw(true, true, 3, 140, 100, 1120L));
    }

    @Test public void waitLimitAllowsDrawAfterALongGapWithoutMoreTraversals() {
        BannerMaterialStart start = new BannerMaterialStart();

        assertFalse(start.allowDraw(true, false, 1, 100, 60, 1000L));
        assertTrue(start.allowDraw(true, false, 2, 0, 0, 1500L));
    }

    @Test public void openedMaterialNeverBlocksAgainWhenStateOrSettingsChange() {
        BannerMaterialStart start = new BannerMaterialStart();

        assertFalse(start.allowDraw(true, true, 1, 100, 60, 1000L));
        assertTrue(start.allowDraw(true, true, 1, 100, 60, 1016L));
        assertTrue(start.allowDraw(true, false, 2, 0, 0, 1017L));
        assertTrue(start.allowDraw(false, false, 3, 0, 0, 1018L));
        assertTrue(start.allowDraw(true, true, 4, 200, 120, 1019L));
    }

    @Test public void enablingMaterialAfterAnUnrequestedDrawDoesNotBlock() {
        BannerMaterialStart start = new BannerMaterialStart();

        assertTrue(start.allowDraw(false, false, -1, 0, 0, 1000L));
        assertTrue(start.allowDraw(true, false, 1, 100, 60, 1001L));
        assertTrue(start.allowDraw(true, true, 1, 100, 60, 1002L));
    }

    @Test public void disablingMaterialReleasesTheWaitWithoutReopeningItLater() {
        BannerMaterialStart start = new BannerMaterialStart();

        assertFalse(start.allowDraw(true, false, 1, 100, 60, 1000L));
        assertTrue(start.allowDraw(false, false, 1, 100, 60, 1001L));
        assertTrue(start.allowDraw(true, false, 2, 0, 0, 1002L));
        assertTrue(start.allowDraw(true, true, 3, 120, 80, 1003L));
    }
}
