/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class FocusIconDragGeometryTest {
    private static final float[] CENTERS = {24f, 76f, 128f};

    @Test public void secondRowCanCrossFirstEvenWhenVisualTopIsClampedToZero() {
        assertEquals(0, FocusIconDragGeometry.targetSlot(23f, 24f, 48, 1, CENTERS));
    }

    @Test public void thirdRowCanMoveDirectlyToFirstInOneEvent() {
        assertEquals(0, FocusIconDragGeometry.targetSlot(-10f, 40f, 48, 2, CENTERS));
    }

    @Test public void firstRowCanMoveDirectlyToLastBeyondBottomBoundary() {
        assertEquals(2, FocusIconDragGeometry.targetSlot(200f, 12f, 48, 0, CENTERS));
    }

    @Test public void sameGestureCanReverseFromFirstToLastAndBack() {
        int slot = FocusIconDragGeometry.targetSlot(23f, 24f, 48, 2, CENTERS);
        assertEquals(0, slot);
        slot = FocusIconDragGeometry.targetSlot(129f, 24f, 48, slot, CENTERS);
        assertEquals(2, slot);
        assertEquals(0, FocusIconDragGeometry.targetSlot(23f, 24f, 48, slot, CENTERS));
    }

    @Test public void measuredUnequalHeightsMarginsAndGrabOffsetDetermineMiddleCrossing() {
        float[] centers = {34f, 118f, 182f};
        assertEquals(0, FocusIconDragGeometry.targetSlot(40f, 60f, 72, 1, centers));
        assertEquals(1, FocusIconDragGeometry.targetSlot(90f, 10f, 72, 1, centers));
        assertEquals(2, FocusIconDragGeometry.targetSlot(157f, 10f, 72, 1, centers));
    }

    @Test public void stayingWithinAdjacentCentersDoesNotReorder() {
        assertEquals(1, FocusIconDragGeometry.targetSlot(70f, 24f, 48, 1, CENTERS));
        assertEquals(1, FocusIconDragGeometry.targetSlot(24f, 24f, 48, 1, CENTERS));
        assertEquals(1, FocusIconDragGeometry.targetSlot(128f, 24f, 48, 1, CENTERS));
    }

    @Test public void compactPaddingLetsEveryGrabPointReachBothEndsInsideContainer() {
        // 覆盖同高、大字体不同高度及奇数像素，固定6px留白不随行高增长。
        for (int[] heights : new int[][]{{48, 48, 48}, {48, 96, 72}, {73, 49, 95}}) {
            float[] centers = new float[heights.length];
            int rowTop = 6;
            for (int slot = 0; slot < heights.length; slot++) {
                centers[slot] = rowTop + heights[slot] / 2f;
                rowTop += heights[slot] + 4;
            }
            int containerHeight = rowTop + 6;
            for (int slot = 0; slot < heights.length; slot++) {
                for (int grab = 0; grab < heights[slot]; grab++) {
                    assertEquals(0, FocusIconDragGeometry.targetSlot(1f, grab, heights[slot], slot, centers));
                    assertEquals(2, FocusIconDragGeometry.targetSlot(containerHeight - 1f,
                            grab, heights[slot], slot, centers));
                }
            }
        }
    }

    @Test public void topGrabReachesFirstWithoutAnyPadding() {
        assertEquals(0, FocusIconDragGeometry.targetSlot(0f, 0f, 48, 1, CENTERS));
        assertEquals(0, FocusIconDragGeometry.targetSlot(23f, 0f, 96, 2, CENTERS));
    }

    @Test public void bottomGrabReachesLastWithoutAnyPadding() {
        assertEquals(2, FocusIconDragGeometry.targetSlot(152f, 47f, 48, 0, CENTERS));
        assertEquals(2, FocusIconDragGeometry.targetSlot(129f, 95f, 96, 1, CENTERS));
    }
}
