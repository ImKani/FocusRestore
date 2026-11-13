/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** 每个安装器拥有自己的句柄，不改变其他功能对同一方法的独立Hook。 */
final class HookInstaller {
    private final HookRegistration<XC_MethodHook.Unhook> registrations = new HookRegistration<>();
    private final Set<Member> targets = new LinkedHashSet<>();

    synchronized XC_MethodHook.Unhook method(Member target, XC_MethodHook callback) {
        // Class对象参与身份，避免插件重载后相同类名沿用旧ClassLoader的句柄。
        Object identity = Arrays.asList(target, callback.getClass());
        XC_MethodHook.Unhook handle = registrations.obtain(identity,
                () -> XposedBridge.hookMethod(target, callback));
        targets.add(target);
        return handle;
    }

    XC_MethodHook.Unhook exact(Class<?> owner, String name, Object... parametersAndCallback) {
        if (owner == null) throw missing("missing class for " + name);
        XC_MethodHook callback = (XC_MethodHook) parametersAndCallback[parametersAndCallback.length - 1];
        String key = owner.getName() + "#" + name;
        Method target;
        try {
            target = XposedHelpers.findMethodExact(owner, name,
                    Arrays.copyOf(parametersAndCallback, parametersAndCallback.length - 1));
        } catch (Throwable failure) {
            registrations.missing(key);
            throw new IllegalStateException("missing exact Hook " + key, failure);
        }
        XC_MethodHook.Unhook handle = method(target, callback);
        registrations.resolved(key);
        return handle;
    }

    XC_MethodHook.Unhook exact(String owner, ClassLoader loader, String name, Object... parametersAndCallback) {
        return exact(XposedHelpers.findClass(owner, loader), name, parametersAndCallback);
    }

    Set<XC_MethodHook.Unhook> allMethods(Class<?> owner, String name, XC_MethodHook callback) {
        if (owner == null) throw missing("missing class for " + name);
        String key = owner.getName() + "#" + name;
        Set<XC_MethodHook.Unhook> handles = installDeclaredMethods(owner, name,
                target -> method(target, callback));
        if (handles.isEmpty()) throw missing(key);
        registrations.resolved(key);
        return handles;
    }

    Set<XC_MethodHook.Unhook> optionalAllMethods(Class<?> owner, String name,
                                                  XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> handles = installDeclaredMethods(owner, name,
                target -> method(target, callback));
        if (!handles.isEmpty()) registrations.resolved(owner.getName() + "#" + name);
        return handles;
    }

    static <T> Set<T> installDeclaredMethods(Class<?> owner, String name,
                                              Function<Method, T> installer) {
        Set<T> handles = new LinkedHashSet<>();
        if (owner == null) return handles;
        for (Method target : owner.getDeclaredMethods()) {
            if (target.getName().equals(name)) handles.add(installer.apply(target));
        }
        return handles;
    }

    Set<XC_MethodHook.Unhook> constructors(Class<?> owner, XC_MethodHook callback) {
        if (owner == null) throw missing("missing constructor class");
        Set<XC_MethodHook.Unhook> handles = new LinkedHashSet<>();
        for (Constructor<?> target : owner.getDeclaredConstructors()) handles.add(method(target, callback));
        if (handles.isEmpty()) throw missing(owner.getName() + "#<init>");
        return handles;
    }

    private IllegalStateException missing(String target) {
        registrations.missing(target);
        return new IllegalStateException("Hook matched zero targets: " + target);
    }

    synchronized boolean has(String owner, String name) {
        for (Member target : targets) {
            if (target.getDeclaringClass().getName().equals(owner)
                    && (target.getName().equals(name) || (target instanceof Constructor && "<init>".equals(name)))) return true;
        }
        return false;
    }
    int count() { return registrations.count(); }
    int failedCount() { return registrations.failedCount(); }
    String failures() { return registrations.failures(); }
}
