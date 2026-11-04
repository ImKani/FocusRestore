/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;
public class NativeChipPolicyTest {
    @Test public void importantAndDuplicateOnlyYieldWhenNativeChipsAreAllowed() {
        assertFalse(new NativeChipPolicy(true,true,true,Collections.emptySet()).allows("focus"));
        assertFalse(new NativeChipPolicy(true,true,false,Collections.singleton("focus")).allows("focus"));
        assertTrue(new NativeChipPolicy(true,true,false,Collections.singleton("other")).allows("focus"));
        assertTrue(new NativeChipPolicy(true,false,true,Collections.singleton("focus")).allows("focus"));
        assertFalse(NativeChipPolicy.unknown().allows("focus"));
    }
    @Test public void stableSnapshotsDoNotRequireRepeatedRendering() {
        assertEquals(new NativeChipPolicy(true,true,false,Collections.singleton("k")),
                new NativeChipPolicy(true,true,false,Collections.singleton("k")));
        assertNotEquals(NativeChipPolicy.unknown(),new NativeChipPolicy(true,false,false,Collections.emptySet()));
    }
}
