/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import de.robv.android.xposed.XC_MethodHook;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HookInstallerTest {
    private static final class OverloadedMethods {
        void target() { }
        void target(String value) { }
        void other() { }
    }

    @Test public void optionalAllMethodsTreatsNoMatchAsAbsentAndStrictPathStillFails() {
        HookInstaller installer = new HookInstaller();
        // 零匹配路径不能触及 Xposed 后端；其 API jar 的构造器在纯 JVM 中只会抛 Stub!。
        XC_MethodHook callback = null;

        assertTrue(installer.optionalAllMethods(OverloadedMethods.class, "missing", callback).isEmpty());
        assertTrue(installer.optionalAllMethods(null, "target", callback).isEmpty());
        assertEquals(0, installer.count());
        assertEquals(0, installer.failedCount());

        try {
            installer.allMethods(OverloadedMethods.class, "missing", callback);
            fail("strict allMethods must reject a missing target");
        } catch (IllegalStateException expected) {
            assertEquals(1, installer.failedCount());
        }
    }

    @Test public void namedInstallerVisitsAllOverloadsAndReusesSuccessfulRegistrations() {
        HookRegistration<Object> registrations = new HookRegistration<>();
        java.util.function.Function<Method, Object> install = method ->
                registrations.obtain(method, Object::new);

        Set<Object> first = HookInstaller.installDeclaredMethods(
                OverloadedMethods.class, "target", install);
        Set<Object> second = HookInstaller.installDeclaredMethods(
                OverloadedMethods.class, "target", install);

        assertEquals(2, first.size());
        assertEquals(first, second);
        assertEquals(2, registrations.count());
        assertEquals(0, registrations.failedCount());
    }

    @Test public void missingNameDoesNotCallInstallerOrPreventLaterName() {
        int[] calls = {0};
        java.util.function.Function<Method, Object> install = method -> {
            calls[0]++;
            return new Object();
        };

        assertTrue(HookInstaller.installDeclaredMethods(
                OverloadedMethods.class, "missing", install).isEmpty());
        assertEquals(0, calls[0]);
        assertFalse(HookInstaller.installDeclaredMethods(
                OverloadedMethods.class, "other", install).isEmpty());
        assertEquals(1, calls[0]);
    }
}
