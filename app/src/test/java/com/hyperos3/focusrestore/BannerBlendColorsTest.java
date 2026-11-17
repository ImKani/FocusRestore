/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class BannerBlendColorsTest {
    private static final class Palette implements BannerBlendColors.Resources {
        final List<String> reads = new ArrayList<>();
        int color1 = 11, color2 = 22;
        String fail;
        @Override public int color(String name) {
            reads.add(name);
            if (name.equals(fail)) throw new IllegalStateException("missing " + name);
            return name.endsWith("_1") ? color1 : color2;
        }
        @Override public int integer(String name) {
            reads.add(name);
            if (name.equals(fail)) throw new IllegalStateException("missing " + name);
            return name.endsWith("_1") ? 107 : 106;
        }
    }

    @Test public void readsTwoOrderedNotificationPairs() {
        Palette p = new Palette();
        assertArrayEquals(new int[]{11, 107, 22, 106}, BannerBlendColors.load(p, false));
        assertEquals(Arrays.asList("notification_element_blend_shade_color_1",
                "notification_element_blend_shade_mode_1", "notification_element_blend_shade_color_2",
                "notification_element_blend_shade_mode_2"), p.reads);
    }

    @Test public void reloadingReaderValuesDoesNotMutatePreviouslyLoadedPalette() {
        Palette p = new Palette();
        int[] light = BannerBlendColors.load(p, false);
        p.color1 = 33;
        p.color2 = 44;
        assertArrayEquals(new int[]{33, 107, 44, 106}, BannerBlendColors.load(p, false));
        assertArrayEquals(new int[]{11, 107, 22, 106}, light);
    }

    @Test public void anyMissingResourceRejectsPartialPalette() {
        for (String missing : new String[]{"notification_element_blend_shade_color_1",
                "notification_element_blend_shade_mode_1", "notification_element_blend_shade_color_2",
                "notification_element_blend_shade_mode_2"}) {
            Palette p = new Palette();
            p.fail = missing;
            try { BannerBlendColors.load(p, false); fail("must reject partial palette"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains(missing)); }
        }
    }

    @Test public void hunUsesItsOwnTwoOrderedPairs() {
        Palette p = new Palette();
        assertArrayEquals(new int[]{11, 107, 22, 106}, BannerBlendColors.load(p, true));
        assertEquals(Arrays.asList("notification_element_blend_headsUp_color_1",
                "notification_element_blend_headsUp_mode_1", "notification_element_blend_headsUp_color_2",
                "notification_element_blend_headsUp_mode_2"), p.reads);
    }

    @Test public void switchingRecipeKeepsEachPreviousPaletteIndependent() {
        Palette p = new Palette();
        int[] shade = BannerBlendColors.load(p, false);
        p.color1 = 33;
        p.color2 = 44;
        int[] hun = BannerBlendColors.load(p, true);
        p.color1 = 55;
        int[] shadeAgain = BannerBlendColors.load(p, false);
        assertArrayEquals(new int[]{11, 107, 22, 106}, shade);
        assertArrayEquals(new int[]{33, 107, 44, 106}, hun);
        assertArrayEquals(new int[]{55, 107, 44, 106}, shadeAgain);
    }

    @Test public void missingHunResourceDoesNotSilentlyReadShade() {
        for (String suffix : new String[]{"color_1", "mode_1", "color_2", "mode_2"}) {
            Palette p = new Palette();
            p.fail = "notification_element_blend_headsUp_" + suffix;
            try { BannerBlendColors.load(p, true); fail("must reject incomplete HUN recipe"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains(p.fail)); }
            for (String name : p.reads) assertFalse(name.contains("shade"));
        }
    }

    @Test public void focusHunUsesThreeThemeResolvedPairs() {
        final List<String> reads = new ArrayList<>();
        BannerBlendColors.Resources resources = new BannerBlendColors.Resources() {
            @Override public int color(String name) { reads.add(name); return name.charAt(name.length() - 1) - '0'; }
            @Override public int integer(String name) {
                reads.add(name);
                return name.equals("blur_mode_pure") ? 3 : name.equals("blur_mode_lab") ? 106 : 107;
            }
        };
        assertArrayEquals(new int[]{1, 107, 2, 106, 3, 3}, BannerBlendColors.loadFocusHun(resources));
        assertEquals(Arrays.asList("focus_notification_element_blend_headsUp_color_1", "blur_mode_linear_light",
                "focus_notification_element_blend_headsUp_color_2", "blur_mode_lab",
                "focus_notification_element_blend_headsUp_color_3", "blur_mode_pure"), reads);
    }

    @Test public void focusHunRejectsAnyMissingResourceWithoutReadingLegacy() {
        for (String missing : new String[]{"focus_notification_element_blend_headsUp_color_1", "blur_mode_linear_light",
                "focus_notification_element_blend_headsUp_color_2", "blur_mode_lab",
                "focus_notification_element_blend_headsUp_color_3", "blur_mode_pure"}) {
            Palette p = new Palette();
            p.fail = missing;
            try { BannerBlendColors.loadFocusHun(p); fail("incomplete focus HUN recipe"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains(missing)); }
            for (String name : p.reads) assertFalse(name.startsWith("notification_element_blend_"));
        }
    }

    @Test public void missingReaderFailsExplicitly() {
        try { BannerBlendColors.load(null, false); fail("missing reader"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("notification")); }
    }
}
