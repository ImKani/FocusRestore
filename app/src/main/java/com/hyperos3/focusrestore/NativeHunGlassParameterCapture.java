/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.util.Log;
import android.view.View;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Passively samples the successful native OS4 HUN glass application; it never changes ROM arguments.
 * Contract source: Xiaomi HyperOS SystemUI 17.03.260226.r / code 202602260,
 * HeadsUpNotificationGlassEffect.apply(Object, Context), HeadsUpNotificationGlassDarkEffect.apply,
 * and MiGlassCompat.setMiGlassCompat(View, float[]); ROM copyright owner/licence is unconfirmed.
 * Read-only evidence: http://192.168.31.146:8787/mcp, workspace d63pb6r9; retained local excerpts
 * are under Knowledge/sources/decompiled/native-focus-hun-20261117. This module independently
 * hooks only these observed signatures and validates/copies successful values; it copies no ROM
 * implementation or parameter array.
 */
final class NativeHunGlassParameterCapture {
    private static final String TAG = "HyperOS3FocusRestore";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String SYSTEM_UI_VERSION = "17.03.260226.r";
    private static final long SYSTEM_UI_VERSION_CODE = 202602260L;
    private static final String EFFECT_PACKAGE =
            "com.android.systemui.statusbar.notification.style.vieweffect.";
    private static final String LIGHT_EFFECT = EFFECT_PACKAGE + "HeadsUpNotificationGlassEffect";
    private static final String DARK_EFFECT = EFFECT_PACKAGE + "HeadsUpNotificationGlassDarkEffect";
    private static final String ROW_CLASS =
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
    private static final String BACKGROUND_CLASS =
            "com.android.systemui.statusbar.notification.row.NotificationBackgroundView";
    private static final String GLASS_COMPAT = "com.miui.systemui.util.MiGlassCompat";
    private static final ThreadLocal<Deque<Frame>> SCOPES = new ThreadLocal<>();
    private static final NativeHunGlassParameterSnapshot.Cache CACHE =
            new NativeHunGlassParameterSnapshot.Cache();
    private static final Map<ClassLoader, Boolean> INSTALLED = new WeakHashMap<>();
    private static volatile String lastPublishedConfiguration;
    private static volatile Profile processProfile;
    private static final AtomicBoolean WARNING_LOGGED = new AtomicBoolean();

    private NativeHunGlassParameterCapture() { }

    /** Installs observation hooks once per SystemUI class loader; unsupported ROMs remain inert. */
    static void install(ClassLoader loader) {
        if (loader == null) return;
        synchronized (INSTALLED) {
            if (INSTALLED.containsKey(loader)) return;
            HookInstaller installer = new HookInstaller();
            List<XC_MethodHook.Unhook> handles = new ArrayList<>();
            try {
                Class<?> compat = XposedHelpers.findClass(GLASS_COMPAT, loader);
                Class<?> viewClass = XposedHelpers.findClass("android.view.View", loader);
                Class<?> light = XposedHelpers.findClass(LIGHT_EFFECT, loader);
                Class<?> dark = XposedHelpers.findClass(DARK_EFFECT, loader);
                Method lightApply = XposedHelpers.findMethodExact(light, "apply", Object.class, Context.class);
                Method darkApply = XposedHelpers.findMethodExact(dark, "apply", Object.class, Context.class);
                handles.add(installer.exact(compat, "setMiGlassCompat", viewClass, float[].class,
                        new GlassSetterHook()));
                handles.add(installer.method(lightApply, new EffectScopeHook(NativeHunGlassParameterSnapshot.LIGHT_TYPE)));
                handles.add(installer.method(darkApply, new EffectScopeHook(NativeHunGlassParameterSnapshot.DARK_TYPE)));
                INSTALLED.put(loader, Boolean.TRUE);
                if (BuildConfig.DEBUG) Log.d(TAG, "DIAG native HUN glass observation installed; awaiting exact-profile sample");
            } catch (Throwable error) {
                for (XC_MethodHook.Unhook handle : handles) {
                    try { handle.unhook(); } catch (Throwable unhookError) {
                        error.addSuppressed(unhookError);
                    }
                }
                warnOnce("hook installation", error);
            }
        }
    }

    /** Returns a copy only for the exact current SystemUI profile, configuration and effect type. */
    static float[] read(Context context, boolean darkEffect) {
        Context app = applicationContext(context);
        if (app == null) return null;
        try {
            Profile info = profile(app);
            Configuration configuration = app.getResources().getConfiguration();
            ConfigState state = configState(configuration);
            String type = darkEffect ? NativeHunGlassParameterSnapshot.DARK_TYPE
                    : NativeHunGlassParameterSnapshot.LIGHT_TYPE;
            NativeHunGlassParameterSnapshot snapshot = CACHE.get(app.getPackageName(), info.versionName,
                    info.versionCode, state.key, state.sequence, type);
            return snapshot == null ? null : snapshot.parameters();
        } catch (Throwable error) {
            warnOnce("snapshot read", error);
            return null;
        }
    }

    /** Explicitly discards all samples, for an owner reacting to a configuration lifecycle boundary. */
    static void invalidate() { CACHE.invalidate(); }

    private static Profile profile(Context app) throws Exception {
        Profile result = processProfile;
        if (result != null) return result;
        synchronized (NativeHunGlassParameterCapture.class) {
            result = processProfile;
            if (result == null) {
                // 当前进程的已加载 APK 不热切换；配置逐次核对，版本元数据只读一次，避免 pre-draw Binder 查询。
                PackageInfo info = app.getPackageManager().getPackageInfo(SYSTEM_UI, 0);
                result = new Profile(info.versionName, info.getLongVersionCode());
                processProfile = result;
            }
        }
        return result;
    }

    private static Context applicationContext(Context context) {
        if (context == null || !SYSTEM_UI.equals(context.getPackageName())) return null;
        Context app = context.getApplicationContext();
        return app != null && SYSTEM_UI.equals(app.getPackageName()) ? app : null;
    }

    private static ConfigState configState(Configuration configuration) {
        int sequence = XposedHelpers.getIntField(configuration, "seq");
        return new ConfigState(sequence, sequence + "|" + configuration.toString());
    }

    private static boolean sameConfiguration(Configuration app, Configuration effect) {
        Configuration left = new Configuration(app);
        Configuration right = new Configuration(effect);
        int appNight = left.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        int effectNight = right.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if ((appNight != Configuration.UI_MODE_NIGHT_NO && appNight != Configuration.UI_MODE_NIGHT_YES)
                || appNight != effectNight) return false;
        XposedHelpers.setIntField(left, "seq", 0);
        XposedHelpers.setIntField(right, "seq", 0);
        return left.equals(right);
    }

    private static Frame createFrame(XC_MethodHook.MethodHookParam param, String effectType) {
        Frame frame = new Frame(param, effectType);
        try {
            Object[] args = param.args;
            if (args == null || args.length != 2 || !(args[0] instanceof View)
                    || !(args[1] instanceof Context)) return frame;
            ClassLoader loader = param.method.getDeclaringClass().getClassLoader();
            Class<?> rowClass = XposedHelpers.findClass(ROW_CLASS, loader);
            Class<?> backgroundClass = XposedHelpers.findClass(BACKGROUND_CLASS, loader);
            View row = (View) args[0];
            Context effectContext = (Context) args[1];
            if (!rowClass.isInstance(row) || !SYSTEM_UI.equals(effectContext.getPackageName())) return frame;
            Context app = applicationContext(effectContext);
            if (app == null) return frame;
            Profile info = profile(app);
            if (!SYSTEM_UI_VERSION.equals(info.versionName)
                    || info.versionCode != SYSTEM_UI_VERSION_CODE) return frame;
            Configuration appConfig = app.getResources().getConfiguration();
            Configuration effectConfig = effectContext.getResources().getConfiguration();
            if (!sameConfiguration(appConfig, effectConfig)) return frame;
            boolean night = (appConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            String expectedType = night ? NativeHunGlassParameterSnapshot.DARK_TYPE
                    : NativeHunGlassParameterSnapshot.LIGHT_TYPE;
            // GLASS_DARK can also be chosen by transparent-HUN policy in day mode; exclude that branch.
            if (!expectedType.equals(effectType)) return frame;
            Object injector = XposedHelpers.callMethod(row, "getInjector");
            Object background = injector == null ? null
                    : XposedHelpers.callMethod(injector, "getBackgroundNormal");
            if (!(background instanceof View) || !backgroundClass.isInstance(background)) return frame;
            ConfigState state = configState(appConfig);
            if (!CACHE.observe(app.getPackageName(), info.versionName, info.versionCode,
                    state.key, state.sequence)) return frame;
            frame.expectedBackground = (View) background;
            frame.packageName = app.getPackageName();
            frame.versionName = info.versionName;
            frame.versionCode = info.versionCode;
            frame.configurationKey = state.key;
            frame.configurationSequence = state.sequence;
            frame.enabled = true;
        } catch (Throwable error) {
            warnOnce("native effect scope", error);
        }
        return frame;
    }

    private static void recordSetter(XC_MethodHook.MethodHookParam param) {
        Deque<Frame> scopes = SCOPES.get();
        Frame frame = scopes == null ? null : scopes.peek();
        if (frame == null || !frame.enabled) return;
        Object[] args = param.args;
        if (args == null || args.length != 2 || args[0] != frame.expectedBackground
                || !(args[1] instanceof float[])) return;
        float[] values = (float[]) args[1];
        if (!NativeHunGlassParameterSnapshot.valid(values)) {
            // An all-zero cleanup or malformed write invalidates this apply attempt.
            frame.candidate = null;
            return;
        }
        frame.candidate = values.clone();
    }

    private static void finishEffect(XC_MethodHook.MethodHookParam param) {
        Deque<Frame> scopes = SCOPES.get();
        if (scopes == null) return;
        Frame frame = null;
        while (!scopes.isEmpty()) {
            Frame candidate = scopes.pop();
            if (candidate.parameter == param) {
                frame = candidate;
                break;
            }
        }
        if (scopes.isEmpty()) SCOPES.remove();
        if (frame == null || !frame.enabled || frame.candidate == null || param.hasThrowable()) return;
        if (!CACHE.publish(frame.packageName, frame.versionName, frame.versionCode,
                frame.configurationKey, frame.configurationSequence, frame.effectType, frame.candidate)) return;
        if (BuildConfig.DEBUG && !frame.configurationKey.equals(lastPublishedConfiguration)) {
            lastPublishedConfiguration = frame.configurationKey;
            Log.d(TAG, "DIAG native HUN glass sample captured type=" + frame.effectType
                    + " params=" + frame.candidate.length + " configSeq=" + frame.configurationSequence
                    + "; copied values only; visual unverified");
        }
    }

    private static void warnOnce(String stage, Throwable error) {
        if (WARNING_LOGGED.compareAndSet(false, true)) {
            Log.w(TAG, "native HUN glass parameter observation unavailable at " + stage, error);
        }
    }

    private static final class EffectScopeHook extends XC_MethodHook {
        private final String effectType;
        EffectScopeHook(String effectType) { this.effectType = effectType; }
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            Deque<Frame> scopes = SCOPES.get();
            if (scopes == null) {
                scopes = new ArrayDeque<>();
                SCOPES.set(scopes);
            }
            scopes.push(createFrame(param, effectType));
        }
        @Override protected void afterHookedMethod(MethodHookParam param) { finishEffect(param); }
    }

    private static final class GlassSetterHook extends XC_MethodHook {
        @Override protected void afterHookedMethod(MethodHookParam param) {
            if (!param.hasThrowable()) recordSetter(param);
        }
    }

    private static final class Profile {
        final String versionName;
        final long versionCode;
        Profile(String versionName, long versionCode) {
            this.versionName = versionName;
            this.versionCode = versionCode;
        }
    }

    private static final class ConfigState {
        final long sequence;
        final String key;
        ConfigState(long sequence, String key) { this.sequence = sequence; this.key = key; }
    }

    private static final class Frame {
        final XC_MethodHook.MethodHookParam parameter;
        final String effectType;
        View expectedBackground;
        String packageName, versionName, configurationKey;
        long versionCode, configurationSequence;
        float[] candidate;
        boolean enabled;
        Frame(XC_MethodHook.MethodHookParam parameter, String effectType) {
            this.parameter = parameter;
            this.effectType = effectType;
        }
    }
}
