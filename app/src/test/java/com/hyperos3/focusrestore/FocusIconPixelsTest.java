/* SPDX-License-Identifier: GPL-3.0-only; Copyright (C) ImKani; FocusRestore: https://github.com/ImKani/FocusRestore */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.*;

public class FocusIconPixelsTest {
    @Test public void recoloringPreservesHoleAndAntialiasAlpha() {
        assertEquals(0, FocusIconPixels.recolor(0, 0xff000000) >>> 24);
        assertEquals(0, FocusIconPixels.recolor(0, 0xffffffff) >>> 24);
        assertEquals(0x80ffffff, FocusIconPixels.recolor(0x80123456, 0xffffffff));
        assertEquals(0x80000000, FocusIconPixels.recolor(0x80123456, 0xff000000));
        assertEquals(0xff000000, FocusIconPixels.recolor(0xffffffff, 0xff000000));
    }
    @Test public void squareLeavesSafeMargin() {
        assertArrayEquals(new int[]{3, 3, 36, 36}, FocusIconPixels.bounds(39, 3, 24, 24));
    }
    @Test public void nonSquareKeepsAspectAndCenters() {
        assertArrayEquals(new int[]{2, 11, 38, 29}, FocusIconPixels.bounds(40, 2, 48, 24));
    }
    @Test public void unknownDimensionsAndTinyBoxAreSafe() {
        assertArrayEquals(new int[]{2, 2, 38, 38}, FocusIconPixels.bounds(40, 2, -1, -1));
        assertArrayEquals(new int[]{0, 0, 1, 1}, FocusIconPixels.bounds(1, 0, 24, 24));
    }
}
