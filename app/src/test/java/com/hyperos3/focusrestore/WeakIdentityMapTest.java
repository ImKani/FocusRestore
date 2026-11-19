/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;

import java.util.WeakHashMap;

import static org.junit.Assert.*;

public class WeakIdentityMapTest {
    private static final class Bean {
        int content;

        Bean(int content) { this.content = content; }

        @Override public int hashCode() { return content; }

        @Override public boolean equals(Object other) {
            return other instanceof Bean && content == ((Bean) other).content;
        }
    }

    @Test public void sameWriterKeepsItsStateAcrossBodyMutations() {
        Bean writer = new Bean(1);
        Object original = new Object();
        WeakHashMap<Bean, Object> oldIndex = new WeakHashMap<>();
        oldIndex.put(writer, original);
        WeakIdentityMap<Object> states = new WeakIdentityMap<>();
        states.put(writer, original);
        writer.content = 2;
        assertNull(oldIndex.get(writer));
        assertSame(original, states.get(writer));
        Object refreshed = new Object();
        states.put(writer, refreshed);
        writer.content = 1;
        assertSame(refreshed, states.get(writer));
        states.remove(writer);
        writer.content = 2;
        assertNull(states.get(writer));
    }

    @Test public void equalViewCopyKeepsSeparateStateAndConversionMarker() {
        Bean writer = new Bean(7);
        Bean copy = new Bean(7);
        assertEquals(writer, copy);
        WeakIdentityMap<String> states = new WeakIdentityMap<>();
        WeakIdentityMap<Boolean> converted = new WeakIdentityMap<>();
        states.put(writer, "writer state");
        converted.put(writer, true);
        assertNull(states.get(copy));
        assertFalse(converted.containsKey(copy));
        states.put(copy, "copy state");
        converted.remove(copy);
        assertTrue(converted.containsKey(writer));
        converted.put(copy, true);
        states.remove(writer);
        converted.remove(writer);
        converted.remove(writer);
        assertNull(states.get(writer));
        assertFalse(converted.containsKey(writer));
        assertEquals("copy state", states.get(copy));
        assertTrue(converted.containsKey(copy));
        assertFalse(converted.containsKey(null)); // ROM setData 清空时传入空 Bean。
    }
}
