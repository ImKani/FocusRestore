/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeSmsVerificationPolicyTest {
    private static final String PAYLOAD = "{\"protocol\":1,\"scene\":\"verifyCode\"}";

    @Test public void acceptsOnlyVerifiedSmsContract() {
        assertTrue(NativeSmsVerificationPolicy.accepts("com.android.mms", PAYLOAD));
        assertFalse(NativeSmsVerificationPolicy.accepts("other.package", PAYLOAD));
        assertFalse(NativeSmsVerificationPolicy.accepts(null, PAYLOAD));
    }

    @Test public void rejectsOtherProtocolsAndScenes() {
        assertFalse(NativeSmsVerificationPolicy.accepts("com.android.mms", "{\"protocol\":2,\"scene\":\"verifyCode\"}"));
        assertFalse(NativeSmsVerificationPolicy.accepts("com.android.mms", "{\"protocol\":1,\"scene\":\"sms\"}"));
        assertFalse(NativeSmsVerificationPolicy.accepts("com.android.mms", "{\"scene\":\"verifyCode\"}"));
        assertFalse(NativeSmsVerificationPolicy.accepts("com.android.mms", "{\"protocol\":\"1\",\"scene\":\"verifyCode\"}"));
        assertFalse(NativeSmsVerificationPolicy.accepts("com.android.mms", "{\"protocol\":1.5,\"scene\":\"verifyCode\"}"));
    }

    @Test public void newProtocolContainersNeverFallBackToLegacy() {
        for (String key : new String[]{"param_v2", "param_voip_v2"}) {
            for (String content : new String[]{"{}", "null", "\"invalid\""}) {
                assertFalse(NativeSmsVerificationPolicy.accepts("com.android.mms",
                        PAYLOAD.substring(0, PAYLOAD.length() - 1) + ",\"" + key + "\":" + content + "}"));
            }
        }
    }

    @Test public void rejectsMalformedAndOversizedPayloads() {
        assertFalse(NativeSmsVerificationPolicy.accepts("com.android.mms", null));
        assertFalse(NativeSmsVerificationPolicy.accepts("com.android.mms", ""));
        assertFalse(NativeSmsVerificationPolicy.accepts("com.android.mms", "{"));
        StringBuilder oversized = new StringBuilder(PAYLOAD);
        for (int i = 0; i < 300000; i++) oversized.append(' ');
        assertFalse(NativeSmsVerificationPolicy.accepts("com.android.mms", oversized.toString()));
    }
}
