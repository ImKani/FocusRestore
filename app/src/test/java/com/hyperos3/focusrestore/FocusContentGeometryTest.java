/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FocusContentGeometryTest {
    @Test public void reportedNegativeTimeGapKeepsIconAndTextSeparated() {
        int iconStart = FocusContentGeometry.focusStart(-6, 2, 2);
        int textStart = FocusContentGeometry.textStart(iconStart, 16, 3);
        assertEquals(-2, iconStart);
        assertEquals(17, textStart);
        assertEquals(3, textStart - (iconStart + 16));
        // 任意滚动偏移都不能穿过正文视口起点；固定图标终点在该起点外。
        for (int scroll = 0; scroll <= 300; scroll++) {
            int firstVisibleText = Math.max(textStart - scroll,
                    FocusContentGeometry.textClipBoundary(160, textStart, false));
            assertTrue(firstVisibleText >= iconStart + 16 + 3);
        }
    }

    @Test public void timeGapMovesDividerIconAndTextTogether() {
        for (int time = -24; time <= 24; time++) {
            int iconStart = FocusContentGeometry.focusStart(time, 2, 2);
            int textStart = FocusContentGeometry.textStart(iconStart, 8, 7);
            assertEquals(2, iconStart - (time + 2));
            assertEquals(7, textStart - (iconStart + 8));
        }
    }

    @Test public void negativeFocusGapDoesNotChangeIndependentTextGap() {
        for (int gap = -24; gap <= 24; gap++) {
            int iconStart = FocusContentGeometry.focusStart(4, 2, gap);
            assertEquals(5, FocusContentGeometry.textStart(iconStart, 32, 5) - iconStart - 32);
        }
    }

    @Test public void rtlClipMirrorsTextBoundaryIncludingNegativeOrigins() {
        for (int start = -30; start < 60; start++) {
            assertEquals(160, FocusContentGeometry.textClipBoundary(160, start, false)
                    + FocusContentGeometry.textClipBoundary(160, start, true));
        }
        assertEquals(-9, FocusContentGeometry.textClipBoundary(160, -9, false));
        assertEquals(169, FocusContentGeometry.textClipBoundary(160, -9, true));
    }

    @Test public void hiddenStartAndExpandedTailDoNotDispatchToRemoteViewsButtons() {
        assertTrue(FocusContentGeometry.outsideTextViewport(10, 200, 160, 17, false));
        assertTrue(FocusContentGeometry.outsideTextViewport(160, 200, 160, 17, false));
        org.junit.Assert.assertFalse(FocusContentGeometry.outsideTextViewport(17, 200, 160, 17, false));
        org.junit.Assert.assertFalse(FocusContentGeometry.outsideTextViewport(159, 200, 160, 17, false));
        assertTrue(FocusContentGeometry.outsideTextViewport(190, 200, 160, 17, true));
        assertTrue(FocusContentGeometry.outsideTextViewport(39, 200, 160, 17, true));
        assertTrue(FocusContentGeometry.outsideTextViewport(183, 200, 160, 17, true));
        org.junit.Assert.assertFalse(FocusContentGeometry.outsideTextViewport(40, 200, 160, 17, true));
        org.junit.Assert.assertFalse(FocusContentGeometry.outsideTextViewport(182, 200, 160, 17, true));
        org.junit.Assert.assertFalse(FocusContentGeometry.outsideTextViewport(0, 200, 160, -9, false));
        org.junit.Assert.assertFalse(FocusContentGeometry.outsideTextViewport(199, 200, 160, -9, true));
    }

    @Test public void noIconUsesFocusStartWithoutInventingTextGap() {
        assertEquals(12, FocusContentGeometry.focusStart(4, 2, 6));
        assertEquals(0, FocusContentGeometry.textStart(0, 0, 0));
    }

    @Test public void clippingOnlyTrailingPaddingDoesNotShrinkVisibleTextAgain() {
        // OS3 的 160px 槽含 8px 尾部留白；父层裁掉留白后仍可显示完整的 152px 文字。
        assertEquals(152, FocusContentGeometry.visibleTextWidth(160, 0, 8, 0, 160));
        assertEquals(152, FocusContentGeometry.visibleTextWidth(160, 0, 8, 0, 152));
        assertEquals(140, FocusContentGeometry.visibleTextWidth(160, 0, 8, 0, 140));
    }

    @Test public void clippedPaddingAndTextAreIntersectedOnBothSides() {
        // RTL 把留白放在左侧；裁掉部分或全部留白不应再次扣完整 padding。
        assertEquals(152, FocusContentGeometry.visibleTextWidth(160, 8, 0, 4, 160));
        assertEquals(152, FocusContentGeometry.visibleTextWidth(160, 8, 0, 8, 160));
        assertEquals(140, FocusContentGeometry.visibleTextWidth(160, 8, 0, 20, 160));
        assertEquals(130, FocusContentGeometry.visibleTextWidth(160, 6, 8, 10, 140));
    }

    @Test public void missingViewportOrPaddingWiderThanViewProducesNoTextArea() {
        assertEquals(0, FocusContentGeometry.visibleTextWidth(160, 0, 8, 152, 160));
        assertEquals(0, FocusContentGeometry.visibleTextWidth(160, 0, 8, 180, 200));
        assertEquals(0, FocusContentGeometry.visibleTextWidth(160, 0, 8, 30, 20));
        assertEquals(0, FocusContentGeometry.visibleTextWidth(4, 3, 3, 0, 4));
    }
}
