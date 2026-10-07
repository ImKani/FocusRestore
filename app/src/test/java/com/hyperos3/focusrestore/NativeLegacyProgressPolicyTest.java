/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeLegacyProgressPolicyTest {
    @Test public void acceptsBothRomProgressScenes() {
        assertTrue(NativeLegacyProgressPolicy.accepts("{\"protocol\":1,\"scene\":\"foodDelivery\",\"progress\":74,\"progressCount\":2}"));
        assertTrue(NativeLegacyProgressPolicy.accepts("{\"protocol\":1,\"scene\":\"templateRevertProgressScene\"}"));
    }

    @Test public void rejectsOtherTemplatesAndProtocols() {
        for (String scene : new String[]{"verifyCode", "carHailing", "timer", "fooddelivery", ""}) {
            assertFalse(NativeLegacyProgressPolicy.accepts("{\"protocol\":1,\"scene\":\"" + scene + "\"}"));
        }
        for (String protocol : new String[]{"2", "1.5", "\"1\"", "null", "true"}) {
            assertFalse(NativeLegacyProgressPolicy.accepts("{\"protocol\":" + protocol + ",\"scene\":\"foodDelivery\"}"));
        }
        assertFalse(NativeLegacyProgressPolicy.accepts("{\"scene\":\"foodDelivery\"}"));
    }

    @Test public void newContainersNeverFallBackToProgress() {
        for (String key : new String[]{"param_v2", "param_voip_v2"}) {
            for (String value : new String[]{"{}", "null", "\"invalid\""}) {
                assertFalse(NativeLegacyProgressPolicy.accepts(
                        "{\"protocol\":1,\"scene\":\"foodDelivery\",\"" + key + "\":" + value + "}"));
            }
        }
    }

    @Test public void rejectsInvalidAndOversizedPayloads() {
        assertFalse(NativeLegacyProgressPolicy.accepts(null));
        assertFalse(NativeLegacyProgressPolicy.accepts(""));
        assertFalse(NativeLegacyProgressPolicy.accepts("{"));
        StringBuilder payload = new StringBuilder("{\"protocol\":1,\"scene\":\"foodDelivery\"}");
        for (int i = 0; i < 300000; i++) payload.append(' ');
        assertFalse(NativeLegacyProgressPolicy.accepts(payload.toString()));
    }

    @Test public void progressDoesNotExpandSmsTakeoverScope() {
        assertFalse(NativeSmsVerificationPolicy.accepts("com.android.mms", "{\"protocol\":1,\"scene\":\"foodDelivery\"}"));
        assertTrue(NativeSmsVerificationPolicy.accepts("com.android.mms", "{\"protocol\":1,\"scene\":\"verifyCode\"}"));
    }
}
