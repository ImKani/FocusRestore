package com.hyperos3.focusrestore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Covers the shared extras-key policy used by both the hook and the native banner renderer.
 *
 * <p>Regression source: {@code com.miui.personalassistant} publishes its island payload under
 * {@code miui.focus.param.custom}, which used to make the native banner fail with
 * "notification has no native V3 focus parameters".
 */
public class FocusParamKeysTest {
    @Test
    public void prefersPrimaryKeyAndFallsBackToCustomSuffix() {
        assertEquals("a", FocusParamKeys.pick(
                key -> FocusParamKeys.PRIMARY.equals(key) ? "a" : "b"));
        assertEquals("b", FocusParamKeys.pick(
                key -> FocusParamKeys.CUSTOM.equals(key) ? "b" : null));
        assertEquals("b", FocusParamKeys.pick(
                key -> FocusParamKeys.CUSTOM.equals(key) ? "  b  " : null));
        assertEquals(FocusParamKeys.PRIMARY, FocusParamKeys.pickKey(
                key -> FocusParamKeys.PRIMARY.equals(key) ? "a" : null));
        assertEquals(FocusParamKeys.CUSTOM, FocusParamKeys.pickKey(
                key -> FocusParamKeys.CUSTOM.equals(key) ? "b" : null));
    }

    @Test
    public void ignoresBlankMissingAndNonStringValues() {
        assertNull(FocusParamKeys.pick(null));
        assertNull(FocusParamKeys.pick(key -> null));
        assertNull(FocusParamKeys.pick(key -> "   "));
        assertNull(FocusParamKeys.pick(key -> Integer.valueOf(42)));
        assertEquals("ok", FocusParamKeys.pick(
                key -> FocusParamKeys.CUSTOM.equals(key) ? "ok" : Integer.valueOf(1)));
        // A non-string primary must not stop the custom fallback from being read.
        assertEquals("custom", FocusParamKeys.pick(key -> FocusParamKeys.PRIMARY.equals(key)
                ? Integer.valueOf(7) : "custom"));
        assertEquals(FocusParamKeys.PRIMARY, FocusParamKeys.pickKey(key -> Integer.valueOf(7)));
    }

    @Test
    public void keyOrderKeepsPrimaryFirst() {
        assertEquals(2, FocusParamKeys.ORDER.length);
        assertEquals(FocusParamKeys.PRIMARY, FocusParamKeys.ORDER[0]);
        assertEquals(FocusParamKeys.CUSTOM, FocusParamKeys.ORDER[1]);
    }

    @Test
    public void describeReportsPresenceTypeLengthAndPick() {
        assertEquals("miui.focus.param=absent miui.focus.param.custom=absent "
                        + "picked=miui.focus.param",
                FocusParamKeys.describe(null));
        assertEquals("miui.focus.param=string:3 miui.focus.param.custom=absent "
                        + "picked=miui.focus.param",
                FocusParamKeys.describe(
                        key -> FocusParamKeys.PRIMARY.equals(key) ? "abc" : null));
        assertEquals("miui.focus.param=blank miui.focus.param.custom=type:Integer "
                        + "picked=miui.focus.param",
                FocusParamKeys.describe(key -> FocusParamKeys.PRIMARY.equals(key)
                        ? "   " : Integer.valueOf(5)));
        // Reports the custom key once it is the only non-empty candidate.
        assertEquals("miui.focus.param=absent miui.focus.param.custom=string:7 "
                        + "picked=miui.focus.param.custom",
                FocusParamKeys.describe(key -> FocusParamKeys.CUSTOM.equals(key) ? "  payload " : null));
    }

    @Test
    public void describeNeverThrowsWhenTheValueSourceDoes() {
        FocusParamKeys.ValueSource broken = key -> {
            throw new IllegalStateException("boom");
        };
        assertEquals("miui.focus.param=error miui.focus.param.custom=error picked=error",
                FocusParamKeys.describe(broken));
    }
}
