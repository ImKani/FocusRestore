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

/** 纯JVM状态测试，不模拟厂商shader或宣称设备视觉通过。 */
public class BannerGlassStateTest {
    private static float[] parameters() {
        float[] values = new float[42];
        for (int i = 0; i < values.length; i++) values[i] = i / 10f;
        return values;
    }

    private static final class Recorder implements BannerGlassState.Calls {
        final List<String> events = new ArrayList<>();
        String fail;
        boolean mutateParameters;
        float[] lastParameters;
        private void record(String event) throws Exception {
            events.add(event);
            if (event.equals(fail)) { fail = null; throw new Exception("injected " + event); }
        }
        @Override public void setParameters(float[] values) throws Exception {
            boolean zero = true;
            for (float value : values) zero &= value == 0;
            lastParameters = values.clone();
            record(zero ? "zero" : "params");
            if (mutateParameters) Arrays.fill(values, 1000f);
        }
        @Override public void setMaterialType(int type) throws Exception { record("type" + type); }
        @Override public void setSize(float width, float height) throws Exception {
            record("size" + (int) width + "x" + (int) height);
        }
    }

    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action) {
        try { action.run(); fail("expected rejection"); }
        catch (Exception expected) { assertNotNull(expected.getMessage()); }
    }

    @Test public void parsesFiniteResourceValuesWithoutChangingArray() {
        String[] values = new String[42];
        Arrays.fill(values, "0.25");
        assertEquals(0.25f, BannerGlassState.parseParameters(values)[41], 0f);
        assertEquals("0.25", values[41]);
    }

    @Test public void rejectsInvalidResourceValues() {
        rejects(() -> BannerGlassState.parseParameters(null));
        rejects(() -> BannerGlassState.parseParameters(new String[41]));
        for (String invalid : new String[]{null, "x", "NaN", "Infinity", "-Infinity"}) {
            String[] values = new String[42];
            Arrays.fill(values, "0");
            values[20] = invalid;
            rejects(() -> BannerGlassState.parseParameters(values));
        }
    }

    @Test public void zeroDimensionsDeferAllCalls() throws Exception {
        Recorder calls = new Recorder();
        BannerGlassState state = new BannerGlassState(calls);
        assertFalse(state.apply(parameters(), 0, 60));
        assertFalse(state.apply(parameters(), 100, 0));
        assertFalse(state.apply(parameters(), -1, 60));
        assertFalse(state.isActive());
        state.clear();
        assertTrue(calls.events.isEmpty());
    }

    @Test public void firstApplyUsesParametersSizeTypeOrder() throws Exception {
        Recorder calls = new Recorder();
        BannerGlassState state = new BannerGlassState(calls);
        assertTrue(state.apply(parameters(), 100, 60));
        assertTrue(state.isActive());
        assertEquals(Arrays.asList("type0", "params", "size100x60", "type1"), calls.events);
    }

    @Test public void equalParametersAndDimensionsAreIdempotent() throws Exception {
        Recorder calls = new Recorder();
        BannerGlassState state = new BannerGlassState(calls);
        state.apply(parameters(), 100, 60);
        calls.events.clear();
        state.apply(parameters(), 100, 60);
        state.resize(100, 60);
        assertTrue(calls.events.isEmpty());
    }

    @Test public void sizeChangesOnlyReplaySdf() throws Exception {
        Recorder calls = new Recorder();
        BannerGlassState state = new BannerGlassState(calls);
        state.apply(parameters(), 100, 60);
        calls.events.clear();
        state.apply(parameters(), 120, 80);
        state.resize(120, 80);
        assertEquals(Arrays.asList("size120x80"), calls.events);
    }

    @Test public void parameterChangeReplaysSizeEvenAtSameBounds() throws Exception {
        Recorder calls = new Recorder();
        BannerGlassState state = new BannerGlassState(calls);
        float[] values = parameters();
        state.apply(values, 100, 60);
        calls.events.clear();
        values[1] = 0.75f;
        state.apply(values, 100, 60);
        assertEquals(Arrays.asList("type0", "params", "size100x60", "type1"), calls.events);
    }

    @Test public void callerAndNativeCannotMutateCachedParameters() throws Exception {
        Recorder calls = new Recorder();
        calls.mutateParameters = true;
        BannerGlassState state = new BannerGlassState(calls);
        float[] values = parameters();
        state.apply(values, 100, 60);
        assertArrayEquals(parameters(), values, 0f);
        calls.events.clear();
        values[0] = 50f;
        state.apply(parameters(), 100, 60);
        assertTrue(calls.events.isEmpty());
    }

    @Test public void clearWritesZeroVectorAndType0Once() throws Exception {
        Recorder calls = new Recorder();
        BannerGlassState state = new BannerGlassState(calls);
        state.apply(parameters(), 100, 60);
        calls.events.clear();
        state.clear();
        state.clear();
        state.resize(120, 80);
        assertFalse(state.isActive());
        assertArrayEquals(new float[42], calls.lastParameters, 0f);
        assertEquals(Arrays.asList("zero", "type0"), calls.events);
    }

    @Test public void partialApplyFailuresRetainCleanupOwnership() throws Exception {
        for (String failure : new String[]{"params", "size100x60", "type1"}) {
            Recorder calls = new Recorder();
            BannerGlassState state = new BannerGlassState(calls);
            calls.fail = failure;
            rejects(() -> state.apply(parameters(), 100, 60));
            assertFalse(state.isActive());
            calls.events.clear();
            state.clear();
            assertEquals(Arrays.asList("zero", "type0"), calls.events);
        }
    }

    @Test public void initialTypeFailureStillAttemptsTypeCleanup() throws Exception {
        Recorder calls = new Recorder();
        BannerGlassState state = new BannerGlassState(calls);
        calls.fail = "type0";
        rejects(() -> state.apply(parameters(), 100, 60));
        calls.events.clear();
        state.clear();
        assertEquals(Arrays.asList("type0"), calls.events);
    }

    @Test public void failedParameterCleanupStillClearsTypeAndRetriesOnlyFailure() throws Exception {
        Recorder calls = new Recorder();
        BannerGlassState state = new BannerGlassState(calls);
        state.apply(parameters(), 100, 60);
        calls.events.clear();
        calls.fail = "zero";
        rejects(state::clear);
        assertFalse(state.isActive());
        assertEquals(Arrays.asList("zero", "type0"), calls.events);
        calls.events.clear();
        state.clear();
        assertEquals(Arrays.asList("zero"), calls.events);
    }

    @Test public void failedTypeCleanupRetainsOnlyTypeOwnership() throws Exception {
        Recorder calls = new Recorder();
        BannerGlassState state = new BannerGlassState(calls);
        state.apply(parameters(), 100, 60);
        calls.events.clear();
        calls.fail = "type0";
        rejects(state::clear);
        assertEquals(Arrays.asList("zero", "type0"), calls.events);
        calls.events.clear();
        state.clear();
        assertEquals(Arrays.asList("type0"), calls.events);
    }

    @Test public void resizeFailureCanBeRetriedAtSameDimensions() throws Exception {
        Recorder calls = new Recorder();
        BannerGlassState state = new BannerGlassState(calls);
        state.apply(parameters(), 100, 60);
        calls.events.clear();
        calls.fail = "size120x80";
        rejects(() -> state.resize(120, 80));
        state.resize(120, 80);
        assertEquals(Arrays.asList("size120x80", "size120x80"), calls.events);
    }

    @Test public void reapplyAfterClearInitializesAgain() throws Exception {
        Recorder calls = new Recorder();
        BannerGlassState state = new BannerGlassState(calls);
        state.apply(parameters(), 100, 60);
        state.clear();
        calls.events.clear();
        state.apply(parameters(), 100, 60);
        assertEquals(Arrays.asList("type0", "params", "size100x60", "type1"), calls.events);
    }

    @Test public void invalidParametersNeverReachNative() {
        Recorder calls = new Recorder();
        BannerGlassState state = new BannerGlassState(calls);
        rejects(() -> state.apply(new float[43], 100, 60));
        float[] values = parameters();
        values[2] = Float.NaN;
        rejects(() -> state.apply(values, 100, 60));
        assertTrue(calls.events.isEmpty());
    }
}
