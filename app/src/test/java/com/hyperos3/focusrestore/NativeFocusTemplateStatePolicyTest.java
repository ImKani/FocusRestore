/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Exercises source/map/builder state transitions without Android or ROM classes. */
public class NativeFocusTemplateStatePolicyTest {
    private NativeFocusTemplateStatePolicy.Rejection evaluate(
            boolean currentSource, boolean loaderConnected, boolean generationMatches,
            boolean removed, boolean templateMatches, Object observedBuilder, Object currentBuilder) {
        return NativeFocusTemplateStatePolicy.evaluate(currentSource, loaderConnected,
                generationMatches, removed, templateMatches, observedBuilder, currentBuilder);
    }

    @Test public void replacedTemplateMapRejectsCapturedSource() {
        assertEquals(NativeFocusTemplateStatePolicy.Rejection.TEMPLATE_CHANGED,
                evaluate(true, true, true, false, false, new Object(), null));
    }

    @Test public void clearedSharedBuilderIsAllowedWhileTemplateAndSourceRemainCurrent() {
        Object observedBuilder = new Object();
        assertEquals(NativeFocusTemplateStatePolicy.Rejection.NONE,
                evaluate(true, true, true, false, true, observedBuilder, null));
    }

    @Test public void currentBuilderMustRemainTheCapturedObject() {
        Object observedBuilder = new Object();
        assertEquals(NativeFocusTemplateStatePolicy.Rejection.NONE,
                evaluate(true, true, true, false, true, observedBuilder, observedBuilder));
        assertEquals(NativeFocusTemplateStatePolicy.Rejection.BUILDER_CHANGED,
                evaluate(true, true, true, false, true, observedBuilder, new Object()));
    }

    @Test public void appearedBuilderIsRejectedWhenWeakCaptureWasReleased() {
        assertEquals(NativeFocusTemplateStatePolicy.Rejection.BUILDER_CHANGED,
                evaluate(true, true, true, false, true, null, new Object()));
    }

    @Test public void removalAndNewNotificationGenerationAreDistinctRejections() {
        Object builder = new Object();
        assertEquals(NativeFocusTemplateStatePolicy.Rejection.NOTIFICATION_REMOVED,
                evaluate(true, true, true, true, true, builder, null));
        assertEquals(NativeFocusTemplateStatePolicy.Rejection.NOTIFICATION_GENERATION_CHANGED,
                evaluate(true, true, false, false, true, builder, null));
    }

    @Test public void replacedSourceAndDisconnectedLoaderAreRejected() {
        Object builder = new Object();
        assertEquals(NativeFocusTemplateStatePolicy.Rejection.SOURCE_CHANGED,
                evaluate(false, true, true, false, true, builder, null));
        assertEquals(NativeFocusTemplateStatePolicy.Rejection.LOADER_DISCONNECTED,
                evaluate(true, false, true, false, true, builder, null));
    }
}
