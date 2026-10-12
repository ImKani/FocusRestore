/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class OS4DarkTintCallbackTest {
    @Test public void contrastCallbackDoesNotOverwriteWhiteTextWithBlack() {
        assertCallbackPair(0xe6ffffff, 0xff000000, 0f);
    }

    @Test public void contrastCallbackDoesNotOverwriteBlackTextWithWhite() {
        assertCallbackPair(0xff000000, 0xe6ffffff, 1f);
    }

    @Test public void transitionKeepsExactTintAndAlphaWithoutUsingIntensity() {
        assertCallbackPair(0xf3808080, 0xff7f7f7f, 0.5f);
        assertCallbackPair(0x00000000, 0xffffffff, 0f);
    }

    @Test public void malformedCallbacksDoNotSupplyATint() {
        assertNull(OS4DarkTintCallback.normalTint("onDarkChanged", null));
        assertNull(OS4DarkTintCallback.normalTint("onDarkChanged", new Object[]{null, 0f}));
        assertNull(OS4DarkTintCallback.normalTint("onDarkChanged", new Object[]{null, 0f, null}));
        assertNull(OS4DarkTintCallback.normalTint("onDarkChangedWithContrast",
                new Object[]{null, 0f, 0xff000000}));
    }

    @Test public void paletteCallbackIsNotAResolvedTextColor() {
        assertNull(OS4DarkTintCallback.normalTint("onLightDarkTintChanged",
                new Object[]{0xe6ffffff, 0xff000000, true}));
        assertNull(OS4DarkTintCallback.normalTint("equals", new Object[]{this}));
    }

    private static void assertCallbackPair(int normalTint, int contrastTint, float intensity) {
        Object areas = new Object();
        Integer regular = OS4DarkTintCallback.normalTint("onDarkChanged",
                new Object[]{areas, intensity, normalTint});
        Integer contrast = OS4DarkTintCallback.normalTint("onDarkChangedWithContrast",
                new Object[]{areas, normalTint, contrastTint});
        assertEquals(Integer.valueOf(normalTint), regular);
        assertEquals("同一轮 ROM 分发不能被后续对比色回调反向覆盖", regular, contrast);
    }
}
