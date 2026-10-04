/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Prevent a delayed native inflate from repainting a different notification generation. */
public class NativeFocusSourceIdentityTest {
    private static final String KEY = "0|com.example.delivery|10|null|10123";
    private static final String PARAM = "{\"param_v2\":{\"title\":\"Arriving\"}}";

    private NativeFocusSourceIdentity source(long time, String param) {
        return new NativeFocusSourceIdentity(KEY, "com.example.delivery", 10123, 0, time, param);
    }

    @Test public void binderCloneWithSameGenerationCanUseCapturedTemplate() {
        assertTrue(source(1234L, new String(PARAM)).matches(source(1234L, new String(PARAM))));
    }

    @Test public void delayedInflationCannotOverwriteNewerSameKeyNotification() {
        assertFalse(source(1234L, PARAM).matches(source(1235L, PARAM)));
        assertFalse(source(1235L, PARAM).matches(source(1234L, PARAM)));
    }

    @Test public void payloadChangeIsRejectedEvenIfProducerReusesPostTime() {
        assertFalse(source(1234L, PARAM).matches(source(1234L,
                "{\"param_v2\":{\"title\":\"Delivered\"}}")));
    }

    @Test public void workProfileAndReinstalledAppCannotShareTemplate() {
        NativeFocusSourceIdentity captured = source(1234L, PARAM);
        assertFalse(captured.matches(new NativeFocusSourceIdentity(KEY, "com.example.delivery",
                10123, 10, 1234L, PARAM)));
        assertFalse(captured.matches(new NativeFocusSourceIdentity(KEY, "com.example.delivery",
                10124, 0, 1234L, PARAM)));
    }

    @Test public void malformedOrMismatchedSourceCannotBeAcceptedByKeyOnly() {
        NativeFocusSourceIdentity captured = source(1234L, PARAM);
        assertFalse(captured.matches(null));
        assertFalse(captured.matches(new NativeFocusSourceIdentity("different-key",
                "com.example.delivery", 10123, 0, 1234L, PARAM)));
        assertFalse(captured.matches(new NativeFocusSourceIdentity(KEY,
                "com.example.other", 10123, 0, 1234L, PARAM)));
        assertFalse(new NativeFocusSourceIdentity(null, "com.example.delivery", 10123,
                0, 1234L, PARAM).matches(captured));
        assertFalse(new NativeFocusSourceIdentity(KEY, "", 10123,
                0, 1234L, PARAM).matches(captured));
        assertFalse(source(1234L, null).matches(source(1234L, null)));
        assertFalse(source(1234L, "").matches(source(1234L, "")));
    }

    @Test public void timestampComparisonRetainsFullLongPrecision() {
        long first = 1_800_000_000_000L;
        assertTrue(source(first, PARAM).matches(source(first, PARAM)));
        assertFalse(source(first, PARAM).matches(source(first + (1L << 32), PARAM)));
    }
}