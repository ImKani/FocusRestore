/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. 由 FocusRestore 项目维护。
 * 原始项目：https://github.com/ImKani/FocusRestore
 * 外部事实来源：miui.systemui.plugin TemplateFactoryV3/TemplateBuilderV3 私有接口；分析版本和证据见 Notes/analysis。
 * 说明：本文件为 FocusRestore 独立实现，不复制或重新授权 SystemUI/plugin 代码。
 */
package com.hyperos3.focusrestore;

import android.app.Notification;
import android.content.Context;
import android.graphics.Outline;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Observes the plugin's normal V3 production and builds an independently owned native view tree.
 * No original Content/View is retained or reparented, and no island window/controller is invoked.
 * Private API contracts were checked against miui.systemui.plugin 18.2.2.2.0; missing contracts
 * deliberately fail instead of substituting a hand-drawn notification.
 */
final class NativeFocusTemplateRenderer {
    interface Logger {
        void log(String message);
        void error(String stage, Throwable error);
        /**
         * Verbose diagnostics. The no-op default keeps existing implementations and test doubles
         * source-compatible, so adding this channel never forces unrelated callers to change.
         */
        default void debug(String message) { }
    }

    private static final String FACTORY =
            "miui.systemui.notification.focus.templateV3.TemplateFactoryV3";
    private static final String TEMPLATE = "miui.systemui.notification.focus.model.Template";
    private static final String CONTENT =
            "com.android.systemui.plugins.miui.notification.FocusNotificationContent";
    private static final String CONTENT_IMPL =
            "miui.systemui.notification.focus.FocusNotificationContentImpl";
    private static final String CALLBACK =
            "miui.systemui.notification.focus.InflateAndAuthCallBack";
    interface RowProvider { View findRow(String key); }
    private static final String NOTIFICATION_R = "com.android.systemui.miui.notification.R$";
    private static final String QUICKSETTINGS_R = "miui.systemui.quicksettings.common.R$";
    private static final String ISLAND_R = "miui.systemui.dynamicisland.R$";
    private static final String BACKGROUND = "moduleBackground";
    private static final String[] ADAPTER_FIELDS = {
            "islandAdapter", "islandFakeAdapter", "focusAdapter", "focusDarkAdapter",
            "focusModalAdapter", "focusModalDarkAdapter"
    };
    private static final String[] HOLDER_FIELDS = {
            "holders", "tinyHolders", "decoHolders", "decoLandHolders"
    };
    private static final int MAX_SOURCES = 64;
    private static final int MAX_FACTORIES_PER_LOADER = 4;

    private final Logger logger;
    private final Runnable sourceChanged;
    private final RowProvider rowProvider;
    private final Object lock = new Object();
    private final Map<ClassLoader, Installation> installations = new IdentityHashMap<>();
    // Only semantic templates and a notification stamp are cached. Factory references are weak.
    private final LinkedHashMap<String, Source> sources = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<String, Long> removedKeys = new LinkedHashMap<>();
    private Handler main;
    private boolean sourceCallbackQueued;

    NativeFocusTemplateRenderer(Logger logger, Runnable sourceChanged, RowProvider rowProvider) {
        if (logger == null) throw new IllegalArgumentException("logger == null");
        this.logger = logger;
        this.sourceChanged = sourceChanged;
        this.rowProvider = rowProvider;
        // handleLoadPackage need not have a Looper. Resolve the main Handler only when needed.
    }

    /** Called synchronously when the real plugin ClassLoader is discovered, before plugin onCreate. */
    void install(ClassLoader pluginLoader) {
        if (pluginLoader == null) return;
        synchronized (lock) {
            if (installations.containsKey(pluginLoader)) {
                debug("native renderer install skipped loader=" + identity(pluginLoader)
                        + " reason=already-installed");
                return;
            }
            Installation installation = new Installation(pluginLoader);
            try {
                Class<?> factoryClass = load(pluginLoader, FACTORY);
                Method standard = factoryClass.getDeclaredMethod("createStandardTemplateView",
                        load(pluginLoader, TEMPLATE), StatusBarNotification.class, boolean.class,
                        load(pluginLoader, CONTENT), load(pluginLoader, CALLBACK));
                standard.setAccessible(true);
                installations.put(pluginLoader, installation);
                debug("native renderer contract resolved " + FACTORY + "#createStandardTemplateView("
                        + TEMPLATE + ",StatusBarNotification,boolean," + CONTENT + "," + CALLBACK
                        + ") loader=" + identity(pluginLoader));
                installation.hooks.addAll(XposedBridge.hookAllConstructors(factoryClass,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                if (param.hasThrowable()) return;
                                rememberFactory(installation, param.thisObject);
                            }
                        }));
                installation.hooks.add(XposedBridge.hookMethod(standard, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable()) return;
                        try {
                            observe(installation, param.thisObject, param.args[0],
                                    (StatusBarNotification) param.args[1],
                                    Boolean.TRUE.equals(param.args[2]));
                        } catch (Throwable error) {
                            report("observe native V3 source", error);
                        }
                    }
                }));
                log("native renderer hooks installed; observing normal V3 production only");
            } catch (Throwable error) {
                installation.connected = false;
                installations.remove(pluginLoader);
                unhook(installation);
                report("native V3 renderer unavailable for plugin loader", error);
            }
        }
    }

    void onPluginDisconnected(ClassLoader loader) {
        Installation removed;
        synchronized (lock) {
            removed = installations.remove(loader);
            if (removed == null) return;
            removed.connected = false;
            Iterator<Source> iterator = sources.values().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().installation == removed) iterator.remove();
            }
            removed.factories.clear();
        }
        unhook(removed);
        log("native renderer plugin disconnected; semantic sources discarded");
        notifySourceChanged();
    }

    void onNotificationRemoved(String key) {
        if (key == null) return;
        boolean hadSource;
        boolean firstRemoval;
        synchronized (lock) {
            hadSource = sources.remove(key) != null;
            // Reject a late coroutine completing an old post after its notification was removed.
            firstRemoval = !removedKeys.containsKey(key);
            removedKeys.put(key, System.currentTimeMillis());
            trim(removedKeys, MAX_SOURCES);
        }
        // A key without a cached template is often removed many times in one burst; logging every
        // repeat would bury the interesting lines without adding information.
        if (hadSource || firstRemoval) {
            debug("native source dropped key=" + key + " hadCachedTemplate=" + hadSource
                    + " firstRemoval=" + firstRemoval + " removedKeys=" + removedKeys.size());
        }
    }

    /** Ignore unrelated source updates; only replace the visible session for its own generation. */
    boolean isCurrent(Render render, StatusBarNotification sbn) {
        if (render == null || render.closed.get()) return false;
        try {
            Stamp stamp = Stamp.read(sbn);
            synchronized (lock) {
                Source current = sources.get(stamp.key);
                return current == render.templateSource && current.installation.connected
                        && current.stamp.identity.matches(stamp.identity);
            }
        } catch (Throwable ignored) { return false; }
    }

    static boolean sameNotification(StatusBarNotification expected, StatusBarNotification actual) {
        try {
            return Stamp.read(expected).identity.matches(Stamp.read(actual).identity)
                    && java.util.Objects.equals(expected.getNotification().contentIntent,
                            actual.getNotification().contentIntent)
                    && java.util.Objects.equals(expected.getNotification().fullScreenIntent,
                            actual.getNotification().fullScreenIntent);
        } catch (Throwable ignored) { return false; }
    }

    Render create(String key, StatusBarNotification sbn) throws Exception {
        requireMainThread();
        Stamp requested = Stamp.read(sbn);
        if (key == null || !key.equals(requested.key)) {
            throw unavailable("requested key does not match the real StatusBarNotification");
        }
        Source source;
        synchronized (lock) {
            pruneSources();
            Long removedAt = removedKeys.get(key);
            if (removedAt != null && requested.postTime <= removedAt) {
                throw unavailable("notification was removed; refusing a late native source"
                        + " (postTime=" + requested.postTime + " removedAt=" + removedAt + ")");
            }
            source = sources.get(key);
        }
        if (source == null || !source.installation.connected) {
            throw unavailable("no observed native V3 template for this key; await normal plugin inflation"
                    + " (paramKey=" + requested.paramKey + ", "
                    + (source == null ? "cache-miss" : "loader-disconnected") + ", "
                    + observedSummary() + ")" + customFocusHint(sbn));
        }
        if (!source.stamp.identity.matches(requested.identity)) {
            throw unavailable("native model/SBN identity is stale (key/package/user/uid/postTime/params); "
                    + "await this notification's normal V3 inflation");
        }
        Object factory = source.factory.get();
        if (factory == null) throw unavailable("native template factory was released");
        // A captured template must still be the normal chain's current model, not an older task.
        if (mapValue(factory, "templateMap", key) != source.template
                || mapValue(factory, "builderMap", key) == null) {
            throw unavailable("normal V3 template generation changed or was removed");
        }
        // Native module binding writes derived timer extras. Preserve the real SBN identity but
        // isolate those writes from SystemUI's notification; PendingIntents retain their tokens.
        StatusBarNotification bindingSbn = cloneForBinding(sbn);
        if (!requested.identity.matches(Stamp.read(bindingSbn).identity)) {
            throw unavailable("cloned binding SBN did not preserve notification identity");
        }
        Object builder = null;
        Object content = null;
        ViewGroup expanded = null;
        List<Object> adapters = new ArrayList<>();
        String[] modules = new String[0];
        String stage = "resolve native template modules";
        try {
            String moduleA = string(call(factory, "chooseModule", source.template,
                    "area_a", Boolean.FALSE));
            String moduleC = string(call(factory, "chooseModule", source.template,
                    "area_c", Boolean.FALSE));
            String moduleD = string(call(factory, "chooseModule", source.template,
                    "area_d", Boolean.FALSE));
            if (moduleA.isEmpty()) throw unavailable("native V3 area A is empty");
            modules = new String[]{moduleD, moduleA, moduleC, BACKGROUND};
            boolean solid = Boolean.TRUE.equals(call(factory, "isSolidBackground", source.template, bindingSbn));

            stage = "construct independent native Content and Builder";
            content = construct(load(source.installation.loader, CONTENT_IMPL), new Class<?>[0]);
            call(content, "setKey", requested.key);
            call(content, "setSbn", bindingSbn);
            Object builderFactory = get(factory, "templateBuilderV3Factory");
            Object originalBuilder = mapValue(factory, "builderMap", key);
            Object candidate = call(builderFactory, "create", source.isFlip);
            if (candidate == null || candidate == originalBuilder) {
                throw unavailable("builder factory did not return an independent builder");
            }
            for (String adapterField : ADAPTER_FIELDS) {
                if (get(candidate, adapterField) != null) {
                    throw unavailable("new builder already owns adapters; unsupported factory contract");
                }
            }
            builder = candidate;
            prepareIndependentAdapters(builder, originalBuilder, adapters);
            Object nativeContext = get(builder, "context");
            if (!(nativeContext instanceof Context)) throw unavailable("plugin resource context missing");
            Context context = (Context) nativeContext;
            ClassLoader loader = source.installation.loader;
            int areaBg = resourceId(context, loader, NOTIFICATION_R, "id", "area_bg");
            int areaA = resourceId(context, loader, NOTIFICATION_R, "id", "area_a");
            int areaC = resourceId(context, loader, NOTIFICATION_R, "id", "area_c");
            int areaD = resourceId(context, loader, NOTIFICATION_R, "id", "area_d");

            stage = "bind independent native module trees";
            // Mirrors only the new-builder branch; no shared Factory create/remove method is called.
            call(builder, "setAreaViewVisible", areaBg, solid ? View.GONE : View.VISIBLE);
            call(builder, "setAreaViewVisible", areaA, View.VISIBLE);
            call(builder, "setAreaViewVisible", areaC,
                    moduleC.isEmpty() ? View.INVISIBLE : View.VISIBLE);
            call(builder, "setAreaViewVisible", areaD,
                    moduleD.isEmpty() ? View.GONE : View.VISIBLE);
            call(builder, "addModuleView", areaBg, BACKGROUND, solid, source.template, bindingSbn);
            call(builder, "addModuleView", areaD, moduleD, solid, source.template, bindingSbn);
            call(builder, "addModuleView", areaC, moduleC, solid, source.template, bindingSbn);
            call(builder, "addModuleView", areaA, moduleA, solid, source.template, bindingSbn);
            adapters = ownedAdapters(builder);
            ensureIndependentAdapters(adapters, mapValue(factory, "builderMap", key));
            call(builder, "buildView", content, source.template);
            stage = "select notification expanded content";
            Context sysuiContext = (Context) get(adapters.get(0), "sysuiContext");
            View row = rowProvider == null ? null : rowProvider.findRow(key);
            // 背景由统一外观读取器选择：默认纯色，普通背景开关开启时沿用 ROM 通知卡片主题。
            NativeFocusAppearance appearance = NativeFocusAppearance.read(row, sysuiContext);
            String getter = appearance.dark ? "getFocusNotificationDarkModal" : "getFocusNotificationModal";
            Object result = call(content, getter);
            if (!(result instanceof View)) throw unavailable("independent builder returned no notification modal view");
            View body = (View) result;
            if (body.getParent() != null) throw unavailable("new native content already has a parent");
            // This private copy is not in SystemUI's ModalController. Native actions must not try
            // to dismiss an unrelated system modal by following the template's default tag.
            body.setTag(resourceId(context, loader, QUICKSETTINGS_R, "id", "dynamic_island_modal_tag"), null);

            stage = "host notification expanded content";
            expanded = new FrameLayout(context);
            expanded.setBackground(appearance.background);
            roundClip(expanded, appearance.radius);
            // Keep the native body's own minimums/margins; do not add the shade row's floor.
            body.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            expanded.addView(body);
            if (!source.installation.connected) throw unavailable("plugin disconnected during render creation");
            Render render = new Render(this, loader, expanded, context, appearance.width, body.getMinimumHeight(),
                    builder, content, modules, adapters, bindingSbn, body, source,
                    "native-v3/" + getter + "/" + appearance.description);
            log("native renderer created fresh notification-expanded content key=" + key
                    + " getter=" + getter + " width=" + appearance.width
                    + " bodyMinHeight=" + body.getMinimumHeight() + " sizing=wrap-content appearance=" + appearance.description
                    + "; native module binding may finish on the UI scope");
            return render;
        } catch (Throwable error) {
            // Includes partial module creation. The caller never has to close a failed create().
            if (builder != null && adapters.isEmpty()) adapters = ownedAdaptersSafely(builder);
            if (expanded != null) {
                try { expanded.removeAllViews(); }
                catch (Throwable cleanupError) { report("remove partial native container", cleanupError); }
            }
            cleanupBuilder(builder, content, modules, adapters);
            throw new IllegalStateException("native V3 unavailable at " + stage + ": "
                    + message(error), unwrap(error));
        }
    }

    private void observe(Installation installation, Object factory, Object template,
                         StatusBarNotification sbn, boolean isFlip) throws Exception {
        Stamp stamp = Stamp.read(sbn);
        // The area-A-empty callback is successful too, but has no standard Builder to reuse.
        Object observedTemplate = mapValue(factory, "templateMap", stamp.key);
        Object observedBuilder = mapValue(factory, "builderMap", stamp.key);
        if (observedTemplate != template || observedBuilder == null) {
            debug("native source ignored key=" + stamp.key + " postTime=" + stamp.postTime
                    + " reason=" + (observedTemplate != template ? "templateMap-mismatch"
                    : "builderMap-absent")
                    + " (area-A-empty callback keeps no reusable standard Builder)");
            return;
        }
        rememberFactory(installation, factory);
        boolean firstReady;
        synchronized (lock) {
            if (!installation.connected) {
                debug("native source ignored key=" + stamp.key
                        + " reason=loader-disconnected");
                return;
            }
            Long removedAt = removedKeys.get(stamp.key);
            if (removedAt != null && stamp.postTime <= removedAt) {
                debug("native source ignored key=" + stamp.key + " postTime=" + stamp.postTime
                        + " reason=posted-before-removal removedAt=" + removedAt);
                return;
            }
            Source previous = sources.get(stamp.key);
            if (previous != null && previous.installation == installation
                    && previous.stamp.postTime > stamp.postTime) {
                debug("native source ignored key=" + stamp.key + " postTime=" + stamp.postTime
                        + " reason=older-than-cached cachedPostTime=" + previous.stamp.postTime);
                return;
            }
            firstReady = previous == null || previous.installation != installation;
            removedKeys.remove(stamp.key);
            pruneSources();
            sources.put(stamp.key, new Source(installation, factory, template, stamp, isFlip));
            trim(sources, MAX_SOURCES);
        }
        if (firstReady) {
            log("native source-ready key=" + stamp.key + " postTime=" + stamp.postTime
                    + " paramKey=" + stamp.paramKey
                    + " source=normal-V3-template isFlip=" + isFlip);
        }
        notifySourceChanged();
    }

    private void rememberFactory(Installation installation, Object factory) {
        synchronized (lock) {
            if (!installation.connected || factory == null) return;
            Iterator<WeakReference<Object>> iterator = installation.factories.iterator();
            while (iterator.hasNext()) {
                Object existing = iterator.next().get();
                if (existing == null) iterator.remove();
                else if (existing == factory) return;
            }
            installation.factories.add(new WeakReference<>(factory));
            while (installation.factories.size() > MAX_FACTORIES_PER_LOADER) {
                installation.factories.remove(0);
            }
        }
        // No scan/bootstrap from builder.lastSbn: native updates do not always refresh that field.
    }

    /**
     * Explains the architectural case behind a cache miss. The ROM's {@code hasCustomFocusView}
     * checks {@code miui.focus.rv}, and a notification that carries it is rendered by
     * {@code FocusNotifPreHandler.buildNoParamsFocusNotification} through the custom-RemoteViews
     * path, which never calls {@code TemplateFactoryV3.createStandardTemplateView}. Such a
     * notification therefore has no standard V3 template to observe, no matter how long we wait.
     */
    private static String customFocusHint(StatusBarNotification sbn) {
        try {
            Notification notification = sbn == null ? null : sbn.getNotification();
            Bundle extras = notification == null ? null : notification.extras;
            if (extras == null) return "";
            boolean customRemoteViews = extras.getParcelable("miui.focus.rv") instanceof RemoteViews
                    || extras.getParcelable("miui.focus.rvNight") instanceof RemoteViews;
            boolean standardParam = extras.getString(FocusParamKeys.PRIMARY) != null;
            if (customRemoteViews && !standardParam) {
                return "; custom-RemoteViews focus notification (miui.focus.rv present, "
                        + FocusParamKeys.PRIMARY + " absent) uses the plugin's custom-view path,"
                        + " which never produces a standard V3 template";
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /**
     * Bounded snapshot of the cached template keys. Answers "was this notification ever observed?"
     * from the failure line itself instead of needing a second run with extra instrumentation.
     */
    private String observedSummary() {
        synchronized (lock) {
            if (sources.isEmpty()) return "observedKeys=0";
            StringBuilder text = new StringBuilder("observedKeys=").append(sources.size()).append('[');
            int index = 0;
            for (String observed : sources.keySet()) {
                if (index > 0) text.append(',');
                if (++index > 8) { text.append(",..."); break; }
                text.append(observed);
            }
            return text.append(']').toString();
        }
    }

    private void pruneSources() {
        Iterator<Source> iterator = sources.values().iterator();
        while (iterator.hasNext()) {
            Source source = iterator.next();
            if (!source.installation.connected || source.factory.get() == null) iterator.remove();
        }
    }

    private void notifySourceChanged() {
        if (sourceChanged == null) return;
        Handler handler = mainHandler();
        if (handler == null) return;
        synchronized (lock) {
            if (sourceCallbackQueued) return;
            sourceCallbackQueued = true;
        }
        if (!handler.post(() -> {
            synchronized (lock) { sourceCallbackQueued = false; }
            try { sourceChanged.run(); }
            catch (Throwable error) { report("native source callback", error); }
        })) {
            synchronized (lock) { sourceCallbackQueued = false; }
        }
    }

    private Handler mainHandler() {
        synchronized (lock) {
            if (main != null) return main;
            Looper looper = Looper.getMainLooper();
            if (looper == null) return null;
            main = new Handler(looper);
            return main;
        }
    }

    private void cleanupBuilder(Object builder, Object content, String[] modules, List<Object> adapters) {
        if (builder == null) return;
        for (String module : new LinkedHashSet<>(java.util.Arrays.asList(modules))) {
            if (module == null || module.isEmpty()) continue;
            try { call(builder, "removeModuleView", module); }
            catch (Throwable error) { report("native module removal " + module, error); }
        }
        if (content != null) {
            try { call(builder, "removeView", content); }
            catch (Throwable error) { report("native builder reference cleanup", error); }
        }
        // Native removeModuleView queues adapter coroutines. Do not cancel them before they detach.
        List<Object> owned = new ArrayList<>(adapters);
        Handler handler = mainHandler();
        if (handler == null || !handler.post(() -> finishAdapterCleanup(owned))) {
            finishAdapterCleanup(owned);
        }
    }

    private void finishAdapterCleanup(List<Object> adapters) {
        for (Object adapter : adapters) {
            // Cancel any still-suspended binding before the final sweep. Only newly owned scopes
            // are touched; no ConcurrencyModule/global scope is cancelled.
            try { cancelOwnedAdapterScope(adapter); }
            catch (Throwable error) { report("independent adapter scope cancellation", error); }
            Set<Object> detached = Collections.newSetFromMap(new IdentityHashMap<>());
            for (String name : HOLDER_FIELDS) {
                try {
                    Object value = get(adapter, name);
                    if (!(value instanceof Map)) continue;
                    Map<?, ?> holders = (Map<?, ?>) value;
                    for (Object holder : new ArrayList<>(holders.values())) {
                        if (holder != null && detached.add(holder)) {
                            try { call(holder, "onDetach"); }
                            catch (Throwable error) { report("independent holder detach", error); }
                        }
                    }
                    holders.clear();
                } catch (Throwable error) { report("independent holder cleanup " + name, error); }
            }
            try {
                Object data = get(adapter, "dataMap");
                if (data instanceof Map) ((Map<?, ?>) data).clear();
            } catch (Throwable error) { report("independent adapter data cleanup", error); }
        }
    }

    private static void cancelOwnedAdapterScope(Object adapter) throws Exception {
        Object scope = get(adapter, "scope");
        if (scope == null) return;
        ClassLoader loader = adapter.getClass().getClassLoader();
        // The second name is the verified relocated coroutine helper in plugin 18.2.2.2.0.
        for (String name : new String[]{"kotlinx.coroutines.CoroutineScopeKt", "h1.H"}) {
            Class<?> helper;
            try { helper = load(loader, name); }
            catch (ClassNotFoundException ignored) { continue; }
            for (Method method : helper.getDeclaredMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class
                        && p.length == 4 && p[0].isInstance(scope)
                        && p[1] == CancellationException.class && p[2] == int.class
                        && p[3] == Object.class
                        && (method.getName().equals("cancel$default") || method.getName().equals("e"))) {
                    invoke(method, null, scope, null, 1, null);
                    return;
                }
            }
        }
        throw new NoSuchMethodException("owned adapter CoroutineScope cancellation contract missing");
    }

    static final class Render implements FocusBannerSource {
        final View view;
        final Context context;
        final int widthPx;
        final int minHeightPx;
        final String source;
        private final Source templateSource;

        private final NativeFocusTemplateRenderer owner;
        private final ClassLoader loader;
        private final Object builder;
        private final Object content;
        private final String[] modules;
        private final List<Object> adapters;
        private final StatusBarNotification bindingSbn;
        private final View body;
        private NativeFocusTimerSession timerSession;
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean attachRequested;

        private Render(NativeFocusTemplateRenderer owner, ClassLoader loader, View view,
                       Context context, int width, int minHeight, Object builder, Object content,
                       String[] modules, List<Object> adapters, StatusBarNotification bindingSbn,
                       View body, Source templateSource, String source) {
            this.owner = owner;
            this.loader = loader;
            this.view = view;
            this.context = context;
            this.widthPx = width;
            this.minHeightPx = minHeight;
            this.builder = builder;
            this.content = content;
            this.modules = modules;
            this.adapters = adapters;
            this.templateSource = templateSource;
            this.source = source;
            this.bindingSbn = bindingSbn;
            this.body = body;
        }

        @Override public View view() { return view; }
        @Override public Context context() { return context; }
        @Override public int widthPx() { return widthPx; }
        @Override public int minHeightPx() { return minHeightPx; }
        @Override public String source() { return source; }

        @Override
        public boolean isCurrent(StatusBarNotification actual) {
            return owner.isCurrent(this, actual);
        }

        @Override
        public String layoutSummary() {
            return "sizing=wrap-content wrapperHeight=" + view.getHeight()
                    + " bodyHeight=" + body.getHeight() + " bodyMeasured=" + body.getMeasuredHeight()
                    + " bodyMin=" + body.getMinimumHeight();
        }

        @Override
        public void onAttached() {
            if (closed.get()) return;
            if (Looper.myLooper() != Looper.getMainLooper()) {
                owner.report("native render attach", new IllegalStateException("main thread required"));
                return;
            }
            if (attachRequested) return;
            attachRequested = true;
            // View.post waits for attachment when WindowManager.addView has not attached yet.
            view.post(() -> {
                if (closed.get() || !view.isAttachedToWindow()) return;
                try {
                    timerSession = NativeFocusTimerSession.attach(context, body, bindingSbn,
                            loader, owner.logger);
                } catch (Throwable error) {
                    owner.report("independent native timer session", error);
                }
                view.requestLayout();
                view.invalidate();
            });
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            Runnable cleanup = () -> {
                if (timerSession != null) {
                    try { timerSession.close(); }
                    catch (Throwable error) { owner.report("independent native timer close", error); }
                    timerSession = null;
                }
                try { ((ViewGroup) view).removeAllViews(); }
                catch (Throwable error) { owner.report("remove owned notification content", error); }
                owner.cleanupBuilder(builder, content, modules, adapters);
            };
            if (Looper.myLooper() == Looper.getMainLooper() && Looper.myLooper() != null) {
                cleanup.run();
            } else {
                Handler handler = owner.mainHandler();
                if (handler == null || !handler.post(cleanup)) {
                    owner.report("native render close", new IllegalStateException("main Looper unavailable"));
                }
            }
        }
    }

    private static final class Installation {
        final ClassLoader loader;
        final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        final List<WeakReference<Object>> factories = new ArrayList<>();
        volatile boolean connected = true;
        Installation(ClassLoader loader) { this.loader = loader; }
    }

    private static final class Source {
        final Installation installation;
        final WeakReference<Object> factory;
        final Object template;
        final Stamp stamp;
        final boolean isFlip;
        Source(Installation installation, Object factory, Object template, Stamp stamp, boolean isFlip) {
            this.installation = installation;
            this.factory = new WeakReference<>(factory);
            this.template = template;
            this.stamp = stamp;
            this.isFlip = isFlip;
        }
    }

    private static final class Stamp {
        final String key;
        final long postTime;
        final String paramKey;
        final NativeFocusSourceIdentity identity;

        private Stamp(StatusBarNotification sbn, String focusParam, String paramKey) {
            key = sbn.getKey();
            postTime = sbn.getPostTime();
            this.paramKey = paramKey;
            identity = new NativeFocusSourceIdentity(key, sbn.getPackageName(), sbn.getUid(),
                    userIdentifier(sbn), postTime, focusParam);
        }

        private static int userIdentifier(StatusBarNotification sbn) {
            try { return ((Number) call(sbn.getUser(), "getIdentifier")).intValue(); }
            catch (Exception error) {
                throw new IllegalStateException("real notification user identity unavailable", error);
            }
        }

        static Stamp read(StatusBarNotification sbn) {
            if (sbn == null || sbn.getNotification() == null || sbn.getUser() == null
                    || sbn.getKey() == null || sbn.getPackageName() == null) {
                throw unavailable("real StatusBarNotification identity missing");
            }
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;
            FocusParamKeys.ValueSource source = extras == null ? null : extras::get;
            String param = FocusParamKeys.pick(source);
            if (param == null || param.isEmpty()) {
                throw unavailable("notification has no native V3 focus parameters ("
                        + FocusParamKeys.describe(source) + ")");
            }
            return new Stamp(sbn, param, FocusParamKeys.pickKey(source));
        }
    }

    private static void prepareIndependentAdapters(Object builder, Object originalBuilder,
                                                   List<Object> owned) throws Exception {
        Object provider = get(builder, "moduleViewHolderAdapterProvider");
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> originals = Collections.newSetFromMap(new IdentityHashMap<>());
        if (originalBuilder != null) {
            for (String field : ADAPTER_FIELDS) {
                Object value = get(originalBuilder, field);
                if (value != null) originals.add(value);
            }
        }
        // Validate each provider product before attaching it to our Builder or allowing any module
        // mutation. A scoped/custom provider must never cause cleanup to touch system-owned holders.
        for (String field : ADAPTER_FIELDS) {
            Object adapter = call(provider, "get");
            if (adapter == null || originals.contains(adapter) || !seen.add(adapter)) {
                throw unavailable("adapter provider did not return a fresh product for each root");
            }
            for (String holderField : HOLDER_FIELDS) {
                Object value = get(adapter, holderField);
                if (!(value instanceof Map) || !((Map<?, ?>) value).isEmpty()) {
                    throw unavailable("adapter provider product is not empty: " + holderField);
                }
            }
            Object data = get(adapter, "dataMap");
            if (!(data instanceof Map) || !((Map<?, ?>) data).isEmpty()) {
                throw unavailable("adapter provider product already has module data");
            }
            owned.add(adapter);
            set(builder, field, adapter);
        }
    }

    private static List<Object> ownedAdapters(Object builder) throws Exception {
        List<Object> adapters = new ArrayList<>();
        Set<Object> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : ADAPTER_FIELDS) {
            Object adapter = get(builder, name);
            if (adapter == null) continue;
            if (!identities.add(adapter)) throw unavailable("adapter provider reused an adapter across roots");
            adapters.add(adapter);
        }
        if (adapters.size() != ADAPTER_FIELDS.length) {
            throw unavailable("native builder did not create all six independent adapters");
        }
        return adapters;
    }

    private List<Object> ownedAdaptersSafely(Object builder) {
        Set<Object> values = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : ADAPTER_FIELDS) {
            try {
                Object value = get(builder, name);
                if (value != null) values.add(value);
            } catch (Throwable error) { report("collect partially created adapter " + name, error); }
        }
        return new ArrayList<>(values);
    }

    private static void ensureIndependentAdapters(List<Object> owned, Object originalBuilder) throws Exception {
        if (originalBuilder == null) return;
        for (String name : ADAPTER_FIELDS) {
            Object original = get(originalBuilder, name);
            for (Object adapter : owned) {
                if (adapter == original) throw unavailable("provider returned a system-owned adapter");
            }
        }
    }

    private static StatusBarNotification cloneForBinding(StatusBarNotification original) {
        StatusBarNotification copy = original.clone();
        Notification notification = copy.getNotification();
        notification.extras = new Bundle(notification.extras);
        // Bundle's copy is shallow. Native buildAction writes click_with_collapse into Action
        // extras, so clone that nested bundle's actions as well; PendingIntent tokens stay intact.
        Bundle originalActions = original.getNotification().extras.getBundle("miui.focus.actions");
        if (originalActions != null) {
            Bundle actions = new Bundle(originalActions);
            for (String name : originalActions.keySet()) {
                Object value = originalActions.get(name);
                if (value instanceof Notification.Action) {
                    actions.putParcelable(name, ((Notification.Action) value).clone());
                }
            }
            notification.extras.putBundle("miui.focus.actions", actions);
        }
        if (notification.actions != null) {
            Notification.Action[] actions = notification.actions.clone();
            for (int i = 0; i < actions.length; i++) {
                if (actions[i] != null) actions[i] = actions[i].clone();
            }
            notification.actions = actions;
        }
        return copy;
    }

    private static boolean isPromoted(StatusBarNotification sbn) {
        try {
            return Boolean.TRUE.equals(call(sbn.getNotification(), "isPromotedOngoing"));
        } catch (Exception ignored) { return false; }
    }

    private static void roundClip(View view, float radius) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View value, Outline outline) {
                outline.setRoundRect(0, 0, value.getWidth(), value.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);
    }

    private static int dimension(Context context, ClassLoader loader, String name) throws Exception {
        return context.getResources().getDimensionPixelSize(
                resourceId(context, loader, ISLAND_R, "dimen", name));
    }

    private static int resourceId(Context context, ClassLoader loader, String resourceOwner,
                                  String type, String name) throws Exception {
        try {
            Field field = load(loader, resourceOwner + type).getDeclaredField(name);
            field.setAccessible(true);
            int id = field.getInt(null);
            if (id != 0) return id;
        } catch (ReflectiveOperationException ignored) { }
        int id = context.getResources().getIdentifier(name, type, context.getPackageName());
        if (id == 0) id = context.getResources().getIdentifier(name, type, "miui.systemui.plugin");
        if (id == 0) throw unavailable("native resource missing: " + type + "/" + name);
        return id;
    }

    private static Object mapValue(Object owner, String field, String key) throws Exception {
        Object value = get(owner, field);
        if (!(value instanceof Map)) throw unavailable("native map missing: " + field);
        return ((Map<?, ?>) value).get(key);
    }

    private static Object get(Object target, String name) throws Exception {
        if (target == null) throw unavailable("native dependency missing for field " + name);
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "." + name);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "." + name);
    }

    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static Object construct(Class<?> type, Class<?>[] parameters, Object... args) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        try { return constructor.newInstance(args); }
        catch (InvocationTargetException error) { throw checked(error.getCause()); }
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        if (target == null) throw unavailable("native dependency missing for " + name);
        return invoke(findMethod(target.getClass(), name, false, args), target, args);
    }

    private static Object callStatic(Class<?> type, String name, Object... args) throws Exception {
        Method method = findMethod(type, name, false, args);
        if (!Modifier.isStatic(method.getModifiers())) throw new NoSuchMethodException(name + " is not static");
        return invoke(method, null, args);
    }

    private static Object callPrefixed(Object target, String prefix, Object... args) throws Exception {
        return invoke(findMethod(target.getClass(), prefix, true, args), target, args);
    }

    private static Method findMethod(Class<?> owner, String name, boolean prefix, Object[] args)
            throws NoSuchMethodException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            Method match = null;
            for (Method method : type.getDeclaredMethods()) {
                if (!(prefix ? method.getName().startsWith(name) : method.getName().equals(name))) continue;
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != args.length) continue;
                boolean compatible = true;
                for (int i = 0; i < parameters.length; i++) {
                    if (!accepts(parameters[i], args[i])) { compatible = false; break; }
                }
                if (!compatible) continue;
                if (match != null && !method.isBridge() && !match.isBridge()) {
                    throw new NoSuchMethodException("ambiguous native method " + owner.getName() + "." + name);
                }
                if (match == null || match.isBridge()) match = method;
            }
            if (match != null) { match.setAccessible(true); return match; }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + "(" + args.length + ")");
    }

    private static boolean accepts(Class<?> parameter, Object value) {
        if (value == null) return !parameter.isPrimitive();
        if (!parameter.isPrimitive()) return parameter.isInstance(value);
        return (parameter == boolean.class && value instanceof Boolean)
                || (parameter == int.class && value instanceof Integer)
                || (parameter == long.class && value instanceof Long)
                || (parameter == float.class && value instanceof Float)
                || (parameter == double.class && value instanceof Double);
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw checked(error.getCause()); }
    }

    private static Exception checked(Throwable error) {
        if (error instanceof Exception) return (Exception) error;
        if (error instanceof Error) throw (Error) error;
        return new IllegalStateException(error);
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error;
    }

    private static String message(Throwable error) {
        Throwable cause = unwrap(error);
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static String string(Object value) {
        if (!(value instanceof String)) throw unavailable("native module chooser returned a non-string");
        return (String) value;
    }

    private static IllegalStateException unavailable(String reason) {
        return new IllegalStateException("native V3 unavailable: " + reason);
    }

    private static void requireMainThread() {
        Looper main = Looper.getMainLooper();
        if (main == null || Looper.myLooper() != main) throw unavailable("main thread required");
    }

    /** Short loader identity for diagnostics; never null. */
    private static String identity(ClassLoader loader) {
        return loader == null ? "null" : loader.getClass().getSimpleName() + '@'
                + Integer.toHexString(System.identityHashCode(loader));
    }

    private static <T> void trim(LinkedHashMap<String, T> map, int maximum) {
        while (map.size() > maximum) map.remove(map.keySet().iterator().next());
    }

    private void unhook(Installation installation) {
        for (XC_MethodHook.Unhook hook : installation.hooks) {
            try { hook.unhook(); }
            catch (Throwable error) { report("remove native renderer observer", error); }
        }
        installation.hooks.clear();
    }

    private void log(String message) {
        try { logger.log(message); }
        catch (Throwable ignored) { }
    }

    private void debug(String message) {
        try { logger.debug(message); }
        catch (Throwable ignored) { }
    }

    private void report(String stage, Throwable error) {
        try { logger.error(stage, unwrap(error)); }
        catch (Throwable ignored) { }
    }
}
