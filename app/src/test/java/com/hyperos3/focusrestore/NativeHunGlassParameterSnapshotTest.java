/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public class NativeHunGlassParameterSnapshotTest {
    private static final String PACKAGE = "com.android.systemui";
    private static final String VERSION = "17.03.260226.r";
    private static final long CODE = 202602260L;
    private static final String DAY = "seq=10|uiMode=16";
    private static final String NIGHT = "seq=11|uiMode=32";

    private static float[] parameters(float first) {
        float[] values = new float[NativeHunGlassParameterSnapshot.PARAMETER_COUNT];
        Arrays.fill(values, first);
        return values;
    }

    @Test public void rejectsWrongProfileTypeAndMalformedOrCleanupArrays() {
        NativeHunGlassParameterSnapshot.Cache cache = new NativeHunGlassParameterSnapshot.Cache();
        assertFalse(cache.publish("com.android.systemui:remote", VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE, parameters(1f)));
        assertFalse(cache.publish(PACKAGE, "17.03.260226.q", CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE, parameters(1f)));
        assertFalse(cache.publish(PACKAGE, VERSION, CODE + 1, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE, parameters(1f)));
        assertFalse(cache.publish(PACKAGE, VERSION, CODE, DAY, 10, "GLASS_FOCUS",
                parameters(1f)));
        assertFalse(cache.publish(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE, new float[41]));
        assertFalse(cache.publish(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE, new float[42]));
        float[] nonFinite = parameters(1f);
        nonFinite[17] = Float.POSITIVE_INFINITY;
        assertFalse(cache.publish(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE, nonFinite));
        assertNull(cache.get(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE));
    }

    @Test public void cacheReturnsCopiesAndKeepsTypesWithinSameConfiguration() {
        NativeHunGlassParameterSnapshot.Cache cache = new NativeHunGlassParameterSnapshot.Cache();
        float[] light = parameters(0.5f);
        float[] dark = parameters(0.8f);
        assertTrue(cache.publish(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE, light));
        assertTrue(cache.publish(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.DARK_TYPE, dark));
        light[0] = 90f;
        NativeHunGlassParameterSnapshot snapshot = cache.get(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE);
        assertNotNull(snapshot);
        float[] returned = snapshot.parameters();
        assertEquals(0.5f, returned[0], 0f);
        returned[0] = 80f;
        assertEquals(0.5f, snapshot.parameters()[0], 0f);
        assertEquals(0.8f, cache.get(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.DARK_TYPE).parameters()[0], 0f);
    }

    @Test public void newConfigurationGenerationEvictsBothThemeSamples() {
        NativeHunGlassParameterSnapshot.Cache cache = new NativeHunGlassParameterSnapshot.Cache();
        cache.publish(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE, parameters(0.5f));
        cache.publish(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.DARK_TYPE, parameters(0.8f));
        long oldGeneration = cache.get(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE).generation();
        assertNull(cache.get(PACKAGE, VERSION, CODE, NIGHT, 11,
                NativeHunGlassParameterSnapshot.DARK_TYPE));
        assertNull(cache.get(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE));
        assertTrue(cache.publish(PACKAGE, VERSION, CODE, NIGHT, 11,
                NativeHunGlassParameterSnapshot.DARK_TYPE, parameters(0.9f)));
        assertTrue(cache.get(PACKAGE, VERSION, CODE, NIGHT, 11,
                NativeHunGlassParameterSnapshot.DARK_TYPE).generation() > oldGeneration);
    }

    @Test public void explicitInvalidationPreventsOldConfigurationFromReturning() {
        NativeHunGlassParameterSnapshot.Cache cache = new NativeHunGlassParameterSnapshot.Cache();
        cache.publish(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE, parameters(0.5f));
        cache.invalidate();
        assertNull(cache.get(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE));
    }

    @Test public void failedValidationDoesNotReplaceGoodSample() {
        NativeHunGlassParameterSnapshot.Cache cache = new NativeHunGlassParameterSnapshot.Cache();
        cache.publish(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE, parameters(0.5f));
        assertFalse(cache.publish(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE, new float[42]));
        assertEquals(0.5f, cache.get(PACKAGE, VERSION, CODE, DAY, 10,
                NativeHunGlassParameterSnapshot.LIGHT_TYPE).parameters()[0], 0f);
    }
}
