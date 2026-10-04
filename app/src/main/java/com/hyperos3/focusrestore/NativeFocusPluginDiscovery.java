/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Observes the real plugin loader before Plugin.onCreate; never loads another APK instance. */
final class NativeFocusPluginDiscovery {
    interface Listener {
        void onLoader(ClassLoader loader);
        void onUnloaded(ClassLoader loader);
        void log(String message);
        void error(String stage, Throwable error);
    }

    private final Listener listener;

    NativeFocusPluginDiscovery(Listener listener) {
        this.listener = listener;
    }

    void install(ClassLoader systemUiLoader) {
        Class<?> factory = FocusReflection.findClass(systemUiLoader,
                "com.android.systemui.shared.plugins.PluginInstance$PluginFactory");
        if (factory == null) {
            listener.log("native plugin discovery unavailable: PluginFactory missing");
            return;
        }
        // OS4: createPlugin() obtains this loader before loading/constructing the plugin class.
        int loaderHooks = hook(factory, "createClassLoader", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || !isFocusPlugin(param.thisObject)) return;
                notifyLoader(param.getResult(), "createClassLoader");
            }
        });
        // Both verified ROMs create the plugin Context before calling Plugin.onCreate().
        int contextHooks = hook(factory, "createPluginContext", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || !isFocusPlugin(param.thisObject)) return;
                Object value = param.getResult();
                if (value instanceof Context) {
                    notifyLoader(((Context) value).getClassLoader(), "createPluginContext");
                }
            }
        });
        // OS3's loader is a Supplier rather than a named method on PluginFactory.
        Class<?> supplier = FocusReflection.findClass(systemUiLoader,
                "com.android.systemui.shared.plugins.PluginInstance$Factory$$ExternalSyntheticLambda0");
        int supplierHooks = hook(supplier, "get", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || !isFocusPlugin(param.thisObject)) return;
                notifyLoader(param.getResult(), "OS3-loader-supplier");
            }
        });
        Class<?> instance = FocusReflection.findClass(systemUiLoader,
                "com.android.systemui.shared.plugins.PluginInstance");
        int unloadHooks = hook(instance, "unloadPlugin", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object pluginFactory = field(param.thisObject, "pluginFactory", "mPluginFactory");
                    if (!isFocusPlugin(pluginFactory)) return;
                    Object context = field(param.thisObject, "mPluginContext");
                    if (!(context instanceof Context)) {
                        Object data = field(param.thisObject, "pluginData");
                        context = field(data, "context");
                    }
                    if (context instanceof Context) {
                        listener.onUnloaded(((Context) context).getClassLoader());
                    }
                } catch (Throwable error) {
                    listener.error("native plugin unload observer", error);
                }
            }
        });
        listener.log("native plugin discovery hooks loader=" + loaderHooks
                + " context=" + contextHooks + " os3Supplier=" + supplierHooks
                + " unload=" + unloadHooks);
    }

    private void notifyLoader(Object value, String source) {
        if (!(value instanceof ClassLoader)) return;
        try {
            listener.onLoader((ClassLoader) value);
        } catch (Throwable error) {
            // An optional renderer must never break normal plugin loading.
            listener.error("native plugin loader observer " + source, error);
        }
    }

    private int hook(Class<?> owner, String method, XC_MethodHook callback) {
        if (owner == null) return 0;
        try {
            return XposedBridge.hookAllMethods(owner, method, callback).size();
        } catch (Throwable error) {
            listener.error("native plugin discovery " + owner.getName() + "#" + method, error);
            return 0;
        }
    }

    private static boolean isFocusPlugin(Object factoryOrSupplier) {
        Object app = field(factoryOrSupplier, "pluginAppInfo", "mAppInfo", "f$1");
        if (app instanceof ApplicationInfo) {
            return isFocusPackage(((ApplicationInfo) app).packageName);
        }
        Object component = field(factoryOrSupplier, "componentName", "mComponentName");
        return component instanceof ComponentName
                && isFocusPackage(((ComponentName) component).getPackageName());
    }

    private static boolean isFocusPackage(String name) {
        return "miui.systemui.plugin".equals(name) || "com.miui.systemui.plugin".equals(name);
    }

    private static Object field(Object owner, String... names) {
        if (owner == null) return null;
        for (String name : names) {
            try {
                Object value = XposedHelpers.getObjectField(owner, name);
                if (value != null) return value;
            } catch (Throwable ignored) {
                // Known OS3/OS4 field alternatives; missing fields are expected here.
            }
        }
        return null;
    }
}