/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;
import org.junit.Test;
import static org.junit.Assert.*;

public class HookRegistrationTest {
    @Test public void repeatedInstallKeepsOriginalHandleAndDoesNotInvokeBackend() {
        HookRegistration<Object> registry = new HookRegistration<>();
        Object handle = new Object();
        assertSame(handle, registry.obtain("first", () -> handle));
        assertSame(handle, registry.obtain("first", () -> { throw new AssertionError("duplicate"); }));
        assertEquals(1, registry.count());
    }
    @Test public void partialFailureRetriesOnlyMissingRegistration() {
        HookRegistration<Object> registry = new HookRegistration<>();
        Object installed = registry.obtain("ready", Object::new);
        try { registry.obtain("late", () -> { throw new IllegalStateException("not ready"); }); fail(); }
        catch (IllegalStateException expected) { assertEquals(1, registry.failedCount()); }
        assertSame(installed, registry.obtain("ready", () -> { throw new AssertionError("reinstalled"); }));
        assertNotNull(registry.obtain("late", Object::new));
        assertEquals(2, registry.count());
        assertEquals(0, registry.failedCount());
    }
    @Test public void missingOrNullHandleNeverCountsAsInstalled() {
        HookRegistration<Object> registry = new HookRegistration<>();
        registry.missing("method");
        try { registry.obtain("method", () -> null); fail(); }
        catch (IllegalStateException expected) { assertEquals(0, registry.count()); }
        assertEquals(1, registry.failedCount());
        registry.obtain("method", Object::new);
        assertEquals(0, registry.failedCount());
    }
}
