/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class RomFocusVisibilityTest {
    private static final String CURRENT = "isCurrentProfile";
    private static final String AVAILABLE = "isProfileAvailable";
    private static final String PUBLIC = "isLockscreenPublicMode";
    private static final String HIDE = "shouldHideNotification";
    private static final String PROTECT = "shouldProtectNotification";
    private static final String FILTER = "shouldFilterOut";
    private static final String FAILED = "privacy-check-failed";
    private static final List<String> REQUIRED_FLAGS = Arrays.asList(CURRENT, AVAILABLE, PUBLIC, HIDE, PROTECT, FILTER);

    @Test public void os4AllowsOnlyAfterAllRequiredChecksReturnKnownValues() {
        Probe probe = new Probe();
        assertNull(check(true, false, probe));
        assertEquals(Arrays.asList("interactive", "userManager", "notificationUser", CURRENT + ":10",
                AVAILABLE + ":10", PUBLIC + ":10", "nativePolicy", HIDE, PROTECT, FILTER), probe.calls);
    }

    @Test public void os4KnownDenialsPreserveReasonsAndCanShortCircuit() {
        String[] reasons = {"other-user", "profile-unavailable", "profile-locked", "native-hidden",
                "screen-share-protected", "screen-share-protected"};
        for (int i = 0; i < reasons.length; i++) {
            Probe probe = new Probe();
            String method = REQUIRED_FLAGS.get(i);
            probe.values.put(method, i >= 2);
            assertEquals(method, reasons[i], check(true, false, probe));
            assertTrue(probe.errors.isEmpty());
            if (i < reasons.length - 1) assertFalse(method, probe.calls.contains(FILTER));
        }
        Probe locked = new Probe();
        assertEquals("device-locked", check(true, true, locked));
        assertEquals(Arrays.asList("interactive"), locked.calls);
    }

    @Test public void os4RejectsNullAndWrongReturnTypeForEveryRequiredFlag() {
        for (String method : REQUIRED_FLAGS) {
            for (Object invalid : new Object[]{null, "false", 0}) {
                Probe probe = new Probe();
                probe.values.put(method, invalid);
                assertEquals(method, FAILED, check(true, false, probe));
                assertEquals(method, 1, probe.errors.size());
                assertTrue(probe.errors.get(0).getMessage().contains(method));
            }
        }
    }

    @Test public void os4RejectsReflectionExceptionAndMissingMethod() {
        for (String method : REQUIRED_FLAGS) {
            for (Throwable failure : new Throwable[]{new IllegalStateException("ROM call failed"), new NoSuchMethodError(method)}) {
                Probe probe = new Probe();
                probe.failures.put(method, failure);
                assertEquals(method, FAILED, check(true, false, probe));
                assertEquals(method, Arrays.asList(failure), probe.errors);
            }
        }
    }

    @Test public void os4CannotAllowWhenRequiredPolicyChecksAreUnavailable() {
        Probe noUserManager = new Probe();
        noUserManager.userManager = false;
        assertEquals("user-policy-unavailable", check(true, false, noUserManager));
        assertEquals(Arrays.asList("interactive", "userManager"), noUserManager.calls);

        Probe noNativePolicy = new Probe();
        noNativePolicy.nativePolicy = false;
        assertEquals("native-privacy-policy-unavailable", check(true, false, noNativePolicy));
        assertEquals(Arrays.asList("interactive", "userManager", "notificationUser", CURRENT + ":10",
                AVAILABLE + ":10", PUBLIC + ":10", "nativePolicy"), noNativePolicy.calls);
    }

    @Test public void os4SamplesInteractiveOnceAndNeverTurnsSkippedNativeChecksIntoAllow() {
        Probe wakes = new Probe();
        wakes.interactive = false;
        assertEquals("screen-not-interactive", check(true, false, wakes));
        assertEquals(Arrays.asList("interactive"), wakes.calls);

        Probe sleeps = new Probe();
        assertNull(check(true, false, sleeps));
        assertEquals(1, sleeps.interactiveReads);
        assertTrue(sleeps.calls.contains(FILTER));
    }

    @Test public void os4ResolvesUserAllForAvailabilityAndPublicMode() {
        Probe probe = new Probe();
        probe.user = -1;
        probe.resolvedUser = 23;
        assertNull(check(true, false, probe));
        assertEquals(Arrays.asList("interactive", "userManager", "notificationUser", CURRENT + ":-1",
                "currentUser", AVAILABLE + ":23", PUBLIC + ":23", "nativePolicy", HIDE, PROTECT, FILTER), probe.calls);
    }

    @Test public void os4RejectsUnresolvedOrInvalidUsers() {
        for (Object invalid : new Object[]{null, "23", 23L, -1, -2}) {
            Probe probe = new Probe();
            probe.user = -1;
            probe.resolvedUser = invalid;
            assertEquals("notification-user-unavailable", check(true, false, probe));
            assertEquals(Arrays.asList("interactive", "userManager", "notificationUser", CURRENT + ":-1",
                    "currentUser"), probe.calls);
        }
        Probe missingField = new Probe();
        missingField.user = -1;
        Throwable failure = new NoSuchFieldError("mCurrentUserId");
        missingField.failures.put("currentUser", failure);
        assertEquals(FAILED, check(true, false, missingField));
        assertEquals(Arrays.asList(failure), missingField.errors);

        Probe invalidUser = new Probe();
        invalidUser.user = -2;
        assertEquals("notification-user-unavailable", check(true, false, invalidUser));
        assertEquals(Arrays.asList("interactive", "userManager", "notificationUser"), invalidUser.calls);
    }

    @Test public void os3RetainsLegacyPublicModeCoercionAndDoesNotCallOs4Checks() {
        for (Object legacyPublic : new Object[]{false, null, "unknown"}) {
            Probe probe = new Probe();
            probe.user = -1;
            probe.resolvedUser = 23;
            probe.nativePolicy = false;
            probe.values.put(PUBLIC, legacyPublic);
            assertNull(check(false, false, probe));
            assertEquals(Arrays.asList("userManager", "notificationUser", CURRENT + ":-1", "currentUser",
                    AVAILABLE + ":23", PUBLIC + ":23", "interactive"), probe.calls);
        }
        Probe knownPublic = new Probe();
        knownPublic.values.put(PUBLIC, true);
        assertEquals("profile-locked", check(false, false, knownPublic));
        Probe noProfile = new Probe();
        noProfile.values.put(CURRENT, null);
        assertEquals("other-user", check(false, false, noProfile));
        assertFalse(noProfile.calls.contains(AVAILABLE + ":10"));
    }

    private static String check(boolean os4, boolean locked, Probe probe) {
        RomFocusVisibility gate = new RomFocusVisibility(null, null, null, os4, () -> {}, new RomFocusVisibility.Logger() {
            @Override public void error(String stage, Throwable error) { probe.errors.add(error); }
            @Override public void log(String message) {}
        });
        return gate.blocked("fixture", locked, probe);
    }

    private static final class Probe implements RomFocusVisibility.Checks {
        final List<String> calls = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        final Map<String, Object> values = new HashMap<>();
        final Map<String, Throwable> failures = new HashMap<>();
        boolean interactive = true;
        int interactiveReads;
        boolean userManager = true;
        boolean nativePolicy = true;
        int user = 10;
        Object resolvedUser = 0;

        @Override public boolean interactive() {
            calls.add("interactive");
            return interactiveReads++ == 0 ? interactive : !interactive;
        }
        @Override public boolean hasUserManager() { calls.add("userManager"); return userManager; }
        @Override public int notificationUser() { calls.add("notificationUser"); return user; }
        @Override public Object currentUser() { return value("currentUser", "currentUser", resolvedUser); }
        @Override public Object profile(String method, int user) {
            return value(method, method + ":" + user, !PUBLIC.equals(method));
        }
        @Override public boolean hasNativePolicy() { calls.add("nativePolicy"); return nativePolicy; }
        @Override public Object shouldHide() { return value(HIDE, HIDE, false); }
        @Override public Object shouldProtect() { return value(PROTECT, PROTECT, false); }
        @Override public Object shouldFilterOut() { return value(FILTER, FILTER, false); }

        private Object value(String method, String call, Object fallback) {
            calls.add(call);
            Throwable failure = failures.get(method);
            if (failure instanceof Error) throw (Error) failure;
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            return values.getOrDefault(method, fallback);
        }
    }
}
