/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

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
    @Test public void transparentIslandMaskUsesTintAndKeepsAntialiasAlpha() {
        int[] pixels = {0, 0x80ffffff, 0, 0xffffffff};
        assertTrue(FocusIconPixels.tint(pixels, 2, new int[]{0, 0, 2, 2}, true, 0xff000000));
        assertArrayEquals(new int[]{0, 0x80000000, 0, 0xff000000}, pixels);
    }
    @Test public void opaqueIslandKeepsInternalDetailsForBothForegroundColors() {
        int[] original = {0xffeeeeee, 0xff111111, 0xff111111, 0xffeeeeee};
        for (int color : new int[]{0xff000000, 0xffffffff}) {
            int[] pixels = original.clone();
            assertFalse(FocusIconPixels.tint(pixels, 2, new int[]{0, 0, 2, 2}, true, color));
            assertArrayEquals(original, pixels);
        }
    }
    @Test public void opaqueSingleColorIslandIsAdaptedWithoutTintingGeneratedPaddingAlpha() {
        int[] pixels = new int[16];
        pixels[5] = pixels[6] = pixels[9] = pixels[10] = 0xffffffff;
        assertTrue(FocusIconPixels.tint(pixels, 4, new int[]{1, 1, 3, 3}, true, 0xff000000));
        for (int index = 0; index < pixels.length; index++) {
            assertEquals(index == 5 || index == 6 || index == 9 || index == 10
                    ? 0xff000000 : 0, pixels[index]);
        }
    }
    @Test public void multicolorIslandWithTransparentCornersIsNotFlattened() {
        int[] pixels = {0, 0xffee0000, 0xff0000ee, 0};
        int[] original = pixels.clone();
        assertFalse(FocusIconPixels.tint(pixels, 2, new int[]{0, 0, 2, 2}, true, 0xff000000));
        assertArrayEquals(original, pixels);
    }
    @Test public void notificationAndDeviceTintContractRemainsUnchanged() {
        int[] pixels = {0xffffffff, 0x80123456, 0xff000000, 0};
        assertTrue(FocusIconPixels.tint(pixels, 2, new int[]{0, 0, 2, 2}, false, 0xff000000));
        assertArrayEquals(new int[]{0xff000000, 0x80000000, 0xff000000, 0}, pixels);
    }
    @Test public void emptyOrLowCoverageIslandIsKeptWithoutInventingAShape() {
        int[] pixels = {0, 0x40123456, 0, 0};
        int[] original = pixels.clone();
        assertFalse(FocusIconPixels.tint(pixels, 2, new int[]{0, 0, 2, 2}, true, 0xffffffff));
        assertArrayEquals(original, pixels);
        assertFalse(FocusIconPixels.tint(new int[0], 0, new int[]{0, 0, 0, 0}, true, 0xffffffff));
    }

    @Test public void matchingBlackAndWhiteMasksStillUseAdaptiveTint() {
        for (int color : new int[]{0xff000000, 0xffffffff}) {
            int[] pixels = {0, color, 0, color};
            assertTrue(FocusIconPixels.tint(pixels, 2, new int[]{0, 0, 2, 2}, true, color));
            assertArrayEquals(new int[]{color & 0x00ffffff, color,
                    color & 0x00ffffff, color}, pixels);
        }
    }
    @Test public void lowContrastBlackMaskBecomesWhiteWithoutChangingAlpha() {
        int[] pixels = {0, 0x80000000, 0xff000000, 0};
        assertTrue(FocusIconPixels.tint(pixels, 2, new int[]{0, 0, 2, 2}, true, 0xffffffff));
        assertArrayEquals(new int[]{0x00ffffff, 0x80ffffff, 0xffffffff, 0x00ffffff}, pixels);
    }
    @Test public void grayMasksAreAdaptedToBothForegroundsRegardlessOfOldContrast() {
        for (int gray : new int[]{0xff333333, 0xff777777, 0xffcccccc}) {
            for (int color : new int[]{0xff000000, 0xffffffff}) {
                int[] pixels = {0, gray, gray, 0};
                assertTrue(FocusIconPixels.tint(pixels, 2, new int[]{0, 0, 2, 2}, true, color));
                assertArrayEquals(new int[]{color & 0x00ffffff, color,
                        color, color & 0x00ffffff}, pixels);
            }
        }
    }
    @Test public void singleHueMasksAlwaysAdaptAndKeepAlpha() {
        for (int hue : new int[]{0x00ff0000, 0x0000ff00, 0x000000ff, 0x00ff00ff}) {
            for (int color : new int[]{0xff000000, 0xffffffff}) {
                int[] pixels = {0, 0x80000000 | hue, 0xff000000 | hue, 0x40000000 | hue};
                assertTrue(FocusIconPixels.tint(pixels, 2, new int[]{0, 0, 2, 2}, true, color));
                assertArrayEquals(new int[]{color & 0x00ffffff, 0x80000000 | (color & 0x00ffffff),
                        color, 0x40000000 | (color & 0x00ffffff)}, pixels);
            }
        }
    }
    @Test public void opaqueColoredArtworkKeepsItsContrastingPattern() {
        int[] original = {0xff008800, 0xffffffff, 0xffffffff, 0xff008800};
        for (int color : new int[]{0xff000000, 0xffffffff}) {
            int[] pixels = original.clone();
            assertFalse(FocusIconPixels.tint(pixels, 2, new int[]{0, 0, 2, 2}, true, color));
            assertArrayEquals(original, pixels);
        }
    }
    @Test public void multicolorIsKeptEvenWhenOneColorHasPoorContrast() {
        int[] pixels = {0, 0xffffffff, 0xffff0000, 0};
        int[] original = pixels.clone();
        assertFalse(FocusIconPixels.tint(pixels, 2, new int[]{0, 0, 2, 2}, true, 0xff000000));
        assertArrayEquals(original, pixels);
    }

    @Test public void blueGradientFormsOneDominantClusterWithoutChangingAnalysisInput() {
        int[] pixels = new int[100];
        java.util.Arrays.fill(pixels, 0xff2196f3);
        java.util.Arrays.fill(pixels, 80, 100, 0xff1976d2);
        int[] original = pixels.clone();
        FocusIconPixels.Analysis result = FocusIconPixels.analyze(pixels, 10, new int[]{0, 0, 10, 10});
        assertTrue(result.monochrome);
        assertEquals(100, result.valid);
        assertEquals(80, result.bestCount);
        assertEquals(100, result.near);
        assertEquals(0x1f8fec, result.average);
        assertArrayEquals(original, pixels);
        System.out.println("synthetic-blue-gradient " + result.diagnostic());
    }
    @Test public void dominantClusterUsesExactNinetyPercentBoundary() {
        int[] pixels = new int[100];
        java.util.Arrays.fill(pixels, 0xff2196f3);
        java.util.Arrays.fill(pixels, 90, 100, 0xffffffff);
        FocusIconPixels.Analysis accepted = FocusIconPixels.analyze(pixels, 10, new int[]{0, 0, 10, 10});
        assertTrue(accepted.monochrome);
        assertEquals(90, accepted.near);
        assertEquals(0x2196f3, accepted.average);
        assertTrue(accepted.maxDistanceSquared > 64 * 64);
        pixels[89] = 0xffffffff;
        FocusIconPixels.Analysis rejected = FocusIconPixels.analyze(pixels, 10, new int[]{0, 0, 10, 10});
        assertFalse(rejected.monochrome);
        assertEquals(89, rejected.near);
        assertTrue(rejected.diagnostic().contains("reason=outside-dominant-cluster"));
    }
    @Test public void blueAndWhiteArtworkWithTwentyPercentDetailRemainsUntouched() {
        int[] pixels = new int[100];
        java.util.Arrays.fill(pixels, 0xff2196f3);
        java.util.Arrays.fill(pixels, 80, 100, 0xffffffff);
        int[] original = pixels.clone();
        assertFalse(FocusIconPixels.tint(pixels, 10, new int[]{0, 0, 10, 10}, true, 0xff000000));
        assertArrayEquals(original, pixels);
    }
    @Test public void alphaThresholdAndFivePercentCoverageAreIndependentOfRgbNoise() {
        int[] pixels = new int[100];
        java.util.Arrays.fill(pixels, 0x7fffffff);
        java.util.Arrays.fill(pixels, 0, 5, 0x802196f3);
        FocusIconPixels.Analysis accepted = FocusIconPixels.analyze(pixels, 10, new int[]{0, 0, 10, 10});
        assertTrue(accepted.monochrome);
        assertEquals(5, accepted.valid);
        pixels[4] = 0x7f2196f3;
        FocusIconPixels.Analysis rejected = FocusIconPixels.analyze(pixels, 10, new int[]{0, 0, 10, 10});
        assertFalse(rejected.monochrome);
        assertEquals(4, rejected.valid);
        assertTrue(rejected.diagnostic().contains("reason=coverage-below-5pct"));
    }
    @Test public void upperQuantizationBucketUsesSixteenWideCenters() {
        int[] pixels = new int[100];
        java.util.Arrays.fill(pixels, 0xfff0f0f0);
        java.util.Arrays.fill(pixels, 80, 100, 0xffdcdcdc);
        FocusIconPixels.Analysis result = FocusIconPixels.analyze(pixels, 10, new int[]{0, 0, 10, 10});
        assertTrue(result.monochrome);
        assertEquals(100, result.near);
        assertEquals(0xececec, result.average);
    }
    @Test public void generatedPaddingDoesNotReduceOriginalArtworkCoverage() {
        int[] pixels = new int[400];
        pixels[189] = pixels[190] = pixels[209] = pixels[210] = 0xff2196f3;
        FocusIconPixels.Analysis result = FocusIconPixels.analyze(pixels, 20, new int[]{9, 9, 11, 11});
        assertTrue(result.monochrome);
        assertEquals(4, result.total);
        assertEquals(4, result.valid);
    }
    @Test public void dominantGradientTintPreservesAlphaAndUsesRequestedForegroundNotAverage() {
        int[] pixels = {0, 0x802196f3, 0xff1976d2, 0x402196f3};
        assertTrue(FocusIconPixels.tint(pixels, 2, new int[]{0, 0, 2, 2}, true, 0xffffffff));
        assertArrayEquals(new int[]{0x00ffffff, 0x80ffffff, 0xffffffff, 0x40ffffff}, pixels);
    }
    @Test public void emptyAndInvalidAnalysisHaveExplicitReasonsAndNoDivisionByZero() {
        FocusIconPixels.Analysis empty = FocusIconPixels.analyze(new int[4], 2, new int[]{0, 0, 2, 2});
        assertFalse(empty.monochrome);
        assertTrue(empty.diagnostic().contains("reason=no-foreground"));
        assertTrue(empty.diagnostic().contains("nearRatio=0.0000"));
        assertFalse(FocusIconPixels.analyze(null, 2, new int[]{0, 0, 2, 2}).monochrome);
        assertTrue(FocusIconPixels.analyze(new int[4], 2, new int[]{2, 2, 0, 0})
                .diagnostic().contains("reason=invalid-bounds"));
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