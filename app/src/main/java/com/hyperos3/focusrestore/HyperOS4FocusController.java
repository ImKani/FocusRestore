package com.hyperos3.focusrestore;

import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** HyperOS 4 notification state and status-bar rendering bridge. */
final class HyperOS4FocusController {
    interface ItemFactory {
        DisplayItem create(Object notificationEntry);
        boolean isExpired(Object notificationEntry);
        /** Milliseconds until the display limit is reached, or {@link Long#MAX_VALUE} when unlimited. */
        long expiryRemainingMillis(Object notificationEntry);
        /** Called once when this entry actually starts being displayed; starts the display window. */
        void onDisplayed(Object notificationEntry);
        HookSettings settings();
        boolean clickNotificationRow(Object notificationEntry, String key);
        boolean showFocusBanner(View anchor, Object notificationEntry, String key);
        void onNotificationRemoved(Object notificationEntry, String key);
        void dismissFocusBanner(String reason);
    }

    interface Logger {
        void log(String message);
        void error(String stage, Throwable throwable);
    }

    static final class DisplayItem {
        final Object notificationEntry;
        final String key;
        final String packageName;
        final String text;
        final String source;
        final RemoteViews remoteViews;
        final RemoteViews remoteViewsNight;
        final PendingIntent contentIntent;
        final Icon icon;
        final Icon iconDark;
        final boolean tintIcon;
        final boolean tintIconDark;
        final boolean islandIcon;
        final boolean islandIconDark;
        final int priority;
        long updateSequence;

        DisplayItem(Object notificationEntry, String key, String packageName, String text, String source,
                    RemoteViews remoteViews, RemoteViews remoteViewsNight,
                    PendingIntent contentIntent, Icon icon, Icon iconDark,
                    boolean tintIcon, boolean tintIconDark,
                    boolean islandIcon, boolean islandIconDark, int priority) {
            this.notificationEntry = notificationEntry;
            this.key = key;
            this.packageName = packageName;
            this.text = text;
            this.source = source;
            this.remoteViews = remoteViews;
            this.remoteViewsNight = remoteViewsNight;
            this.contentIntent = contentIntent;
            this.icon = icon;
            this.iconDark = iconDark;
            this.tintIcon = tintIcon;
            this.tintIconDark = tintIconDark;
            this.islandIcon = islandIcon;
            this.islandIconDark = islandIconDark;
            this.priority = priority;
        }

        boolean hasContent() {
            return remoteViews != null || remoteViewsNight != null || !TextUtils.isEmpty(text);
        }
    }

    private final ClassLoader classLoader;
    private final Context context;
    private final ItemFactory itemFactory;
    private final Logger logger;
    private final Map<String, DisplayItem> items = new LinkedHashMap<>();
    private final Set<Object> registeredPipelines = Collections.newSetFromMap(
            new WeakHashMap<Object, Boolean>());
    private volatile Object activePipeline;
    private long updateSequence;
    private ViewGroup statusBarRoot;
    private View.OnAttachStateChangeListener statusBarAttachListener;
    private ViewGroup primarySlot;
    private FocusHostView focusHost;
    private long statusBarGeneration;
    private long renderGeneration;
    private boolean renderPosted;
    private FocusHostView renderPostHost;
    private TextView statusBarClock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable expiryCheck = this::runExpiryCheck;
    private String displayedKey;
    private final NotificationIconHider notificationIconHider;
    private final NotificationIconsVisibilityState notificationIconsVisibility =
            new NotificationIconsVisibilityState(View.GONE);
    private View notificationIcons;
    private int notificationIconsId;
    private boolean notificationIconsHideRequested;
    private boolean notificationIconsInternalWrite;
    private boolean notificationIconsMissingLogged;
    private Object darkDispatcher;
    private Object darkReceiver;
    private Class<?> darkDispatcherClass;
    private int currentTint = Color.WHITE;

    HyperOS4FocusController(ClassLoader classLoader, Context context,
                            ItemFactory itemFactory, Logger logger) {
        this.classLoader = classLoader;
        this.context = context;
        this.itemFactory = itemFactory;
        this.logger = logger;
        this.notificationIconHider = new NotificationIconHider(new NotificationIconHider.Logger() {
            @Override public void log(String message) { HyperOS4FocusController.this.logger.log("OS4 " + message); }
            @Override public void error(String stage, Throwable throwable) {
                HyperOS4FocusController.this.logger.error("OS4 " + stage, throwable);
            }
        });
    }

    void install() {
        hookNotifPipeline();
        hookStatusBarView();
        notificationIconHider.install();
        hookClockTint();
    }

    private void hookNotifPipeline() {
        try {
            Class<?> pipelineClass = FocusReflection.findClass(classLoader,
                    "com.android.systemui.statusbar.notification.collection.NotifPipeline");
            XposedBridge.hookAllConstructors(pipelineClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    registerPipeline(param.thisObject);
                }
            });
            logger.log("OS4 notifPipelineListener=hooked");
        } catch (Throwable throwable) {
            logger.error("OS4 hookNotifPipeline", throwable);
        }
    }

    private void registerPipeline(Object pipeline) {
        if (pipeline == null) return;
        synchronized (registeredPipelines) {
            if (!registeredPipelines.add(pipeline)) return;
        }
        try {
            Class<?> listenerClass = FocusReflection.findClass(classLoader,
                    "com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener");
            Object listener = Proxy.newProxyInstance(classLoader, new Class<?>[]{listenerClass},
                    new NotificationListener(pipeline));
            Method addListener = pipeline.getClass().getMethod("addCollectionListener", listenerClass);
            addListener.invoke(pipeline, listener);
        } catch (Throwable throwable) {
            synchronized (registeredPipelines) {
                registeredPipelines.remove(pipeline);
            }
            logger.error("OS4 registerNotifPipeline", throwable);
            return;
        }
        synchronized (items) {
            activePipeline = pipeline;
            items.clear();
        }
        itemFactory.dismissFocusBanner("pipeline-replaced");
        logger.log("OS4 notifPipelineListener=registered activeOwner="
                + System.identityHashCode(pipeline));
        renderBest();
        try {
            Object existing = XposedHelpers.callMethod(pipeline, "getAllNotifs");
            if (existing instanceof Collection) {
                for (Object entry : new ArrayList<>((Collection<?>) existing)) {
                    updateEntry(entry, "initial", pipeline);
                }
            }
        } catch (Throwable throwable) {
            logger.error("OS4 initialNotifSnapshot", throwable);
        }
    }

    private final class NotificationListener implements InvocationHandler {
        private final Object owner;

        NotificationListener(Object owner) {
            this.owner = owner;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("toString".equals(name)) return "HyperOS4FocusRestoreNotifListener";
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) return args != null && args.length == 1 && proxy == args[0];
            if (owner != activePipeline) return null;
            try {
                if (("onEntryAdded".equals(name) || "onEntryUpdated".equals(name)
                        || "onEntryBind".equals(name)) && args != null && args.length > 0) {
                    updateEntry(args[0], name, owner);
                } else if (("onEntryRemoved".equals(name) || "onEntryCleanUp".equals(name))
                        && args != null && args.length > 0) {
                    removeEntry(args[0], name, owner);
                }
            } catch (Throwable throwable) {
                logger.error("OS4 listener " + name, throwable);
            }
            return null;
        }
    }

    private void updateEntry(Object entry, String stage, Object owner) {
        if (entry == null || owner != activePipeline) return;
        DisplayItem item = itemFactory.create(entry);
        String key = item == null ? entryKey(entry) : item.key;
        synchronized (items) {
            if (owner != activePipeline) return;
            if (item == null || TextUtils.isEmpty(key) || !item.hasContent()) {
                if (!TextUtils.isEmpty(key)) items.remove(key);
            } else {
                item.updateSequence = ++updateSequence;
                items.put(key, item);
                logger.log("OS4 candidate " + stage + " key=" + key
                        + " priority=" + item.priority + " source=" + item.source
                        + " remoteViews=" + (item.remoteViews != null)
                        + " remoteViewsNight=" + (item.remoteViewsNight != null)
                        + " text=" + item.text);
            }
        }
        renderBest();
    }

    private void removeEntry(Object entry, String stage, Object owner) {
        if (owner != activePipeline) return;
        String key = entryKey(entry);
        if (TextUtils.isEmpty(key)) return;
        synchronized (items) {
            if (owner != activePipeline) return;
            DisplayItem current = items.get(key);
            if (current != null && current.notificationEntry != entry) return;
            items.remove(key);
        }
        itemFactory.onNotificationRemoved(entry, key);
        logger.log("OS4 candidate " + stage + " key=" + key);
        renderBest();
    }

    private String entryKey(Object entry) {
        Object value = field(entry, "key");
        if (value == null) value = field(entry, "mKey");
        return value == null ? null : String.valueOf(value);
    }

    private void hookStatusBarView() {
        try {
            Class<?> statusBarView = FocusReflection.findClass(classLoader,
                    "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView");
            XposedBridge.hookAllMethods(statusBarView, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof ViewGroup) {
                        attachStatusBar((ViewGroup) param.thisObject);
                    }
                }
            });
            XposedBridge.hookAllMethods(statusBarView, "onConfigurationChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    renderBest();
                }
            });
            logger.log("OS4 statusBarPrimarySlot=hooked");
        } catch (Throwable throwable) {
            logger.error("OS4 hookStatusBarView", throwable);
        }
    }

    private void hookNotificationIconsVisibility() {
        try {
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (notificationIconsInternalWrite || !notificationIconsHideRequested
                                    || !(param.thisObject instanceof View)
                                    || param.args == null || param.args.length == 0
                                    || !(param.args[0] instanceof Integer)) {
                                return;
                            }
                            View view = (View) param.thisObject;
                            int requestedVisibility = (Integer) param.args[0];
                            if (view != notificationIcons) {
                                if (notificationIconsId == 0 || view.getId() != notificationIconsId
                                        || !isDescendantOf(view, statusBarRoot)) {
                                    return;
                                }
                                replaceTrackedNotificationIcons(view, requestedVisibility);
                            }
                            int previousDesired = notificationIconsVisibility.desiredVisibility();
                            int appliedVisibility = notificationIconsVisibility.onVisibilityRequested(
                                    requestedVisibility);
                            if (appliedVisibility != requestedVisibility) {
                                param.args[0] = appliedVisibility;
                                if (previousDesired != requestedVisibility) {
                                    logger.log("OS4 notificationIcons visibility intercepted requested="
                                            + requestedVisibility + " applied=" + appliedVisibility
                                            + " desired="
                                            + notificationIconsVisibility.desiredVisibility());
                                }
                            }
                        }
                    });
            logger.log("OS4 notificationIconsVisibilityHook=hooked");
        } catch (Throwable throwable) {
            logger.error("OS4 hookNotificationIconsVisibility", throwable);
        }
    }

    private void hookNotificationIconsAttachment() {
        try {
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (notificationIconsInternalWrite || !notificationIconsHideRequested
                                    || !(param.thisObject instanceof View)) {
                                return;
                            }
                            View view = (View) param.thisObject;
                            if (notificationIconsId == 0 || view.getId() != notificationIconsId
                                    || !isDescendantOf(view, statusBarRoot)) {
                                return;
                            }
                            if (view != notificationIcons) {
                                replaceTrackedNotificationIcons(view, view.getVisibility());
                            } else if (!notificationIconsVisibility.isHiding()) {
                                notificationIconsVisibility.beginHiding(view.getVisibility());
                            }
                            setNotificationIconsVisibilityInternal(view, View.GONE);
                            logger.log("OS4 notificationIcons=GONE id=" + view.getId()
                                    + " source=attached desired="
                                    + notificationIconsVisibility.desiredVisibility());
                        }
                    });
            logger.log("OS4 notificationIconsAttachmentHook=hooked");
        } catch (Throwable throwable) {
            logger.error("OS4 hookNotificationIconsAttachment", throwable);
        }
    }

    private void hookClockTint() {
        try {
            Class<?> clockClass = FocusReflection.findClass(classLoader,
                    "com.android.systemui.statusbar.views.MiuiClock");
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    clockClass, "onDarkChanged", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject == statusBarClock) {
                                updateTint(((TextView) param.thisObject).getCurrentTextColor(),
                                        "MiuiClock.onDarkChanged");
                            }
                        }
                    });
            logger.log("OS4 clockTintHook=" + (hooks.isEmpty() ? "missing" : "hooked")
                    + " count=" + hooks.size());
        } catch (Throwable throwable) {
            logger.error("OS4 hookClockTint", throwable);
        }
    }

    private synchronized void attachStatusBar(final ViewGroup statusBarView) {
        try {
            int primaryId = context.getResources().getIdentifier(
                    "ongoing_activity_chip_primary", "id", "com.android.systemui");
            View view = primaryId == 0 ? null : statusBarView.findViewById(primaryId);
            ViewGroup resolvedSlot = view instanceof ViewGroup ? (ViewGroup) view : null;
            if (statusBarRoot == statusBarView && focusHost != null
                    && primarySlot == resolvedSlot && resolvedSlot != null
                    && focusHost.getParent() == resolvedSlot
                    && isDescendantOf(resolvedSlot, statusBarView)) {
                renderBest();
                return;
            }
            teardownStatusBar(true);
            statusBarRoot = statusBarView;
            statusBarAttachListener = new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {
                    if (view == statusBarRoot && focusHost == null) {
                        attachStatusBar(statusBarView);
                    }
                }

                @Override public void onViewDetachedFromWindow(View view) {
                    if (view == statusBarRoot) teardownStatusBar(false);
                }
            };
            statusBarView.addOnAttachStateChangeListener(statusBarAttachListener);

            if (resolvedSlot == null) {
                logger.log("OS4 statusBarPrimarySlot=missing id=" + primaryId);
                return;
            }
            ViewGroup slot = resolvedSlot;
            FocusHostView host = new FocusHostView(slot.getContext());
            host.setId(View.generateViewId());
            host.setVisibility(View.GONE);
            slot.addView(host, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            primarySlot = slot;
            focusHost = host;
            notificationIconsId = statusBarView.getResources().getIdentifier(
                    "notificationIcons", "id", context.getPackageName());
            View resolvedNotificationIcons = notificationIconsId == 0
                    ? null : statusBarView.findViewById(notificationIconsId);
            notificationIconHider.bindRoot(resolvedNotificationIcons instanceof ViewGroup
                    ? (ViewGroup) resolvedNotificationIcons : null, notificationIconsId);
            resolveStatusBarClock();
            registerDarkReceiver();
            // HyperOS 4 hides this legacy XML slot before installing its Compose
            // chip. Preserve that inactive state so the XML defaults (phone icon,
            // 00:00:00 chronometer and background) never leak between candidates.
            slot.setVisibility(View.GONE);
            logger.log("OS4 statusBarPrimarySlot=attached id=" + primaryId
                    + " legacyInactiveVisibility=GONE children=" + slot.getChildCount());
            renderBest();
        } catch (Throwable throwable) {
            logger.error("OS4 attachStatusBar", throwable);
            teardownStatusBar(false);
        }
    }

    private synchronized void teardownStatusBar(boolean removeAttachListener) {
        statusBarGeneration++;
        renderGeneration++;
        renderPosted = false;
        renderPostHost = null;
        mainHandler.removeCallbacks(expiryCheck);
        restoreNotificationIcons();
        notificationIconHider.clear();
        unregisterDarkReceiver();
        FocusHostView host = focusHost;
        focusHost = null;
        primarySlot = null;
        statusBarClock = null;
        notificationIconsId = 0;
        if (host != null) {
            host.clearContent();
            if (host.getParent() instanceof ViewGroup) {
                ((ViewGroup) host.getParent()).removeView(host);
            }
        }
        if (removeAttachListener) {
            ViewGroup root = statusBarRoot;
            if (root != null && statusBarAttachListener != null) {
                root.removeOnAttachStateChangeListener(statusBarAttachListener);
            }
            statusBarAttachListener = null;
            statusBarRoot = null;
        }
    }

    private synchronized void renderBest() {
        renderGeneration++;
        FocusHostView host = focusHost;
        if (host == null) return;
        if (renderPosted && renderPostHost == host) return;
        renderPosted = true;
        renderPostHost = host;
        if (!host.post(() -> drainRender(host))) {
            renderPosted = false;
            renderPostHost = null;
            logger.log("OS4 render post rejected hostDetached=true");
        }
    }

    private void drainRender(FocusHostView host) {
        final long generation;
        synchronized (this) {
            if (!renderPosted || renderPostHost != host || host != focusHost) return;
            renderPosted = false;
            renderPostHost = null;
            generation = renderGeneration;
        }
        final DisplayItem best;
        synchronized (items) {
            DisplayItem selected = null;
            for (DisplayItem candidate : items.values()) {
                if (itemFactory.isExpired(candidate.notificationEntry)) continue;
                if (selected == null || OS4FocusPriorityPolicy.compare(
                        candidate.priority, candidate.updateSequence,
                        selected.priority, selected.updateSequence) > 0) {
                    selected = candidate;
                }
            }
            best = selected;
        }
        render(host, best, generation);
        if (best == null) {
            displayedKey = null;
        } else if (!best.key.equals(displayedKey)) {
            // The display window starts here, not at the notification post time.
            displayedKey = best.key;
            itemFactory.onDisplayed(best.notificationEntry);
        }
        scheduleExpiryCheck(best);
    }

    /**
     * The candidate list only changes on notification events, so a displayed focus with a finite
     * limit needs its own wake-up to disappear exactly when the user's time is up.
     */
    private void scheduleExpiryCheck(DisplayItem item) {
        mainHandler.removeCallbacks(expiryCheck);
        if (item == null) return;
        long remaining = itemFactory.expiryRemainingMillis(item.notificationEntry);
        if (remaining == Long.MAX_VALUE) return;
        mainHandler.postDelayed(expiryCheck, Math.max(0L, remaining) + 32L);
    }

    private void runExpiryCheck() {
        FocusHostView host = focusHost;
        if (host == null) return;
        logger.log("OS4 display limit reached; re-evaluating focus candidates");
        renderBest();
    }

    private synchronized boolean isRenderCurrent(FocusHostView host, long generation) {
        return host == focusHost && generation == renderGeneration;
    }

    private void render(FocusHostView host, DisplayItem item, long generation) {
        if (!isRenderCurrent(host, generation)) return;
        if (item == null) {
            synchronized (this) {
                if (host != focusHost || generation != renderGeneration) return;
                host.clearContent();
                host.setVisibility(View.GONE);
                ViewGroup slot = primarySlot;
                if (slot != null) slot.setVisibility(View.GONE);
                restoreNotificationIcons();
                logger.log("OS4 focus hidden; no eligible notification legacySlot=GONE"
                        + " notificationIconsRestored=true");
            }
            return;
        }
        HookSettings settings = itemFactory.settings();
        boolean night = (host.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        RemoteViews selected = night
                ? (item.remoteViewsNight != null ? item.remoteViewsNight : item.remoteViews)
                : (item.remoteViews != null ? item.remoteViews : item.remoteViewsNight);
        View content = null;
        if (selected != null) {
            try {
                content = selected.apply(host.getContext(), host);
            } catch (Throwable throwable) {
                logger.error("OS4 applyRemoteViews key=" + item.key, throwable);
            }
        }
        if (content == null && !TextUtils.isEmpty(item.text)) {
            TextView textView = new TextView(host.getContext());
            textView.setText(item.text);
            textView.setTextSize(14f);
            textView.setTextColor(currentTint);
            textView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            textView.setSingleLine(true);
            textView.setEllipsize(null);
            textView.setIncludeFontPadding(false);
            content = textView;
        }
        if (!isRenderCurrent(host, generation)) return;
        if (content == null) {
            boolean removed = false;
            synchronized (items) {
                if (items.get(item.key) == item) {
                    items.remove(item.key);
                    removed = true;
                }
            }
            logger.log("OS4 candidate renderFailed key=" + item.key
                    + " removedCurrent=" + removed);
            if (removed) renderBest();
            return;
        }
        synchronized (this) {
            if (host != focusHost || generation != renderGeneration) return;
            hideOriginalChildren();
            setNotificationIconsHidden(settings.hideNotificationIcons);
            host.setVisibility(View.VISIBLE);
            host.showContent(content, item, settings);
            logger.log("OS4 focus shown key=" + item.key + " package=" + item.packageName
                    + " source=" + item.source + " priority=" + item.priority
                    + " widthDp=" + settings.widthDp + " limit=" + settings.limitWidth
                    + " maxWidthPx=" + host.maxWidthPx
                    + " hideNotificationIcons=" + settings.hideNotificationIcons
                    + " showFocusDivider=" + settings.showFocusDivider
                    + " tint=0x" + Integer.toHexString(currentTint)
                    + " legacyClick=" + settings.allowFocusClick
                    + " rowClickFallback=" + settings.notificationRowClickFallback
                    + " independentFocusBanner=" + settings.independentFocusBanner);
        }
    }

    private void resolveStatusBarClock() {
        ViewGroup root = statusBarRoot;
        if (root == null) return;
        int id = root.getResources().getIdentifier("clock", "id", context.getPackageName());
        View view = id == 0 ? null : root.findViewById(id);
        statusBarClock = view instanceof TextView ? (TextView) view : null;
        if (statusBarClock != null) {
            currentTint = statusBarClock.getCurrentTextColor();
            logger.log("OS4 statusBarClock=resolved tint=0x"
                    + Integer.toHexString(currentTint));
        } else {
            logger.log("OS4 statusBarClock=missing id=" + id);
        }
    }

    private void registerDarkReceiver() {
        unregisterDarkReceiver();
        try {
            darkDispatcherClass = FocusReflection.findClass(classLoader,
                    "com.android.systemui.plugins.DarkIconDispatcher");
            Class<?> receiverClass = FocusReflection.findClass(classLoader,
                    "com.android.systemui.plugins.DarkIconDispatcher$DarkReceiver");
            Class<?> dependencyClass = FocusReflection.findClass(classLoader,
                    "com.android.systemui.Dependency");
            darkDispatcher = XposedHelpers.callStaticMethod(
                    dependencyClass, "get", darkDispatcherClass);
            final long ownerGeneration = statusBarGeneration;
            darkReceiver = Proxy.newProxyInstance(classLoader,
                    new Class<?>[]{receiverClass}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if (("onDarkChanged".equals(name)
                                    || "onDarkChangedWithContrast".equals(name))
                                    && args != null && args.length >= 3
                                    && args[2] instanceof Integer) {
                                if (proxy != darkReceiver || ownerGeneration != statusBarGeneration) {
                                    return null;
                                }
                                int tint = resolveTint(args[0], (Integer) args[2]);
                                updateTint(tint, "DarkIconDispatcher." + name);
                            } else if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            } else if ("equals".equals(name)) {
                                return args != null && args.length == 1 && proxy == args[0];
                            } else if ("toString".equals(name)) {
                                return "FocusRestoreDarkReceiver";
                            }
                            return null;
                        }
                    });
            XposedHelpers.callMethod(darkDispatcher, "addDarkReceiver", darkReceiver);
            try {
                XposedHelpers.callMethod(darkDispatcher, "applyDark", darkReceiver);
            } catch (Throwable throwable) {
                logger.error("OS4 applyInitialDark", throwable);
            }
            logger.log("OS4 darkReceiver=registered dispatcher="
                    + darkDispatcher.getClass().getName());
        } catch (Throwable throwable) {
            darkDispatcher = null;
            darkReceiver = null;
            darkDispatcherClass = null;
            logger.error("OS4 registerDarkReceiver", throwable);
        }
    }

    private int resolveTint(Object areas, int fallbackTint) {
        View tintReference = statusBarClock != null ? statusBarClock : focusHost;
        if (tintReference == null || darkDispatcherClass == null) return fallbackTint;
        try {
            Object value = XposedHelpers.callStaticMethod(
                    darkDispatcherClass, "getTint", areas, tintReference, fallbackTint);
            return value instanceof Integer ? (Integer) value : fallbackTint;
        } catch (Throwable throwable) {
            logger.error("OS4 resolveDarkTint", throwable);
            return fallbackTint;
        }
    }

    private void unregisterDarkReceiver() {
        if (darkDispatcher == null || darkReceiver == null) return;
        try {
            XposedHelpers.callMethod(darkDispatcher, "removeDarkReceiver", darkReceiver);
        } catch (Throwable throwable) {
            logger.error("OS4 unregisterDarkReceiver", throwable);
        } finally {
            darkDispatcher = null;
            darkReceiver = null;
            darkDispatcherClass = null;
        }
    }

    private void updateTint(int tint, String source) {
        if (currentTint == tint) return;
        currentTint = tint;
        FocusHostView host = focusHost;
        if (host != null) {
            applyTint(host);
            host.updateDividerTint();
            host.refreshTintedIcon();
        }
        logger.log("OS4 tint updated source=" + source + " tint=0x"
                + Integer.toHexString(tint));
    }

    private void applyTint(View view) {
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(currentTint);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                applyTint(group.getChildAt(index));
            }
        }
    }

    private void setNotificationIconsHidden(boolean hidden) {
        notificationIconsHideRequested = hidden;
        HookSettings settings = itemFactory.settings();
        int hideMode = settings == null ? NotificationIconHider.MODE_CONTAINER
                : settings.notificationIconHideMode;
        notificationIconHider.request(hidden, hideMode);
        if (hidden && hideMode == NotificationIconHider.MODE_SYSTEM_ONLY) {
            logger.log("OS4 notificationIcons selective request applied; container preserved");
            return;
        }
        if (!hidden) {
            restoreTrackedNotificationIcons();
            return;
        }
        View icons = resolveNotificationIcons();
        if (icons == null) {
            if (!notificationIconsMissingLogged) {
                notificationIconsMissingLogged = true;
                logger.log("OS4 notificationIcons=missing requestedHidden=true source=render");
            }
            return;
        }
        notificationIconsMissingLogged = false;
        if (notificationIcons != icons) {
            replaceTrackedNotificationIcons(icons, icons.getVisibility());
        } else if (!notificationIconsVisibility.isHiding()) {
            notificationIconsVisibility.beginHiding(icons.getVisibility());
        }
        setNotificationIconsVisibilityInternal(icons, View.GONE);
        logger.log("OS4 notificationIcons=GONE id=" + icons.getId()
                + " source=render desired="
                + notificationIconsVisibility.desiredVisibility());
    }

    private View resolveNotificationIcons() {
        ViewGroup root = statusBarRoot;
        if (root == null || notificationIconsId == 0) return null;
        View icons = notificationIcons;
        if (icons != null && icons.getId() == notificationIconsId
                && isDescendantOf(icons, root)) {
            return icons;
        }
        View resolved = root.findViewById(notificationIconsId);
        return resolved == root ? null : resolved;
    }

    private void replaceTrackedNotificationIcons(View replacement, int desiredVisibility) {
        restoreTrackedNotificationIcons();
        notificationIcons = replacement;
        notificationIconsVisibility.beginHiding(desiredVisibility);
        logger.log("OS4 notificationIcons=tracked id=" + replacement.getId()
                + " desired=" + desiredVisibility);
    }

    private void restoreNotificationIcons() {
        notificationIconsHideRequested = false;
        notificationIconsMissingLogged = false;
        restoreTrackedNotificationIcons();
    }

    private void restoreTrackedNotificationIcons() {
        View icons = notificationIcons;
        if (icons == null || !notificationIconsVisibility.isHiding()) {
            notificationIcons = null;
            notificationIconsVisibility.reset();
            return;
        }
        int restoreVisibility = notificationIconsVisibility.finishHiding();
        setNotificationIconsVisibilityInternal(icons, restoreVisibility);
        logger.log("OS4 notificationIcons=restored id=" + icons.getId()
                + " visibility=" + restoreVisibility);
        notificationIcons = null;
    }

    private void setNotificationIconsVisibilityInternal(View view, int visibility) {
        notificationIconsInternalWrite = true;
        try {
            view.setVisibility(visibility);
        } finally {
            notificationIconsInternalWrite = false;
        }
    }

    private static boolean isDescendantOf(View view, ViewGroup ancestor) {
        if (view == null || ancestor == null) return false;
        View current = view;
        while (current != null) {
            if (current == ancestor) return true;
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private void hideOriginalChildren() {
        ViewGroup slot = primarySlot;
        FocusHostView host = focusHost;
        if (slot == null || host == null) return;
        for (int index = 0; index < slot.getChildCount(); index++) {
            View child = slot.getChildAt(index);
            if (child != host) child.setVisibility(View.GONE);
        }
        slot.setVisibility(View.VISIBLE);
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        try {
            return XposedHelpers.getObjectField(target, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    boolean isVisibleFocusRegion(float x, float y, int displayId) {
        FocusHostView host = focusHost;
        return host != null && host.content != null && !TextUtils.isEmpty(host.currentItemKey)
                && FocusScrollToTopGuard.containsVisible(host, x, y, displayId);
    }

    private final class FocusHostView extends FrameLayout {
        private ValueAnimator animator;
        private Runnable pendingAnimation;
        private View content;
        private View divider;
        private ImageView iconView;
        private boolean tintCurrentIcon;
        private boolean currentIslandIcon;
        private Icon currentIcon;
        private int currentIconSizeDp;
        private int contentInsetPx;
        private int maxWidthPx = Integer.MAX_VALUE;
        private boolean blockClicks = true;
        private boolean bannerClicks;
        private final int bannerTouchSlop;
        private float bannerDownX;
        private float bannerDownY;
        private boolean bannerGestureCancelled;
        private boolean notificationRowClicks;
        private String currentItemKey;
        private Object currentNotificationEntry;
        private PendingIntent currentContentIntent;

        FocusHostView(Context context) {
            super(context);
            bannerTouchSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
            setClipChildren(true);
            setClipToPadding(true);
        }

        void showContent(View nextContent, DisplayItem item, HookSettings settings) {
            boolean sameBannerTarget = bannerClicks && settings.independentFocusBanner
                    && currentNotificationEntry == item.notificationEntry
                    && TextUtils.equals(currentItemKey, item.key);
            clearContent(sameBannerTarget);
            bannerClicks = settings.independentFocusBanner;
            blockClicks = !settings.allowFocusClick && !bannerClicks;
            notificationRowClicks = !bannerClicks && settings.allowFocusClick
                    && settings.notificationRowClickFallback;
            currentItemKey = item.key;
            currentNotificationEntry = item.notificationEntry;
            currentContentIntent = item.contentIntent;
            setClickable(bannerClicks || notificationRowClicks);
            setFocusable(bannerClicks || notificationRowClicks);
            setContentDescription(bannerClicks ? "展开焦点通知："
                    + (TextUtils.isEmpty(item.text) ? item.packageName : item.text) : null);
            float density = getResources().getDisplayMetrics().density;
            boolean landscape = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            int configuredWidthDp = landscape ? settings.widthLandscapeDp : settings.widthDp;
            maxWidthPx = settings.limitWidth
                    ? Math.max(1, Math.round(configuredWidthDp * density)) : Integer.MAX_VALUE;
            logger.log("OS4 focus width select orientation=" + (landscape ? "landscape" : "portrait")
                    + " widthDp=" + configuredWidthDp + " portraitDp=" + settings.widthDp
                    + " landscapeDp=" + settings.widthLandscapeDp);
            if (settings.showFocusDivider) {
                int dividerWidth = Math.max(1, Math.round(density));
                int dividerHeight = Math.max(1, Math.round(12f * density));
                contentInsetPx = dividerWidth + Math.max(1, Math.round(6f * density));
                divider = new View(getContext());
                divider.setBackgroundColor(currentTint);
                divider.setAlpha(0.45f);
                LayoutParams dividerParams = new LayoutParams(
                        dividerWidth, dividerHeight, Gravity.CENTER_VERTICAL | Gravity.START);
                addView(divider, dividerParams);
            }
            boolean usingDarkIcon = isNightMode() && item.iconDark != null;
            Icon selectedIcon = usingDarkIcon ? item.iconDark : item.icon;
            if (selectedIcon != null && nextContent instanceof TextView) {
                try {
                    currentIconSizeDp = 13;
                    int iconSize = Math.max(1, Math.round(currentIconSizeDp * density));
                    int iconGap = Math.max(1, Math.round(4f * density));
                    iconView = new ImageView(getContext());
                    iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    tintCurrentIcon = usingDarkIcon ? item.tintIconDark : item.tintIcon;
                    currentIslandIcon = usingDarkIcon ? item.islandIconDark : item.islandIcon;
                    currentIcon = selectedIcon;
                    FocusIconStyler.Result iconResult = FocusIconStyler.load(getContext(),
                            selectedIcon, currentIslandIcon, tintCurrentIcon,
                            currentTint, currentIconSizeDp);
                    if (iconResult == null) {
                        throw new IllegalStateException("Icon.loadDrawable returned null package="
                                + item.packageName + " type=" + selectedIcon.getType());
                    }
                    iconView.setImageDrawable(iconResult.drawable);
                    LayoutParams iconParams = new LayoutParams(
                            iconSize, iconSize, Gravity.CENTER_VERTICAL | Gravity.START);
                    iconParams.setMarginStart(contentInsetPx);
                    addView(iconView, iconParams);
                    bindClick(iconView, item, settings);
                    contentInsetPx += iconSize + iconGap;
                    logger.log("OS4 focus icon attached key=" + item.key
                            + " tinted=" + tintCurrentIcon);
                } catch (Throwable throwable) {
                    iconView = null;
                    tintCurrentIcon = false;
                    logger.error("OS4 load focus icon key=" + item.key, throwable);
                }
            }
            content = nextContent;
            LayoutParams params = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER_VERTICAL | Gravity.START);
            params.setMarginStart(contentInsetPx);
            addView(nextContent, params);
            applyTint(nextContent);
            if (!(nextContent instanceof ViewGroup)) bindClick(nextContent, item, settings);
            requestLayout();
            long delay = Math.max(0L, Math.min(5000L, settings.marqueeDelayMs));
            pendingAnimation = () -> startScroll(settings.marqueeBounce);
            postDelayed(pendingAnimation, delay);
            logger.log("OS4 marquee scheduled key=" + item.key + " delayMs=" + delay
                    + " bounce=" + settings.marqueeBounce);
        }

        private void bindClick(View target, DisplayItem item, HookSettings settings) {
            if (settings.independentFocusBanner || !settings.allowFocusClick) return;
            if (settings.notificationRowClickFallback) {
                target.setOnClickListener(view -> itemFactory.clickNotificationRow(
                        item.notificationEntry, item.key));
            } else if (item.contentIntent != null) {
                target.setOnClickListener(view -> send(item.contentIntent, item.key));
            }
        }

        private boolean isNightMode() {
            return (getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        }

        void clearContent() {
            clearContent(false);
        }

        private void clearContent(boolean preserveBannerGesture) {
            if (!preserveBannerGesture) {
                cancelPendingInputEvents();
                setPressed(false);
                bannerGestureCancelled = true;
            }
            if (pendingAnimation != null) {
                removeCallbacks(pendingAnimation);
                pendingAnimation = null;
            }
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (content != null) content.setTranslationX(0f);
            removeAllViews();
            content = null;
            divider = null;
            iconView = null;
            tintCurrentIcon = false;
            currentIslandIcon = false;
            currentIcon = null;
            currentIconSizeDp = 0;
            contentInsetPx = 0;
            bannerClicks = false;
            notificationRowClicks = false;
            currentItemKey = null;
            currentNotificationEntry = null;
            currentContentIntent = null;
            setClickable(false);
            setFocusable(false);
            setContentDescription(null);
        }

        private void startScroll(boolean bounce) {
            pendingAnimation = null;
            View child = content;
            if (child == null || getVisibility() != View.VISIBLE) return;
            int availableWidth = Math.max(1, getWidth() - contentInsetPx);
            int distance = child.getMeasuredWidth() - availableWidth;
            if (distance <= 0 && child instanceof TextView) {
                TextView text = (TextView) child;
                distance = Math.round(text.getPaint().measureText(String.valueOf(text.getText())))
                        - availableWidth + text.getPaddingLeft() + text.getPaddingRight();
            }
            if (distance <= 0) {
                logger.log("OS4 marquee not needed contentWidth=" + child.getMeasuredWidth()
                        + " hostWidth=" + getWidth() + " maxWidthPx=" + maxWidthPx
                        + " contentInsetPx=" + contentInsetPx);
                return;
            }
            float direction = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1f : -1f;
            animator = bounce
                    ? ValueAnimator.ofFloat(0f, direction * distance, 0f)
                    : ValueAnimator.ofFloat(0f, direction * distance);
            animator.setDuration(Math.max(2500L, distance * 35L));
            animator.setInterpolator(new LinearInterpolator());
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(value -> {
                if (content == child && getVisibility() == View.VISIBLE) {
                    child.setTranslationX((Float) value.getAnimatedValue());
                }
            });
            animator.start();
            logger.log("OS4 marquee started distance=" + distance + " bounce=" + bounce
                    + " contentWidth=" + child.getMeasuredWidth()
                    + " hostWidth=" + getWidth() + " maxWidthPx=" + maxWidthPx);
        }

        void updateDividerTint() {
            if (divider != null) divider.setBackgroundColor(currentTint);
        }

        void refreshTintedIcon() {
            if (iconView == null || currentIcon == null || !tintCurrentIcon) return;
            try {
                FocusIconStyler.Result result = FocusIconStyler.load(getContext(), currentIcon,
                        currentIslandIcon, true, currentTint, currentIconSizeDp);
                if (result != null) iconView.setImageDrawable(result.drawable);
            } catch (Throwable throwable) {
                logger.error("OS4 refresh tinted island icon", throwable);
            }
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            renderBest();
        }

        @Override
        protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
            if (child != content || contentInsetPx <= 0) {
                return super.drawChild(canvas, child, drawingTime);
            }
            int saveCount = canvas.save();
            if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                canvas.clipRect(0, 0, getWidth() - contentInsetPx, getHeight());
            } else {
                canvas.clipRect(contentInsetPx, 0, getWidth(), getHeight());
            }
            boolean drawn = super.drawChild(canvas, child, drawingTime);
            canvas.restoreToCount(saveCount);
            return drawn;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            return blockClicks || bannerClicks || notificationRowClicks
                    || super.onInterceptTouchEvent(event);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (blockClicks) return true;
            if (bannerClicks) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    bannerDownX = event.getX();
                    bannerDownY = event.getY();
                    bannerGestureCancelled = false;
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    bannerGestureCancelled = true;
                } else if (!bannerGestureCancelled
                        && (action == MotionEvent.ACTION_POINTER_DOWN
                        || (action == MotionEvent.ACTION_MOVE
                        && (Math.abs(event.getX() - bannerDownX) > bannerTouchSlop
                        || Math.abs(event.getY() - bannerDownY) > bannerTouchSlop)))) {
                    bannerGestureCancelled = true;
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    try { super.onTouchEvent(cancel); }
                    finally { cancel.recycle(); }
                    return true;
                } else if (bannerGestureCancelled) {
                    return true;
                }
                // Let the host's normal click/accessibility path perform the request.
                return super.onTouchEvent(event);
            }
            if (notificationRowClicks) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            }
            return super.onTouchEvent(event);
        }

        @Override
        public boolean performClick() {
            if (bannerClicks) {
                super.performClick();
                Object entry = currentNotificationEntry;
                String key = currentItemKey;
                boolean current;
                synchronized (items) {
                    DisplayItem item = items.get(key);
                    current = item != null && item.notificationEntry == entry;
                }
                if (current) {
                    boolean accepted = itemFactory.showFocusBanner(this, entry, key);
                    logger.log("OS4 independent banner request key=" + key
                            + " accepted=" + accepted);
                } else {
                    logger.log("OS4 independent banner rejected key=" + key + " reason=stale-entry");
                }
                return true;
            }
            if (notificationRowClicks) {
                super.performClick();
                if (!itemFactory.clickNotificationRow(currentNotificationEntry, currentItemKey)
                        && currentContentIntent != null) {
                    send(currentContentIntent, currentItemKey);
                }
                return true;
            }
            return super.performClick();
        }

        @Override
        protected void onDetachedFromWindow() {
            clearContent();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int unlimitedWidth = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            super.onMeasure(unlimitedWidth, heightMeasureSpec);
            int width = getMeasuredWidth();
            int mode = MeasureSpec.getMode(widthMeasureSpec);
            if (mode != MeasureSpec.UNSPECIFIED) {
                width = Math.min(width, MeasureSpec.getSize(widthMeasureSpec));
            }
            width = Math.min(width, maxWidthPx);
            setMeasuredDimension(Math.max(1, width), getMeasuredHeight());
        }
    }

    private void send(PendingIntent intent, String key) {
        try {
            intent.send();
            logger.log("OS4 focus click sent key=" + key);
        } catch (PendingIntent.CanceledException exception) {
            logger.error("OS4 focus click canceled key=" + key, exception);
        }
    }
}
