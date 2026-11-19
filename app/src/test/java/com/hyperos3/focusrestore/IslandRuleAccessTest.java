/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.*;

public class IslandRuleAccessTest {
    private static final String UI = "com.android.systemui";
    private static final int UID = 10039;
    private static final String[] PACKAGES = {UI};

    @Test public void acceptsOnlyAuthenticatedForegroundSystemUi() {
        assertTrue(IslandRuleAccess.allows(UID, UID, true, true, UI, PACKAGES, true));
        assertFalse(IslandRuleAccess.allows(1000, UID, true, true, UI, PACKAGES, true));
        assertFalse(IslandRuleAccess.allows(UID + 1, UID, true, true, UI, PACKAGES, true));
        assertFalse(IslandRuleAccess.allows(UID, UID, true, true, UI, PACKAGES, false));
    }

    @Test public void matchingPackageOrSignatureCannotAuthorizeOtherUsers() {
        assertFalse(IslandRuleAccess.allows(UID, UID, false, true, UI, PACKAGES, true));
        assertFalse(IslandRuleAccess.allows(UID, UID, true, false, UI, PACKAGES, true));
    }

    @Test public void captureAcceptsThePlatformSharedUidWithoutExtendingRuleAccess() {
        String[] shared = {"android", "com.android.settings", UI, "miui.systemui.plugin"};
        assertTrue(IslandRuleAccess.allowsCaptureRegistration(1000, 1000, true, true, UI, shared, true, true));
        assertFalse(IslandRuleAccess.allows(1000, 1000, true, true, UI, shared, true));
        assertTrue(IslandRuleAccess.allowsCaptureRegistration(UID, UID, true, true, UI, PACKAGES, true, false));
    }

    @Test public void captureRequiresBothPlatformMembersAndTheExactSystemUid() {
        String[] shared = {UI, "android"};
        assertFalse(IslandRuleAccess.allowsCaptureRegistration(UID, UID, true, true, UI, shared, true, false));
        assertFalse(IslandRuleAccess.allowsCaptureRegistration(1000, 1000, true, true, UI, new String[]{UI, "other"}, true, true));
        assertFalse(IslandRuleAccess.allowsCaptureRegistration(1000, 1000, true, true, UI, new String[]{"android", "other"}, true, true));
        assertFalse(IslandRuleAccess.allowsCaptureRegistration(1000, 1000, true, true, UI, null, true, true));
        assertFalse(IslandRuleAccess.allowsCaptureRegistration(1000, 1000, true, true, UI, new String[0], true, true));
    }

    @Test public void captureRejectsMissingCallerEvidenceAndIneligibleUsers() {
        String[] shared = {UI, "android"};
        assertFalse(IslandRuleAccess.allowsCaptureRegistration(1000, UID, true, true, UI, shared, true, true));
        assertFalse(IslandRuleAccess.allowsCaptureRegistration(1000, 1000, true, true, "android", shared, true, true));
        assertFalse(IslandRuleAccess.allowsCaptureRegistration(1000, 1000, true, true, null, shared, true, true));
        assertFalse(IslandRuleAccess.allowsCaptureRegistration(1000, 1000, true, true, UI, shared, false, true));
        assertFalse(IslandRuleAccess.allowsCaptureRegistration(1000, 1000, false, true, UI, shared, true, true));
        assertFalse(IslandRuleAccess.allowsCaptureRegistration(1000, 1000, true, false, UI, shared, true, true));
    }

    @Test public void ambiguousOrMissingAttributionAndSharedIdsFailClosed() {
        assertFalse(IslandRuleAccess.allows(UID, UID, true, true, null, PACKAGES, true));
        assertFalse(IslandRuleAccess.allows(UID, UID, true, true, "android", PACKAGES, true));
        assertFalse(IslandRuleAccess.allows(UID, UID, true, true, UI, null, true));
        assertFalse(IslandRuleAccess.allows(UID, UID, true, true, UI, new String[]{UI, "android"}, true));
    }
}
