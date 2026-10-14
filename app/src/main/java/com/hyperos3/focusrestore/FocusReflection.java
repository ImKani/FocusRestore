/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Small compatibility layer for optional private SystemUI APIs. */
final class FocusReflection {
    private FocusReflection() {
    }

    static Class<?> findClass(ClassLoader loader, String name) {
        try {
            return XposedHelpers.findClass(name, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Class<?> findClass(String name, ClassLoader loader) {
        return findClass(loader, name);
    }

    static Class<?> findFirstClass(ClassLoader loader, String... names) {
        for (String name : names) {
            Class<?> value = findClass(loader, name);
            if (value != null) return value;
        }
        return null;
    }

    static boolean hasMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        if (owner == null) return false;
        try {
            owner.getDeclaredMethod(name, parameterTypes);
            return true;
        } catch (Throwable ignored) {
            try {
                owner.getMethod(name, parameterTypes);
                return true;
            } catch (Throwable ignoredAgain) {
                return false;
            }
        }
    }

    static boolean hasField(Class<?> owner, String name) {
        if (owner == null) return false;
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 取 ROM 私有字段用于诊断读取，找不到时返回 null 由调用方降级。
     *
     * <p>只沿当前类与被 hook 的类自身查找，不做父类回溯：诊断失败必须安静降级，不能因此影响
     * 手势与抑制逻辑。
     */
    static Field findField(Class<?> owner, String name) {
        if (owner == null || name == null) return null;
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String capability(Class<?> owner, String name, Class<?>... parameterTypes) {
        boolean available = parameterTypes.length == 0 ? hasAnyMethod(owner, name)
                : hasMethod(owner, name, parameterTypes);
        return name + "=" + (available ? "available" : "missing");
    }

    private static boolean hasAnyMethod(Class<?> owner, String name) {
        if (owner == null) return false;
        try {
            for (Method method : owner.getDeclaredMethods()) {
                if (name.equals(method.getName())) return true;
            }
            for (Method method : owner.getMethods()) {
                if (name.equals(method.getName())) return true;
            }
            return false;
        } catch (Throwable throwable) {
            XposedBridge.log("HyperOS3FocusRestore ERROR capabilityProbe "
                    + owner.getName() + "#" + name + ": " + throwable);
            XposedBridge.log(throwable);
            return false;
        }
    }
}