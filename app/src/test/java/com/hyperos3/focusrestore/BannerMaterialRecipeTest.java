/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import static org.junit.Assert.*;
import org.junit.Test;

/** 决策与例外的纯 JVM 覆盖，不代表 Android 混色或视觉验收。 */
public class BannerMaterialRecipeTest {
    @Test public void os3RequiresEligibleNonGlassAndDoesNotDependOnCapture() {
        assertEquals(BannerMaterialRecipe.OS3_FOCUS_HUN, BannerMaterialRecipe.choose(true, true, false, false));
        assertEquals(BannerMaterialRecipe.OS3_FOCUS_HUN, BannerMaterialRecipe.choose(true, true, false, true));
        assertEquals(BannerMaterialRecipe.LEGACY, BannerMaterialRecipe.choose(true, true, true, true));
    }

    @Test public void os4RequiresGlassAndCurrentConfigurationSample() {
        assertEquals(BannerMaterialRecipe.LEGACY, BannerMaterialRecipe.choose(true, false, true, false));
        assertEquals(BannerMaterialRecipe.LEGACY, BannerMaterialRecipe.choose(true, false, false, true));
        assertEquals(BannerMaterialRecipe.OS4_CAPTURED_HUN, BannerMaterialRecipe.choose(true, false, true, true));
    }

    @Test public void exceptionsAlwaysKeepLegacyEvenWithSample() {
        for (boolean os3 : new boolean[]{false, true}) {
            for (boolean glass : new boolean[]{false, true}) {
                assertEquals(BannerMaterialRecipe.LEGACY, BannerMaterialRecipe.choose(false, os3, glass, true));
            }
        }
    }

    @Test public void lateSampleNeverChangesAlreadyCreatedRenderPolicy() {
        boolean firstRender = BannerMaterialRecipe.freezeEligibility(true, false, false);
        assertFalse(firstRender);
        assertEquals(BannerMaterialRecipe.LEGACY, BannerMaterialRecipe.choose(firstRender, false, true, false));
        assertEquals(BannerMaterialRecipe.LEGACY, BannerMaterialRecipe.choose(firstRender, false, true, true));
        boolean nextRender = BannerMaterialRecipe.freezeEligibility(true, false, true);
        assertTrue(nextRender);
        assertEquals(BannerMaterialRecipe.OS4_CAPTURED_HUN, BannerMaterialRecipe.choose(nextRender, false, true, true));
        assertTrue(BannerMaterialRecipe.freezeEligibility(true, true, false));
        assertFalse(BannerMaterialRecipe.freezeEligibility(false, false, true));
    }

    @Test public void rowBindingMustMatchKeyAndPostTime() {
        assertTrue(BannerHunEligibility.sameGeneration("key-a", 10, "key-a", 10));
        assertFalse(BannerHunEligibility.sameGeneration("key-a", 10, "key-a", 11));
        assertFalse(BannerHunEligibility.sameGeneration("key-a", 10, "key-b", 10));
        assertFalse(BannerHunEligibility.sameGeneration(null, 10, null, 10));
    }

    @Test public void eligibilityRequiresKnownPrivateOrdinaryBackground() {
        for (boolean known : new boolean[]{false, true}) {
            for (boolean publicContent : new boolean[]{false, true}) {
                for (boolean transparent : new boolean[]{false, true}) {
                    for (boolean custom : new boolean[]{false, true}) {
                        assertEquals(known && !publicContent && !transparent && !custom,
                                BannerHunEligibility.allows(known, publicContent, transparent, custom));
                    }
                }
            }
        }
    }
}
