/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;
import org.junit.Test;
import static org.junit.Assert.*;
public class FocusVisibilityPolicyTest {
    @Test public void allowsOnlyInteractiveUnlockedCurrentAvailableUnprotectedProfiles() {
        assertNull(FocusVisibilityPolicy.blocked(true,false,true,true,true,false,false,false));
        for (int bit=0;bit<8;bit++) {
            boolean[] state={true,false,true,true,true,false,false,false};
            state[bit]=!state[bit];
            assertNotNull("required boundary " + bit, FocusVisibilityPolicy.blocked(
                    state[0],state[1],state[2],state[3],state[4],state[5],state[6],state[7]));
        }
    }
    @Test public void unknownPolicyCannotBeOverriddenByNativeFocusOrManualSelection() {
        assertEquals("user-policy-unavailable",FocusVisibilityPolicy.blocked(true,false,false,true,true,false,false,false));
        assertEquals("profile-locked",FocusVisibilityPolicy.blocked(true,false,true,true,true,true,false,false));
        assertEquals("screen-share-protected",FocusVisibilityPolicy.blocked(true,false,true,true,true,false,false,true));
    }
}
