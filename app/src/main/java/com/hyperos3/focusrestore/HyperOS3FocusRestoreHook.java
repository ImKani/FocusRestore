/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.animation.ValueAnimator;
import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.media.session.MediaController;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import org.json.JSONObject;
import android.text.TextUtils;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HyperOS3FocusRestoreHook implements IXposedHookLoadPackage {
    private static final String TAG = "HyperOS3FocusRestore";
    private static final String SYSTEM_UI = "com.android.systemui";

    // OS3 rejects the legacy miui.focus.rv when used as contentRemoteViews.
    private static final boolean FALLBACK_MAIN_RV_FOR_STATUS_BAR = false;

    /** Verbose diagnostics are debug-build only; see {@link #debug(String)}. */
    private static final boolean VERBOSE_LOG = BuildConfig.DEBUG;
    /** Cap on remembered notification keys for one-shot extras diagnosis. */
    private static final int MAX_DIAGNOSED_KEYS = 64;
    /** Insertion-ordered so eviction drops the oldest diagnosed key first. */
    private final Set<String> diagnosedExtrasKeys =
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>());
    /**
     * Island text most recently applied to each prompt view. {@code updateRemoteViews} runs on every
     * layout pass (the marquee animation alone causes many), so this lets a repeat pass skip both the
     * payload re-parse and the redundant view writes.
     */
    private final Map<Object, String> appliedIslandTexts = new WeakHashMap<>();
    /**
     * Notification keys whose focus prompt this module has already forced visible once. Forcing
     * {@code shouldShow} on every false leaves a stale icon after the user clicks the prompt, so the
     * override is deliberately one-shot: it pushes the converted notification onto the status bar and
     * then hands the prompt lifecycle back to HyperOS.
     */
    private final Set<String> forcedShouldShowKeys = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final int MAX_FORCED_SHOULD_SHOW_KEYS = 64;

    private static final long SETTINGS_REFRESH_INTERVAL_MS = 1000L;
    private static final long CONVERTED_KEY_TTL_MS = 10L * 60L * 1000L;
    private static final long MARQUEE_ATTACH_TIMEOUT_MS = 2000L;
    private static final int MAX_CONVERTED_KEYS = 128;
    private static final String[] REMOTE_VIEWS_CONTAINER_FIELDS = {
            "mRemoteView", "mRemoteViews", "mRemoteViewContainer",
            "mContentRemoteView", "mContentRemoteViews", "mCustomViewContainer"
    };

    private static final Set<ClassLoader> INSTALLED_CLASS_LOADERS =
            Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());
    private static final Object INSTALL_LOCK = new Object();
    private static final Object SETTINGS_READ_LOCK = new Object();
    private static final ExecutorService SETTINGS_EXECUTOR =
            Executors.newSingleThreadExecutor(command -> {
                Thread thread = new Thread(command, TAG + "-settings");
                thread.setDaemon(true);
                return thread;
            });

    private ClassLoader classLoader;
    /** Island view blocking is installed per plugin loader and re-armed when the plugin unloads. */
    private volatile boolean islandViewsBlocked;
    /** 设备通知的构造焦点通知通路；首次使用时创建。 */
    private DeviceNotificationFocusPoster deviceNotificationFocusPoster;
    /** 每次设备通知请求的代次与已确认投递的代次，只用于把投递结果按次记录，不参与任何呈现决策。 */
    private long deviceFocusPostGeneration;
    private long deviceFocusDeliveredGeneration = -1;
    private int pendingDeviceFocusId = -1;
    private String activeDeviceEventId;
    private long deviceChargeDeadline;
    private long lastDeviceChargeUpdate;
    private boolean deviceChargeSessionClosed;
    /** 同一同步命令经过 listener / StrongToast 时只投递一次；不按文案或时间猜测并吞掉后续事件。 */
    private final ThreadLocal<Boolean> deviceConversionPosted = new ThreadLocal<>();
    private volatile WeakReference<Object> deviceListenerRef = new WeakReference<>(null);
    private final DeviceChargingEventPolicy deviceChargingPolicy = new DeviceChargingEventPolicy();
    private volatile Context systemUiContext;
    // FocusedTextView.startMarqueeLocal() copies this value into TextView.
    // -1 keeps long lyrics moving instead of stopping after one pass.
    private static final int MARQUEE_REPEAT_LIMIT = -1;
    private volatile HookSettings currentSettings = HookSettings.defaults();
    private long lastProviderReadAttemptMs = Long.MIN_VALUE;
    private long settingsReadGeneration;
    private boolean settingsReadQueued;
    private boolean hasSuccessfulProviderSettings;
    private int providerSettingsState = Integer.MIN_VALUE;
    private boolean modeHooksInstalled;
    private int installedHookMode;
    private HyperOS4FocusController os4Controller;
    private volatile FocusBannerController bannerController;
    private NativeFocusTemplateRenderer nativeBannerRenderer;
    private final Set<View> os3PromptViews = Collections.newSetFromMap(new WeakHashMap<>());
    private Handler mainHandler;
    private TextView pendingMarqueeText;
    private Runnable pendingMarqueeRunnable;
    private View.OnAttachStateChangeListener pendingMarqueeAttachListener;
    private Runnable pendingMarqueeAttachTimeout;
    private TextView activeMarqueeText;
    private View.OnAttachStateChangeListener activeMarqueeDetachListener;
    private ValueAnimator fallbackMarqueeAnimator;
    private long marqueeGeneration;
    private final Map<TextView, OriginalWidthState> originalTextWidths =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, ParentWidthState> originalParentWidths =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Object> convertedBeans = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private final Map<Object, Boolean> preMarkedOriginalFocus = Collections.synchronizedMap(
            new WeakHashMap<>());
    private final Set<Object> preMarkedIslands = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private final Map<Object, OriginalBeanState> originalBeanStates =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, Integer> remoteViewsHiddenPrompts =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, Map<View, Integer>> remoteViewsHiddenContainers =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** Which notification the containers were hidden for, so a leaked hide can name itself. */
    private final Map<View, String> remoteViewsHiddenKeys =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final LinkedHashMap<String, Long> convertedNotificationKeys =
            new LinkedHashMap<>(16, 0.75f, true);
    /** Keys observed as media notifications; bean callbacks can temporarily lose the media token. */
    private final Set<String> mediaNotificationKeys = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> activeMediaNotificationKeys = Collections.synchronizedSet(new HashSet<>());
    /** Packages whose media notification the user dismissed from the shade (ROM lastDismissPkg). */
    private final Set<String> dismissedMediaPackages = Collections.synchronizedSet(new HashSet<>());
    /** Latest OS3 prompt controller, used to actively remove a dismissed media Focus bean. */
    private volatile WeakReference<Object> os3PromptControllerRef = new WeakReference<>(null);
    private volatile Class<?> os3PromptControllerClass;
    /** Latest OS3 media view controller, owner of the ROM's media output (cast) picker. */
    private volatile WeakReference<Object> mediaViewControllerRef = new WeakReference<>(null);
    /** Latest MiPlayPluginManager, owner of the MIUI 妙播 transfer panel plugin. */
    private volatile WeakReference<Object> miPlayPluginManagerRef = new WeakReference<>(null);
    private volatile Object miPlayPanel;
    private volatile View miPlayPanelHost;
    private final ThreadLocal<Boolean> replayingFocusEntry = new ThreadLocal<>();
    private final Map<String, WeakReference<Object>> notificationEntries =
            Collections.synchronizedMap(new LinkedHashMap<>());
    /** key -> {displayStartElapsedRealtime, notificationPostTime} for the display limit. */
    private final Map<String, long[]> focusDisplayWindows =
            Collections.synchronizedMap(new LinkedHashMap<>());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEM_UI.equals(lpparam.packageName)
                || !SYSTEM_UI.equals(lpparam.processName)) {
            return;
        }

        synchronized (INSTALL_LOCK) {
            if (!INSTALLED_CLASS_LOADERS.add(lpparam.classLoader)) {
                return;
            }
        }
        classLoader = lpparam.classLoader;
        log("entry loaded in " + lpparam.packageName + "/" + lpparam.processName
                + " version=" + BuildConfig.VERSION_NAME + " code=" + BuildConfig.VERSION_CODE
                + " debug=" + BuildConfig.DEBUG + " pid=" + android.os.Process.myPid()
                + " bannerEngine=native-template-v1");
        hookNativeBannerPlugin();
        hookApplicationAttach();
        hookDynamicIslandSystemProperty();
        disableDynamicIslandFeatureCache();
        if (!com.hyperos3.focusrestore.BuildConfig.DEBUG
                || currentSettings.disableIslandFeatureCache) {
            hideDisplayCutoutPainting();
            hideStrongToastMask();
            hookDeviceNotificationConversion();
            hookStatusBarTreeDump();
        }
        log("loading in " + lpparam.packageName + "/" + lpparam.processName
                + " awaiting persisted hook mode; default=OS"
                + FocusRestoreSettings.DEFAULT_HOOK_MODE);
    }

    private void hookNativeBannerPlugin() {
        try {
            nativeBannerRenderer = new NativeFocusTemplateRenderer(
                    new NativeFocusTemplateRenderer.Logger() {
                        @Override public void log(String message) {
                            HyperOS3FocusRestoreHook.this.log(message);
                        }
                        @Override public void error(String stage, Throwable throwable) {
                            HyperOS3FocusRestoreHook.this.error(stage, throwable);
                        }
                        @Override public void debug(String message) {
                            HyperOS3FocusRestoreHook.debug(message);
                        }
                    }, () -> {
                        FocusBannerController controller = bannerController;
                        if (controller != null) controller.onNativeSourceChanged();
                    }, key -> {
                        WeakReference<Object> reference = notificationEntries.get(key);
                        Object entry = reference == null ? null : reference.get();
                        Object row = getField(entry, "row");
                        return row instanceof View ? (View) row : null;
                    });
            new NativeFocusPluginDiscovery(new NativeFocusPluginDiscovery.Listener() {
                @Override public void onLoader(ClassLoader loader) {
                    nativeBannerRenderer.install(loader);
                    blockDynamicIslandViews(loader);
                }
                @Override public void onUnloaded(ClassLoader loader) {
                    FocusBannerController controller = bannerController;
                    if (controller != null) controller.dismiss("native-plugin-unloaded");
                    // 构造的焦点通知是真实通知，插件卸载后不该继续留在通知栏。
                    if (deviceNotificationFocusPoster != null) deviceNotificationFocusPoster.cancel();
                    nativeBannerRenderer.onPluginDisconnected(loader);
                    islandViewsBlocked = false;
                }
                @Override public void log(String message) {
                    HyperOS3FocusRestoreHook.this.log(message);
                }
                @Override public void error(String stage, Throwable throwable) {
                    HyperOS3FocusRestoreHook.this.error(stage, throwable);
                }
            }).install(classLoader);
        } catch (Throwable throwable) {
            error("native banner plugin discovery", throwable);
        }
    }

    private void logCapabilities(int hookMode) {
        Class<?> focusUtils = FocusReflection.findClass(classLoader,
                "com.android.systemui.statusbar.notification.utils.FocusUtils");
        if (hookMode == FocusRestoreSettings.HOOK_MODE_OS4) {
            Class<?> pipeline = FocusReflection.findClass(classLoader,
                    "com.android.systemui.statusbar.notification.collection.NotifPipeline");
            Class<?> statusBar = FocusReflection.findClass(classLoader,
                    "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView");
            log("capabilities configuredMode=OS4 installedMode=OS4 "
                    + FocusReflection.capability(focusUtils, "showOnStatusBar")
                    + " " + FocusReflection.capability(pipeline, "addCollectionListener")
                    + " " + FocusReflection.capability(statusBar, "onFinishInflate"));
            return;
        }
        Class<?> promptView = FocusReflection.findClass(classLoader,
                "com.android.systemui.statusbar.phone.FocusedNotifPromptView");
        Class<?> focusedText = FocusReflection.findClass(classLoader,
                "com.android.systemui.statusbar.widget.FocusedTextView");
        log("capabilities configuredMode=OS3 installedMode=OS3 "
                + FocusReflection.capability(focusUtils, "showOnStatusBar")
                + " " + FocusReflection.capability(promptView, "setData")
                + " " + FocusReflection.capability(promptView, "onFocusNotifPromptClicked")
                + " " + FocusReflection.capability(focusedText, "startMarqueeLocal"));
    }

    private synchronized void installConfiguredModeHooks() {
        if (modeHooksInstalled) {
            log("mode hooks already installed installedMode=OS" + installedHookMode
                    + " configuredMode=OS" + currentSettings.hookMode);
            return;
        }
        installedHookMode = currentSettings.hookMode;
        modeHooksInstalled = true;
        hookNotificationEntries();
        hookFocusCoordinatorMediaPromotion();
        log("installing configuredMode=OS" + installedHookMode
                + " settings=" + currentSettings.describe());
        try {
            logCapabilities(installedHookMode);
        } catch (Throwable throwable) {
            error("logCapabilities", throwable);
        }
        if (installedHookMode == FocusRestoreSettings.HOOK_MODE_OS4) {
            installOS4Hooks();
        } else {
            installOS3Hooks();
        }
        new FocusScrollToTopGuard(this::isVisibleFocusRegion, new FocusScrollToTopGuard.Logger() {
            @Override public void log(String message) { HyperOS3FocusRestoreHook.this.log(message); }
            @Override public void error(String stage, Throwable error) {
                HyperOS3FocusRestoreHook.this.error(stage, error);
            }
        }).install(classLoader, installedHookMode == FocusRestoreSettings.HOOK_MODE_OS4);
    }

    private boolean isVisibleFocusRegion(float x, float y, int displayId) {
        if (installedHookMode == FocusRestoreSettings.HOOK_MODE_OS4) {
            return os4Controller != null && os4Controller.isVisibleFocusRegion(x, y, displayId);
        }
        synchronized (os3PromptViews) {
            for (View prompt : os3PromptViews) {
                if (prompt == null || getField(prompt, "mData") == null) continue;
                Object content = getField(prompt, "mContent");
                Object icon = getField(prompt, "mIcon");
                if ((content instanceof View && FocusScrollToTopGuard.containsVisible((View) content, x, y, displayId))
                        || (icon instanceof View && FocusScrollToTopGuard.containsVisible((View) icon, x, y, displayId))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void installOS3Hooks() {
        hookFocusCoordinatorMediaPromotion();
        hookOS3NotificationRemoval();
        hookShowOnStatusBar();
        hookMediaFocusedBeanRemoval();
        hookMediaFocusYield();
        hookMediaNotificationLifecycle();
        hookMediaHeadsUpSuppression();
        hookMediaOutputEntry();
        hookMiPlayProbe();
        hookFocusedParentParams();
        hookFocusedTextMarquee();
        hookPromptViewSetData();
        hookPromptShouldShow();
        hookDisableConvertedFocusClick();
        hookRemoteViewsErrors();
        log("installedMode=OS3");
    }

    private void installOS4Hooks() {
        Context context = systemUiContext;
        if (context == null) {
            error("installOS4Hooks", new IllegalStateException("SystemUI context unavailable"));
            return;
        }
        synchronized (focusDisplayWindows) {
            focusDisplayWindows.clear();
        }
        os4Controller = new HyperOS4FocusController(classLoader, context,
                new HyperOS4FocusController.ItemFactory() {
                    @Override
                    public HyperOS4FocusController.DisplayItem create(Object notificationEntry) {
                        return createOS4DisplayItem(notificationEntry);
                    }

                    @Override
                    public boolean isExpired(Object notificationEntry) {
                        return remainingDisplayMillis(notificationEntry) == 0L;
                    }

                    @Override
                    public long expiryRemainingMillis(Object notificationEntry) {
                        return remainingDisplayMillis(notificationEntry);
                    }

                    @Override
                    public void onDisplayed(Object notificationEntry) {
                        markFocusDisplayed(inspectExpanded(notificationEntrySbn(notificationEntry)));
                    }

                    @Override
                    public HookSettings settings() {
                        return currentSettings;
                    }

                    @Override
                    public boolean clickNotificationRow(Object notificationEntry, String key) {
                        return performNotificationRowClick(notificationEntry, key, "OS4");
                    }

                    @Override
                    public boolean showFocusBanner(View anchor, Object notificationEntry, String key) {
                        return showIndependentFocusBanner(anchor, notificationEntry, key, "OS4");
                    }

                    @Override
                    public void onNotificationRemoved(Object notificationEntry, String key) {
                        forgetFocusDisplay(key);
                        WeakReference<Object> ref = notificationEntries.get(key);
                        if (ref == null || ref.get() == notificationEntry) removeBannerNotification(key);
                    }

                    @Override
                    public void dismissFocusBanner(String reason) {
                        FocusBannerController controller = bannerController;
                        if (controller != null) controller.dismiss(reason);
                    }
                }, new HyperOS4FocusController.Logger() {
                    @Override
                    public void log(String message) {
                        HyperOS3FocusRestoreHook.this.log(message);
                    }

                    @Override
                    public void error(String stage, Throwable throwable) {
                        HyperOS3FocusRestoreHook.this.error(stage, throwable);
                    }
                });
        os4Controller.install();
        hookMediaNotificationLifecycle();
        hookMediaOutputEntry();
        hookMiPlayProbe();
        log("installedMode=OS4");
    }

    private void hookApplicationAttach() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof Context) {
                                Context attachedContext = (Context) param.args[0];
                                Context applicationContext = attachedContext.getApplicationContext();
                                systemUiContext = applicationContext != null
                                        ? applicationContext : attachedContext;
                                mainHandler = new Handler(attachedContext.getMainLooper());
                                log("SystemUI attach context available class="
                                        + systemUiContext.getClass().getName()
                                        + " applicationContext=" + (applicationContext != null)
                                        + "; loading persisted hook mode");
                                if (reloadSettings(true)) {
                                    installConfiguredModeHooks();
                                } else {
                                    log("mode hooks not installed: persisted settings unavailable; "
                                            + "restart SystemUI or device after settings storage is available");
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            error("hookApplicationAttach", t);
        }
    }

    private void hookNotificationEntries() {
        try {
            Class<?> entryClass = FocusReflection.findClass(classLoader,
                    "com.android.systemui.statusbar.notification.collection.NotificationEntry");
            XposedBridge.hookAllConstructors(entryClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    rememberNotificationEntry(param.thisObject);
                }
            });
            XposedBridge.hookAllMethods(entryClass, "setSbn", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    rememberNotificationEntry(param.thisObject);
                }
            });
            log("notification entry row fallback hooks installed");
        } catch (Throwable throwable) {
            error("hookNotificationEntries", throwable);
        }
    }

    private String notificationEntryKey(Object entry) {
        if (entry == null) return null;
        Object key = getField(entry, "key");
        if (key == null) key = getField(entry, "mKey");
        if (key instanceof String) return (String) key;
        Object sbn = notificationEntrySbn(entry);
        return sbn instanceof android.service.notification.StatusBarNotification
                ? ((android.service.notification.StatusBarNotification) sbn).getKey() : null;
    }

    private Object notificationEntrySbn(Object entry) {
        if (entry == null) return null;
        Object sbn = getField(entry, "mSbn");
        return sbn != null ? sbn : getField(entry, "sbn");
    }

    private void rememberNotificationEntry(Object entry) {
        if (entry == null) return;
        try {
            // OS4 exposes the key as a field; getKey() is absent on the tested ROM.
            String key = notificationEntryKey(entry);
            if (TextUtils.isEmpty(key)) return;
            synchronized (notificationEntries) {
                notificationEntries.put(key, new WeakReference<>(entry));
                Iterator<Map.Entry<String, WeakReference<Object>>> it =
                        notificationEntries.entrySet().iterator();
                while (notificationEntries.size() > 256 && it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            Object sbn = notificationEntrySbn(entry);
            if (sbn instanceof android.service.notification.StatusBarNotification) {
                android.service.notification.StatusBarNotification notification =
                        (android.service.notification.StatusBarNotification) sbn;
                // 交付确认与横幅宿主无关：重启后还没画过横幅时也必须能确认构造的焦点通知已经生效。
                noteConstructedFocusNotification(notification);
                FocusBannerController controller = bannerController;
                if (controller != null) controller.onNotificationChanged(key, notification);
            }
        } catch (Throwable throwable) {
            error("remember notification entry", throwable);
        }
    }

    /**
     * Wires the media banner's seamless-transfer icon to the ROM's own media output picker.
     *
     * <p>Source: static analysis of HyperOS 3 SystemUI APK
     * {@code os3系统界面_16.03.251211.r.apk}. {@code MiuiMediaViewControllerImpl$setSeamless$1$1
     * .onClick} calls {@code MediaOutputDialogManager.createAndShow(packageName, true,
     * seamlessButton, UserHandle.getUserHandleForUid(appUid), MediaData.token)}; FocusRestore
     * independently resolves the same manager from the ROM's media view controller and calls the
     * same public method instead of copying the plugin/妙播 implementation.
     *
     * <p>OS4 接口事实来源：用户提供的 MT MCP APK，SystemUI 17.03.260226.r
     * （versionCode 202602260）；MediaOutputDialogManager.createAndShow 的六参数重载，
     * MediaControlPanel$$ExternalSyntheticLambda8 调用末尾布尔参数为 false。
     * 出处：http://192.168.31.239:8787/mcp；
     * 版权所有者及该 ROM 私有实现许可证未确认。本项目仅依据签名独立适配，未复制外部实现。
     */
    private void hookMediaOutputEntry() {
        try {
            Class<?> controller = FocusReflection.findClass(
                    "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl",
                    classLoader);
            XposedBridge.hookAllMethods(controller, "bindMediaData", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    mediaViewControllerRef = new WeakReference<>(param.thisObject);
                }
            });
            MediaNotificationBannerSource.setSeamlessOpener(this::openMediaPicker);
            log("media output picker hook installed");
        } catch (Throwable throwable) {
            error("hookMediaOutputEntry", throwable);
        }
    }

    private boolean openMediaOutputPicker(String key, Object mediaData, View anchor) {
        if (mediaData == null) return false;
        try {
            Object controller = mediaViewControllerRef.get();
            if (controller == null) {
                log("media output picker unavailable key=" + key + " reason=controller");
                return false;
            }
            Object lazy = getField(controller, "mediaOutputDialogManager");
            if (lazy == null) {
                log("media output picker unavailable key=" + key + " reason=manager-field");
                return false;
            }
            Object manager = XposedHelpers.callMethod(lazy, "get");
            if (manager == null) return false;
            String packageName = stringValue(getField(mediaData, "packageName"));
            Object token = getField(mediaData, "token");
            Object uidValue = getField(mediaData, "appUid");
            int uid = uidValue instanceof Integer ? (Integer) uidValue : 0;
            Object userHandle = android.os.UserHandle.getUserHandleForUid(uid);
            // The ROM's createAndShow(View...) builds a DialogTransitionAnimator.Controller from the
            // anchor, which throws unless that View implements SystemUI's LaunchableView. Passing a
            // null anchor makes the ROM skip the transition controller entirely, so the system's own
            // media output picker still opens (without the unfold animation). The anchor is only
            // forwarded when it already satisfies the ROM's contract.
            View anchorView = null;
            if (anchor != null) {
                Class<?> launchableView = FocusReflection.findClass(
                        "com.android.systemui.animation.LaunchableView", classLoader);
                if (launchableView != null && launchableView.isInstance(anchor)) anchorView = anchor;
            }
            // OS4 接口多一个布尔参数，ROM 自身传 false；按已安装模式选择，保留 OS3 的旧接口。
            Method createAndShow;
            if (installedHookMode == FocusRestoreSettings.HOOK_MODE_OS4) {
                createAndShow = XposedHelpers.findMethodExact(manager.getClass(),
                        "createAndShow", String.class, boolean.class, View.class,
                        android.os.UserHandle.class, android.media.session.MediaSession.Token.class,
                        boolean.class);
                createAndShow.invoke(manager, packageName, true, anchorView, userHandle, token, false);
            } else {
                createAndShow = XposedHelpers.findMethodExact(manager.getClass(),
                        "createAndShow", String.class, boolean.class, View.class,
                        android.os.UserHandle.class, android.media.session.MediaSession.Token.class);
                createAndShow.invoke(manager, packageName, true, anchorView, userHandle, token);
            }
            log("media output picker opened key=" + key + " package=" + packageName
                    + " animated=" + (anchorView != null));
            return true;
        } catch (Throwable throwable) {
            error("openMediaOutputPicker key=" + key, throwable);
            return false;
        }
    }

    /**
     * Diagnostic probe for the MIUI 妙播 (MiPlay) audio-transfer panel. It only records whether the
     * plugin and its own panel view can be obtained; nothing is attached, shown or modified.
     *
     * <p>Source: static analysis of HyperOS 3 SystemUI APK
     * {@code os3系统界面_16.03.251211.r.apk} and its plugin {@code miui.systemui.plugin}.
     * {@code MiPlayPluginManager.onPluginConnected} stores the connected plugin into
     * {@code mMiPlayPlugin}, and {@code MiPlayPluginImpl.showMiPlayDetailView} only calls
     * {@code QSControlMiPlayDetailContent.setDetailShowing} when the passed view is the plugin's own
     * panel from {@code createMiPlayDetailView()}; the ROM hosts that panel in
     * {@code ModalQSControlDetail}. No external dialog entry exists, so FocusRestore probes the
     * plugin before deciding whether a 妙播 entry can be offered alongside the native picker.
     */
    private void hookMiPlayProbe() {
        try {
            Class<?> manager = FocusReflection.findClass(
                    "com.android.systemui.controlcenter.phone.controls.MiPlayPluginManager",
                    classLoader);
            XposedBridge.hookAllMethods(manager, "onPluginConnected", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    miPlayPluginManagerRef = new WeakReference<>(param.thisObject);
                    log("MiPlay probe plugin connected " + describeMiPlayPlugin(param.thisObject));
                }
            });
            Class<?> modal = FocusReflection.findClass(
                    "com.android.systemui.statusbar.notification.modal.ModalQSControlDetail",
                    classLoader);
            XposedHelpers.findAndHookMethod(modal, "setMiPlayPluginManager", manager,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object candidate = param.args == null || param.args.length == 0
                                    ? null : param.args[0];
                            if (candidate != null) {
                                miPlayPluginManagerRef = new WeakReference<>(candidate);
                            }
                        }
                    });
            log("MiPlay probe hooks installed");
        } catch (Throwable throwable) {
            error("hookMiPlayProbe", throwable);
        }
    }

    private String describeMiPlayPlugin(Object manager) {
        Object plugin = getField(manager, "mMiPlayPlugin");
        if (plugin == null) return "plugin=absent";
        String cta = systemUiContext == null
                ? "unknown" : String.valueOf(callQuietly(plugin, "isInterconnectionCTAAgree",
                        systemUiContext));
        return "plugin=" + plugin.getClass().getName()
                + " supportAudio=" + callQuietly(plugin, "supportMiPlayAudio")
                + " casting=" + callQuietly(plugin, "isAudioCasting")
                + " cta=" + cta;
    }

    /** True when the focus carries media content that is currently an active media notification. */
    private boolean isMediaFocus(FocusData data) {
        return data != null && data.mediaContent != null
                && activeMediaNotificationKeys.contains(data.key);
    }

    /** Opens the configured cast UI for a media notification key without showing the banner. */
    private boolean openMediaCast(String key) {
        Object mediaData = MediaNotificationBannerSource.mediaDataFor(key);
        if (mediaData == null) {
            log("media cast direct rejected key=" + key + " reason=no-media-data");
            return false;
        }
        return openMediaPicker(key, mediaData, null);
    }

    /** Routes the seamless-transfer tap to the picker the user selected in settings. */
    private boolean openMediaPicker(String key, Object mediaData, View anchor) {
        if (currentSettings != null
                && currentSettings.mediaFocusCastPicker == FocusRestoreSettings.CAST_PICKER_MIPLAY
                && showMiPlayPanel(key)) {
            return true;
        }
        if (currentSettings != null
                && currentSettings.mediaFocusCastPicker == FocusRestoreSettings.CAST_PICKER_MIPLAY) {
            log("MiPlay panel unavailable key=" + key + " fallback=native");
        }
        return openMediaOutputPicker(key, mediaData, anchor);
    }

    /**
     * Shows the MIUI 妙播 transfer panel in a FocusRestore-owned window.
     *
     * <p>The panel is created by the ROM's own plugin ({@code MiPlayPlugin
     * .createMiPlayDetailView()}); FocusRestore only supplies the host window, scrim and dismissal,
     * because the ROM's usual host ({@code ModalQSControlDetail}) is bound to the notification
     * shade. Any failure hides the window again and the caller falls back to the Android picker.
     */
    private boolean showMiPlayPanel(String key) {
        Object plugin = miPlayPlugin();
        if (plugin == null || systemUiContext == null) return false;
        hideMiPlayPanel();
        try {
            Object panelValue = XposedHelpers.callMethod(plugin, "createMiPlayDetailView");
            if (!(panelValue instanceof View)) return false;
            View panel = (View) panelValue;
            applyMiPlayPanelBackground(panel);
            FrameLayout scrim = new FrameLayout(systemUiContext);
            scrim.setBackgroundColor(0x99000000);
            scrim.setOnClickListener(view -> hideMiPlayPanel());
            scrim.setFocusableInTouchMode(true);
            scrim.setOnKeyListener((view, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_BACK || event.getAction() != KeyEvent.ACTION_UP) {
                    return false;
                }
                hideMiPlayPanel();
                return true;
            });
            FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                    miPlayPanelWidth(panel), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            scrim.addView(panel, panelParams);
            WindowManager windowManager =
                    (WindowManager) systemUiContext.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) return false;
            WindowManager.LayoutParams windowParams = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
            windowManager.addView(scrim, windowParams);
            XposedHelpers.callMethod(plugin, "showMiPlayDetailView", panel, "notification");
            miPlayPanel = panel;
            miPlayPanelHost = scrim;
            animateMiPlayPanelIn(scrim, panel);
            log("MiPlay panel shown key=" + key + " panel=" + panel.getClass().getName()
                    + " width=" + panelParams.width);
            return true;
        } catch (Throwable throwable) {
            error("showMiPlayPanel key=" + key, throwable);
            hideMiPlayPanel();
            return false;
        }
    }

    /**
     * Gives the panel the same background layer the ROM gives its 324dp column.
     *
     * <p>{@code ModalQSControlDetail.onFinishInflate} either enables blur with the
     * {@code modal_miplay_container_blend_colors} blend array or paints
     * {@code qs_control_detail_bg_color} ({@code #b21a1a1a}) when background blur is off. The panel
     * clips itself to a rounded outline, so applying the layer to the panel keeps the native shape.
     */
    private void applyMiPlayPanelBackground(View panel) {
        android.content.res.Resources resources = systemUiContext.getResources();
        try {
            Class<?> blur = FocusReflection.findClass("com.miui.systemui.util.MiBlurCompat",
                    classLoader);
            boolean blurEnabled = blur != null && Boolean.TRUE.equals(
                    XposedHelpers.callStaticMethod(blur, "getBackgroundBlurOpened",
                            resources.getConfiguration()));
            if (blurEnabled) {
                XposedHelpers.callStaticMethod(blur, "setMiViewBlurModeCompat", 1, panel);
                int arrayId = resources.getIdentifier("modal_miplay_container_blend_colors",
                        "array", "com.android.systemui");
                if (arrayId != 0) {
                    applyBlendColors(blur, panel, resources.getIntArray(arrayId));
                }
                return;
            }
            if (blur != null) {
                XposedHelpers.callStaticMethod(blur, "setMiViewBlurModeCompat", 0, panel);
            }
        } catch (Throwable throwable) {
            error("applyMiPlayPanelBackground blur", throwable);
        }
        int colorId = resources.getIdentifier("qs_control_detail_bg_color", "color",
                "com.android.systemui");
        panel.setBackgroundColor(colorId != 0
                ? systemUiContext.getColor(colorId) : 0xB21A1A1A);
    }

    private void applyBlendColors(Class<?> blur, View panel, int[] colors) {
        try {
            XposedHelpers.callStaticMethod(blur, "setMiBackgroundBlendColorsNew", panel, colors);
        } catch (Throwable named) {
            XposedHelpers.callStaticMethod(blur, "setMiBackgroundBlendColorsNew$default", panel,
                    colors);
        }
    }

    /** The ROM hosts the 妙播 panel in a centred {@code qs_control_mi_play_detail_width} column. */
    private int miPlayPanelWidth(View panel) {
        try {
            android.content.res.Resources resources = panel.getContext().getResources();
            int id = resources.getIdentifier("qs_control_mi_play_detail_width", "dimen",
                    "com.android.systemui");
            if (id != 0) return resources.getDimensionPixelSize(id);
        } catch (Throwable throwable) {
            error("miPlayPanelWidth", throwable);
        }
        return dp(324);
    }

    /**
     * Reveals the panel after a short delay.
     *
     * <p>The ROM panel writes its own height into its inner list ({@code updateHeight} assigns the
     * RecyclerView height and calls {@code requestLayout}), so it is hosted with wrap_content and
     * must never be given a fixed height. Before the device list arrives its list is match_parent,
     * which would briefly make it very tall, so the reveal waits for that first sizing pass.
     */
    private void animateMiPlayPanelIn(View scrim, View panel) {
        scrim.setAlpha(0f);
        panel.setAlpha(0f);
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        scrim.animate().alpha(1f).setDuration(180L).start();
        panel.postDelayed(() -> revealMiPlayPanel(panel), 300L);
    }

    private void revealMiPlayPanel(View panel) {
        if (panel != miPlayPanel) return;
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        log("MiPlay panel revealed width=" + panel.getWidth() + " height=" + panel.getHeight());
    }

    /** Hides the 妙播 panel window with the reverse transition; safe to call when absent. */
    private void hideMiPlayPanel() {
        View host = miPlayPanelHost;
        Object panel = miPlayPanel;
        miPlayPanelHost = null;
        miPlayPanel = null;
        if (host == null) return;
        Object plugin = miPlayPlugin();
        if (plugin != null && panel != null) {
            try {
                XposedHelpers.callMethod(plugin, "hideMiPlayDetailView", panel);
            } catch (Throwable throwable) {
                error("hideMiPlayDetailView", throwable);
            }
        }
        View panelView = panel instanceof View ? (View) panel : null;
        if (panelView != null && host.isAttachedToWindow()) {
            panelView.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(180L).start();
            host.animate().alpha(0f).setDuration(200L).withEndAction(
                    () -> removeMiPlayPanelHost(host)).start();
            return;
        }
        removeMiPlayPanelHost(host);
    }

    private void removeMiPlayPanelHost(View host) {
        try {
            WindowManager windowManager = systemUiContext == null ? null
                    : (WindowManager) systemUiContext.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) windowManager.removeViewImmediate(host);
        } catch (Throwable throwable) {
            error("hideMiPlayPanel removeView", throwable);
        }
    }

    private int dp(int value) {
        return Math.round(value * systemUiContext.getResources().getDisplayMetrics().density);
    }

    private Object miPlayPlugin() {
        Object manager = miPlayPluginManagerRef.get();
        return getField(manager, "mMiPlayPlugin");
    }

    private static Object callQuietly(Object target, String method, Object... args) {
        try {
            return XposedHelpers.callMethod(target, method, args);
        } catch (Throwable throwable) {
            return "error:" + throwable.getClass().getSimpleName();
        }
    }

    private void hookMediaNotificationLifecycle() {
        try {            Class<?> listener = FocusReflection.findClass(
                    "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaNotificationControllerImpl$mediaDataListener$1",
                    classLoader);
            Class<?> mediaData = FocusReflection.findClass(
                    "com.android.systemui.media.controls.shared.model.MediaData", classLoader);
            XC_MethodHook mediaDataLoadedHook = new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String key = param.args == null || param.args.length == 0
                                    ? null : stringValue(param.args[0]);
                            Object loadedData = param.args == null || param.args.length < 3
                                    ? null : param.args[2];
                            if (TextUtils.isEmpty(key) || loadedData == null) return;
                            // 仅捕获 ROM 的 MediaData 供媒体横幅渲染；显示触发仍完全由
                            // 通知栏媒体 NotificationEntry 的添加/更新/移除生命周期决定。
                            MediaNotificationBannerSource.attachMediaData(key, loadedData);
                            FocusBannerController bannerControllerRef = bannerController;
                            if (bannerControllerRef != null) {
                                bannerControllerRef.refreshMediaContent(key);
                            }
                            // 用户划掉媒体通知时 ROM 只把该包记入 lastDismissPkg，媒体 Entry
                            // 仍在集合中，所以焦点必须跟随这一状态主动隐藏。
                            String lastDismissPkg = null;
                            try {
                                Object controller = getField(param.thisObject, "this$0");
                                lastDismissPkg = stringValue(getField(controller, "lastDismissPkg"));
                            } catch (Throwable ignored) {
                                lastDismissPkg = null;
                            }
                            String packageName = stringValue(getField(loadedData, "packageName"));
                            if (TextUtils.isEmpty(packageName)) {
                                debug("media data captured key=" + key);
                                return;
                            }
                            if (!TextUtils.isEmpty(lastDismissPkg)
                                    && lastDismissPkg.equals(packageName)) {
                                if (dismissedMediaPackages.add(packageName)) {
                                    log("media notification dismissed pkg=" + packageName);
                                    hideDismissedMediaFocus(packageName);
                                }
                            } else if (dismissedMediaPackages.remove(packageName)) {
                                log("media notification restored pkg=" + packageName);
                            }
                            debug("media data captured key=" + key);
                        }
                    };
            try {
                // OS3 exposes the MediaDataManager.Listener callback with an extra
                // immediately flag; OS4's listener uses the three-argument contract.
                XposedHelpers.findAndHookMethod(listener, "onMediaDataLoaded", String.class,
                        String.class, mediaData, boolean.class, mediaDataLoadedHook);
                log("media data loaded hook signature=OS3");
            } catch (Throwable os3SignatureMissing) {
                XposedHelpers.findAndHookMethod(listener, "onMediaDataLoaded", String.class,
                        String.class, mediaData, mediaDataLoadedHook);
                log("media data loaded hook signature=OS4");
            }
            XposedHelpers.findAndHookMethod(listener, "onMediaDataRemoved", String.class,
                    boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String key = param.args == null || param.args.length == 0
                                    ? null : stringValue(param.args[0]);
                            if (TextUtils.isEmpty(key)) return;
                            MediaNotificationBannerSource.detachMediaData(key);
                            String packageName = packageNameOfKey(key);
                            if (!TextUtils.isEmpty(packageName)) {
                                dismissedMediaPackages.remove(packageName);
                            }
                            debug("media data dropped key=" + key);
                        }
                    });
            log("media notification lifecycle hooks installed");
        } catch (Throwable throwable) {
            error("hookMediaNotificationLifecycle", throwable);
        }
    }

    private static String packageNameOfKey(String key) {
        if (TextUtils.isEmpty(key)) return null;
        int first = key.indexOf('|');
        if (first < 0) return null;
        int second = key.indexOf('|', first + 1);
        return second > first + 1 ? key.substring(first + 1, second) : null;
    }

    /**
     * Hides the media Focus for every tracked notification of a package the user dismissed from
     * the shade. The ROM keeps the media entry in the collection after the dismiss (it only
     * records {@code lastDismissPkg}), so the removal must be driven explicitly here.
     */
    private void hideDismissedMediaFocus(final String packageName) {
        if (TextUtils.isEmpty(packageName) || mainHandler == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    List<String> dismissedKeys = new ArrayList<>();
                    synchronized (mediaNotificationKeys) {
                        for (String key : mediaNotificationKeys) {
                            if (packageName.equals(packageNameOfKey(key))) dismissedKeys.add(key);
                        }
                        for (String key : dismissedKeys) {
                            mediaNotificationKeys.remove(key);
                            activeMediaNotificationKeys.remove(key);
                            MediaNotificationBannerSource.detachMediaData(key);
                            WeakReference<Object> reference = notificationEntries.get(key);
                            Object entry = reference == null ? null : reference.get();
                            if (entry != null) clearPreMark(notificationEntrySbn(entry), true);
                        }
                    }
                    Object controller = os3PromptControllerRef.get();
                    Class<?> controllerClass = os3PromptControllerClass;
                    for (String key : dismissedKeys) {
                        WeakReference<Object> reference = notificationEntries.get(key);
                        Object entry = reference == null ? null : reference.get();
                        if (controller == null || controllerClass == null || entry == null) continue;
                        try {
                            XposedHelpers.callStaticMethod(controllerClass,
                                    "-$$Nest$mremoveFocusedNotifBean", controller, entry);
                        } catch (Throwable throwable) {
                            error("hideDismissedMediaFocus removeFocusedNotifBean key=" + key,
                                    throwable);
                        }
                    }
                    if (!dismissedKeys.isEmpty()) {
                        log("media Focus hidden for dismissed pkg=" + packageName
                                + " keys=" + dismissedKeys.size());
                    }
                } catch (Throwable throwable) {
                    error("hideDismissedMediaFocus pkg=" + packageName, throwable);
                }
            }
        });
    }

    private void hookMediaFocusedBeanRemoval() {
        try {
            Class<?> controller = FocusReflection.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptController",
                    classLoader);
            os3PromptControllerClass = controller;
            XposedBridge.hookAllMethods(controller,
                    "-$$Nest$mremoveFocusedNotifBean", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args != null && param.args.length >= 2) {
                                os3PromptControllerRef = new WeakReference<>(param.args[0]);
                            }
                            if (!currentSettings.mediaFocusEnabled || param.args == null
                                    || param.args.length < 2) return;
                            Object entry = param.args[1];
                            String key = notificationEntryKey(entry);
                            if (!TextUtils.isEmpty(key) && mediaNotificationKeys.contains(key)) {
                                param.setResult(null);
                                log("blocked media removeFocusedNotifBean key=" + key);
                            }
                        }
                    });
            log("OS3 media FocusedNotifBean removal hook installed");
        } catch (Throwable throwable) {
            error("hookMediaFocusedBeanRemoval", throwable);
        }
    }

    private static final String MEDIA_YIELD_EXTRA = "focusRestore:yieldedMediaBeans";

    /**
     * Keeps media Focus below every ordinary Focus: when the prompt bean map holds both, the ROM
     * selects the current bean by map iteration order, so a media bean can block an ordinary one.
     * While an ordinary bean exists this hook temporarily removes media beans for the ROM's own
     * selection pass and restores them afterwards, so the ordinary Focus is shown and the media
     * Focus reappears once the ordinary Focus is gone.
     */
    private void hookMediaFocusYield() {
        try {
            Class<?> controller = FocusReflection.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptController",
                    classLoader);
            XposedBridge.hookAllMethods(controller, "update", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!currentSettings.mediaFocusEnabled) return;
                    Object map = currentPromptBeanMap(param.thisObject);
                    if (!(map instanceof Map)) return;
                    boolean hasOrdinary = false;
                    for (Object value : ((Map<?, ?>) map).values()) {
                        FocusData candidate = inspectBean(value);
                        if (candidate != null && candidate.mediaContent == null) {
                            hasOrdinary = true;
                            break;
                        }
                    }
                    if (!hasOrdinary) return;
                    List<Object> mediaBeans = new ArrayList<>();
                    Iterator<?> iterator = ((Map<?, ?>) map).entrySet().iterator();
                    while (iterator.hasNext()) {
                        Object bean = ((Map.Entry<?, ?>) iterator.next()).getValue();
                        FocusData candidate = inspectBean(bean);
                        if (candidate != null && candidate.mediaContent != null) {
                            mediaBeans.add(bean);
                            iterator.remove();
                        }
                    }
                    if (!mediaBeans.isEmpty()) {
                        param.setObjectExtra(MEDIA_YIELD_EXTRA, mediaBeans);
                        log("media Focus yielded during update beans=" + mediaBeans.size());
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object extra = param.getObjectExtra(MEDIA_YIELD_EXTRA);
                    if (!(extra instanceof List)) return;
                    Object map = currentPromptBeanMap(param.thisObject);
                    if (!(map instanceof Map)) return;
                    for (Object bean : (List<?>) extra) {
                        FocusData data = inspectBean(bean);
                        if (data == null || TextUtils.isEmpty(data.key)) continue;
                        ((Map<Object, Object>) map).put(data.key, bean);
                    }
                }
            });
            log("OS3 media Focus yield hook installed");
        } catch (Throwable throwable) {
            error("hookMediaFocusYield", throwable);
        }
    }

    private Object currentPromptBeanMap(Object promptController) {
        if (promptController == null) return null;
        try {
            Object userIdValue = getField(promptController, "mCurrentUserId");
            int userId = userIdValue instanceof Integer ? (Integer) userIdValue : 0;
            return XposedHelpers.callMethod(promptController, "getBeanMap", userId);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * Media beans carry {@code headsUp=true} because the media notification itself is heads-up.
     * The ROM then skips {@code notifyNotifBeanChanged} in {@code update()} while any heads-up
     * bean exists, so the media Focus would only render after the media banner expired. Treating
     * media entries as non-heads-up makes the media Focus appear together with the media banner.
     */
    private void hookMediaHeadsUpSuppression() {
        try {
            Class<?> controller = FocusReflection.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptController",
                    classLoader);
            Class<?> entryClass = FocusReflection.findClass(
                    "com.android.systemui.statusbar.notification.collection.NotificationEntry",
                    classLoader);
            XposedHelpers.findAndHookMethod(controller, "onNotifHeadsUpResult",
                    entryClass, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!currentSettings.mediaFocusEnabled || param.args == null
                                    || param.args.length < 2) return;
                            Object entry = param.args[0];
                            String key = notificationEntryKey(entry);
                            if (!TextUtils.isEmpty(key) && mediaNotificationKeys.contains(key)
                                    && Boolean.TRUE.equals(param.args[1])) {
                                param.args[1] = Boolean.FALSE;
                                log("media heads-up suppressed for focus key=" + key);
                            }
                        }
                    });
            log("OS3 media heads-up suppression hook installed");
        } catch (Throwable throwable) {
            error("hookMediaHeadsUpSuppression", throwable);
        }
    }

    private void hookFocusCoordinatorMediaPromotion() {
        try {
            Class<?> listener = FocusReflection.findClass(
                    "com.android.systemui.statusbar.notification.focus.FocusCoordinator$3",
                    classLoader);
            XposedBridge.hookAllMethods(listener, "onEntryAdded", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(replayingFocusEntry.get())) return;
                    Object entry = param.args == null || param.args.length == 0
                            ? null : param.args[0];
                    promoteMediaEntry(param.thisObject, entry, "onEntryAdded");
                }
            });
            XposedBridge.hookAllMethods(listener, "onEntryUpdated", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(replayingFocusEntry.get())) return;
                    Object entry = param.args == null || param.args.length == 0
                            ? null : param.args[0];
                    promoteMediaEntry(param.thisObject, entry, "onEntryUpdated");
                }
            });
            XposedBridge.hookAllMethods(listener, "onEntryRemoved", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object entry = param.args == null || param.args.length == 0
                            ? null : param.args[0];
                    String key = notificationEntryKey(entry);
                    if (!TextUtils.isEmpty(key)) {
                        activeMediaNotificationKeys.remove(key);
                        mediaNotificationKeys.remove(key);
                        MediaNotificationBannerSource.detachMediaData(key);
                        String packageName = packageNameOfKey(key);
                        if (!TextUtils.isEmpty(packageName)) {
                            dismissedMediaPackages.remove(packageName);
                        }
                    }
                }
            });
            log("OS3 media FocusCoordinator promotion hooks installed");
        } catch (Throwable throwable) {
            error("hookFocusCoordinatorMediaPromotion", throwable);
        }
    }

    private void replayFocusEntryAdded(Object listener, Object entry, String key) {
        try {
            Method onEntryAdded = listener.getClass().getDeclaredMethod(
                    "onEntryAdded", entry.getClass());
            onEntryAdded.setAccessible(true);
            replayingFocusEntry.set(Boolean.TRUE);
            XposedBridge.invokeOriginalMethod(onEntryAdded, listener, new Object[]{entry});
            log("replayed FocusCoordinator.onEntryAdded for media key=" + key);
        } catch (Throwable throwable) {
            error("replay FocusCoordinator.onEntryAdded key=" + key, throwable);
        } finally {
            replayingFocusEntry.remove();
        }
    }

    private void promoteMediaEntry(Object listener, Object entry, String stage) {
        if (!currentSettings.mediaFocusEnabled || entry == null) return;
        try {
            Object expanded = notificationEntrySbn(entry);
            FocusData data = inspectExpanded(expanded);
            if (data == null || TextUtils.isEmpty(data.key)) return;

            if (data.mediaContent == null) {
                activeMediaNotificationKeys.remove(data.key);
                mediaNotificationKeys.remove(data.key);
                clearPreMark(expanded, true);
                return;
            }
            if (dismissedMediaPackages.contains(data.packageName)) {
                activeMediaNotificationKeys.remove(data.key);
                mediaNotificationKeys.remove(data.key);
                clearPreMark(expanded, true);
                return;
            }
            activeMediaNotificationKeys.add(data.key);
            mediaNotificationKeys.add(data.key);
            boolean originalFocus = getBooleanField(expanded, "mIsFocusNotification", false);
            if (originalFocus) return;
            preMarkedOriginalFocus.put(expanded, false);
            preMarkedIslands.add(expanded);
            XposedHelpers.setBooleanField(expanded, "mIsFocusNotification", true);
            if ("onEntryUpdated".equals(stage) && listener != null) {
                replayFocusEntryAdded(listener, entry, data.key);
            }
            log(stage + " promoted media to Focus key=" + data.key
                    + " package=" + data.packageName);
        } catch (Throwable throwable) {
            error(stage + " promoteMediaEntry", throwable);
        }
    }

    private void hookOS3NotificationRemoval() {
        try {
            Class<?> eventClass = FocusReflection.findClass(classLoader,
                    "com.android.systemui.statusbar.notification.collection.notifcollection.EntryRemovedEvent");
            Set<?> hooks = XposedBridge.hookAllMethods(eventClass, "dispatchToListener",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object entry = getField(param.thisObject, "entry");
                            String key = notificationEntryKey(entry);
                            if (TextUtils.isEmpty(key)) return;
                            WeakReference<Object> ref = notificationEntries.get(key);
                            // A delayed removal must not close a newer same-key entry.
                            if (ref == null || ref.get() == entry) removeBannerNotification(key);
                        }
                    });
            log("OS3 banner removal hooks=" + hooks.size());
        } catch (Throwable throwable) {
            error("OS3 banner notification removal", throwable);
        }
    }

    private void removeBannerNotification(String key) {
        if (TextUtils.isEmpty(key)) return;
        notificationEntries.remove(key);
        mediaNotificationKeys.remove(key);
        activeMediaNotificationKeys.remove(key);
        FocusBannerController controller = bannerController;
        if (controller != null) controller.onNotificationRemoved(key);
        if (nativeBannerRenderer != null) nativeBannerRenderer.onNotificationRemoved(key);
    }

    private boolean showIndependentFocusBanner(View anchor, Object directEntry, String key,
                                               String mode) {
        if (TextUtils.isEmpty(key)) {
            log(mode + " independent banner rejected reason=missing-key");
            return false;
        }
        Object entry = directEntry;
        if (entry == null) {
            WeakReference<Object> ref = notificationEntries.get(key);
            entry = ref == null ? null : ref.get();
        }
        FocusData data = inspectExpanded(notificationEntrySbn(entry));
        return showIndependentFocusBanner(anchor, data, key, mode);
    }

    private boolean showIndependentFocusBanner(View anchor, FocusData data, String key,
                                               String mode) {
        // 媒体焦点仅在“启用媒体焦点通知”开启时才算数：关闭开关后
        // activeMediaNotificationKeys 里可能仍留有旧 key，不应再走媒体横幅或直接流转。
        boolean mediaFocus = isMediaFocus(data) && currentSettings.mediaFocusEnabled;
        if (currentSettings.mediaFocusCastDirect && mediaFocus) {
            // 点击媒体焦点直接展开流转界面，替代媒体横幅。
            return openMediaCast(data.key);
        }
        // 媒体焦点始终可点击：未选“直接展开流转界面”时点击即展开媒体横幅，不再回退到
        // 普通通知的原生模板（媒体通知没有原生 V3 焦点参数，回退必然失败、表现为无反应）。
        boolean mediaBanner = mediaFocus;
        if (!mediaBanner && !currentSettings.independentFocusBanner) return false;
        if (mainHandler == null || Looper.myLooper() != mainHandler.getLooper()) {
            log(mode + " independent banner rejected key=" + key + " reason=thread");
            return false;
        }
        if (data == null || data.notification == null || TextUtils.isEmpty(key)) {
            log(mode + " independent banner rejected key=" + key + " reason=notification");
            return false;
        }
        try {
            StatusBarNotification sbn = data.sbn;
            if (sbn == null) {
                WeakReference<Object> ref = notificationEntries.get(key);
                Object candidate = notificationEntrySbn(ref == null ? null : ref.get());
                if (candidate instanceof StatusBarNotification) sbn = (StatusBarNotification) candidate;
            }
            if (sbn == null || !key.equals(sbn.getKey())) {
                log(mode + " native banner rejected key=" + key + " reason=real-sbn-missing-or-stale");
                return false;
            }
            if (mediaBanner) {
                if (!MediaNotificationBannerSource.isAvailable(sbn)) {
                    log(mode + " media banner rejected key=" + key + " reason=no-remote-views");
                    return false;
                }
                log(mode + " media banner click key=" + key + " source=notification-remote-views");
                return sharedBannerController().show(anchor, key, sbn, true);
            }
            if (nativeBannerRenderer == null) {
                log(mode + " native banner rejected key=" + key + " reason=real-sbn-or-renderer-missing");
                return false;
            }
            // 设置项表达“统一纯色背景”；关闭时才退回旧版电话横幅使用的普通通知卡片。
            RemoteViewsFocusBannerSource.setForceNormalBackground(
                    !currentSettings.specialBannerNormalBackground);
            Notification bannerNotification = sbn.getNotification();
            Bundle bannerExtras = bannerNotification == null ? null : bannerNotification.extras;
            FocusParamKeys.ValueSource bannerValues =
                    bannerExtras == null ? null : bannerExtras::get;
            debug(mode + " native banner preflight key=" + key + " postTime=" + sbn.getPostTime()
                    + " " + FocusParamKeys.describe(bannerValues)
                    + " " + focusExtrasInventory(bannerExtras));
            log(mode + " native banner click key=" + key + " version="
                    + BuildConfig.VERSION_NAME);
            return sharedBannerController().show(anchor, key, sbn);
        } catch (Throwable throwable) {
            error(mode + " independent banner key=" + key, throwable);
            return false;
        }
    }

    /**
     * 焦点横幅与设备通知横幅共用的窗口宿主，首次使用时创建。
     *
     * <p>设备通知走 {@code showSource} 通路，只有内容源不同；共用同一个宿主才能复用几何、测量、
     * 装窗、收起、锁屏/旋转/外侧点击处理。渲染器可能为空（插件未就绪），此时焦点横幅各自的分支
     * 会在调用前退出，而内容源通路不使用渲染器。调用方都在主线程。
     */
    private FocusBannerController sharedBannerController() {
        if (bannerController == null) {
            bannerController = new FocusBannerController(new FocusBannerController.Logger() {
                @Override public void log(String message) {
                    HyperOS3FocusRestoreHook.this.log(message);
                }
                @Override public void error(String stage, Throwable throwable) {
                    HyperOS3FocusRestoreHook.this.error(stage, throwable);
                }
            }, nativeBannerRenderer, this::openBannerNotification);
        }
        return bannerController;
    }

    private boolean openBannerNotification(String key, StatusBarNotification expected) {
        if (mainHandler == null || Looper.myLooper() != mainHandler.getLooper()
                || TextUtils.isEmpty(key)) return false;
        WeakReference<Object> reference = notificationEntries.get(key);
        Object entry = reference == null ? null : reference.get();
        Object value = notificationEntrySbn(entry);
        if (!(value instanceof StatusBarNotification)
                || !key.equals(((StatusBarNotification) value).getKey())
                || !NativeFocusTemplateRenderer.sameNotification(expected, (StatusBarNotification) value)) {
            log("banner body click rejected key=" + key + " reason=stale-or-removed-notification");
            // Media notifications render as a media header node; the entry may be absent from the
            // cached row map or its SBN keeps changing. Open the app via the media session intent.
            return openMediaClickIntent(key);
        }
        Object row = getField(entry, "row");
        if (!(row instanceof View)) {
            return openMediaClickIntent(key);
        }
        try {
            // OS4 getEntry() delegates to the row injector; OS3 may still expose mEntry.
            Object boundEntry;
            try { boundEntry = XposedHelpers.callMethod(row, "getEntry"); }
            catch (NoSuchMethodError missing) { boundEntry = getField(row, "mEntry"); }
            if (boundEntry != entry) {
                log("banner body click rejected key=" + key + " reason=row-rebound");
                return openMediaClickIntent(key);
            }
            // Preserve the ROM's notification/keyguard/BAL/menu/group policy. No direct PI fallback.
            boolean handled = ((View) row).performClick();
            log("banner body click native-row key=" + key + " handled=" + handled
                    + "; listener dispatch is not proof of app launch");
            if (handled) return true;
            // Media notifications render as a media header node whose row click can return
            // false; fall back to the media session click intent to open the app.
            return openMediaClickIntent(key);
        } catch (Throwable error) {
            error("banner body click native-row key=" + key, error);
            return openMediaClickIntent(key);
        }
    }

    private boolean openMediaClickIntent(String key) {
        try {
            android.app.PendingIntent intent = MediaNotificationBannerSource.clickIntentFor(key);
            if (intent != null && sendWithBackgroundStart(intent)) {
                log("banner body click media-intent key=" + key + " sent=true");
                return true;
            }
            // The ROM media header launches the app through SystemUI's ActivityStarter; a plain
            // PendingIntent.send() from the background is blocked by background-activity-start
            // rules, so fall back to launching the app's own entry activity with BAL allowed.
            String packageName = packageNameOfKey(key);
            if (!TextUtils.isEmpty(packageName) && systemUiContext != null) {
                android.content.Intent launch = systemUiContext.getPackageManager()
                        .getLaunchIntentForPackage(packageName);
                if (launch != null) {
                    launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (Build.VERSION.SDK_INT >= 34) {
                        android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
                        options.setPendingIntentBackgroundActivityStartMode(
                                android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                        systemUiContext.startActivity(launch, options.toBundle());
                    } else {
                        systemUiContext.startActivity(launch);
                    }
                    log("banner body click app-launch key=" + key + " package=" + packageName);
                    return true;
                }
            }
            log("banner body click unavailable key=" + key + " reason=row-and-media-intent");
            return false;
        } catch (Throwable error) {
            error("banner body click media-intent key=" + key, error);
            return false;
        }
    }

    /** Sends a media click intent with background-activity-start allowed where the API supports it. */
    private boolean sendWithBackgroundStart(android.app.PendingIntent intent) {
        try {
            if (Build.VERSION.SDK_INT >= 34 && systemUiContext != null) {
                android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
                options.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                intent.send(systemUiContext, 0, null, null, null, null, options.toBundle());
            } else {
                intent.send();
            }
            return true;
        } catch (Throwable throwable) {
            error("banner body click pending intent send", throwable);
            return false;
        }
    }

    private boolean performNotificationRowClick(Object directEntry, String key, String mode) {
        if (TextUtils.isEmpty(key) && directEntry == null) return false;
        Object entry = directEntry;
        WeakReference<Object> reference = notificationEntries.get(key);
        if (entry == null) entry = reference == null ? null : reference.get();
        if (entry == null) {
            notificationEntries.remove(key);
            log(mode + " notification row click unavailable key=" + key + " reason=entry");
            return false;
        }
        Object row = getField(entry, "row");
        if (!(row instanceof View)) {
            log(mode + " notification row click unavailable key=" + key + " reason=row");
            return false;
        }
        View rowView = (View) row;
        Handler handler = mainHandler;
        if (handler == null) return false;
        if (Looper.myLooper() == handler.getLooper()) {
            boolean clicked = rowView.performClick();
            log(mode + " notification row click key=" + key + " result=" + clicked);
            return clicked;
        }
        handler.post(() -> {
            boolean clicked = rowView.performClick();
            log(mode + " notification row click key=" + key + " result=" + clicked);
        });
        log(mode + " notification row click scheduled key=" + key);
        return true;
    }

    private void hookDynamicIslandSystemProperty() {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.os.SystemProperties",
                    classLoader,
                    "getBoolean",
                    String.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if ((!com.hyperos3.focusrestore.BuildConfig.DEBUG || currentSettings.disableIslandProperty)
                                    && "feature.island.debug".equals(param.args[0])) {
                                param.setResult(false);
                                log("Dynamic Island property override: feature.island.debug=false");
                            }
                        }
                    });
        } catch (Throwable t) {
            error("hookDynamicIslandSystemProperty", t);
        }
    }

    private void disableDynamicIslandFeatureCache() {
        if (com.hyperos3.focusrestore.BuildConfig.DEBUG && !currentSettings.disableIslandFeatureCache) return;
        try {
            Class<?> config = FocusReflection.findClass(
                    "com.android.systemui.statusbar.notification.DynamicFeatureConfig",
                    classLoader);
            XposedHelpers.setStaticBooleanField(config, "FEATURE_DYNAMIC_ISLAND", false);
            log("Dynamic Island feature cache disabled: FEATURE_DYNAMIC_ISLAND=false");
        } catch (Throwable t) {
            // The property hook still covers initialization if this class is not loaded yet.
            error("set FEATURE_DYNAMIC_ISLAND", t);
        }
    }

    /**
     * 诊断：打印状态栏视图树（类名 / id / 可见性 / 尺寸 / 透明度 / 背景色）。
     *
     * <p>用于定位"屏蔽超级岛后仍然全黑显示脑门"的绘制者：插件岛通路与
     * {@code DisplayCutoutBaseView.drawCutouts} 经日志确认都未被调用，需要直接看视图树。
     */
    private void hookStatusBarTreeDump() {
        hookTreeDump("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView");
        hookTreeDump("com.android.systemui.statusbar.phone.PhoneStatusBarView");
    }

    private void hookTreeDump(String className) {
        Class<?> owner = FocusReflection.findClass(classLoader, className);
        if (owner == null) {
            log("status bar tree dump skipped: " + className + " missing");
            return;
        }
        XposedBridge.hookAllMethods(owner, "onFinishInflate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof View)) return;
                View root = (View) param.thisObject;
                root.post(() -> {
                    log("status bar tree root=" + className);
                    dumpViewTree(root, 0, new StringBuilder());
                });
            }
        });
    }

    private void dumpViewTree(View view, int depth, StringBuilder indent) {
        if (view == null || depth > 6) return;
        log(indent + view.getClass().getSimpleName()
                + " id=" + viewId(view)
                + " vis=" + view.getVisibility()
                + " " + view.getWidth() + "x" + view.getHeight()
                + " alpha=" + view.getAlpha()
                + " bg=" + backgroundDescription(view.getBackground())
                + " drawable=" + imageDrawableDescription(view));
        if (!(view instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        indent.append("  ");
        for (int index = 0; index < group.getChildCount(); index++) {
            dumpViewTree(group.getChildAt(index), depth + 1, indent);
        }
        indent.setLength(indent.length() - 2);
    }

    private String viewId(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return "-";
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private String backgroundDescription(android.graphics.drawable.Drawable background) {
        if (background == null) return "null";
        if (background instanceof android.graphics.drawable.ColorDrawable) {
            return "Color#"
                    + Integer.toHexString(((android.graphics.drawable.ColorDrawable) background).getColor());
        }
        return background.getClass().getSimpleName();
    }

    private String imageDrawableDescription(View view) {
        if (!(view instanceof android.widget.ImageView)) return "-";
        return backgroundDescription(((android.widget.ImageView) view).getDrawable());
    }

    /**
     * 接口事实来源：K80u SystemUI 17.03.260226.r 的 CommandQueueDelegate.setStatus、
     * DeviceNotificationListenerImpl.handleDeviceNotification、DynamicIslandPluginController.onPluginLoaded。
     * 小米私有协议许可未确认，仅按接口独立实现，未复制 ROM 代码。
     * 岛关闭时 ROM 不注册设备通知 listener，所以命令入口与内容模型入口必须同时覆盖。
     */
    private void hookDeviceNotificationConversion() {
        hookDeviceEntry("com.miui.systemui.statusbar.CommandQueueDelegate", "setStatus", true);
        hookDeviceEntry("com.android.systemui.devicenotification.listener.DeviceNotificationListenerImpl",
                "handleDeviceNotification", false);
        hookDeviceNotificationRemoval();
        hookDeviceBatteryUpdates();
    }

    /** 岛关闭后原电池 callback 不注册；从 ROM 全局电池入口取得真实厂商状态，而不是猜广播扩展字段。 */
    private void hookDeviceBatteryUpdates() {
        try {
            Class<?> listener = FocusReflection.findClass(classLoader,
                    "com.android.systemui.devicenotification.listener.DeviceNotificationListenerImpl");
            if (listener != null) XposedBridge.hookAllConstructors(listener, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    deviceListenerRef = new WeakReference<>(param.thisObject);
                    debug("device stage=battery listener=captured");
                }
            });
            Class<?> monitor = FocusReflection.findClass(classLoader, "com.android.keyguard.KeyguardUpdateMonitor");
            if (monitor == null) {
                debug("device stage=install battery result=monitor-missing");
                return;
            }
            int count = XposedBridge.hookAllMethods(monitor, "handleBatteryUpdate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || param.args[0] == null) return;
                    try { handleDeviceBatteryUpdate(param.args[0]); }
                    catch (Throwable error) { HyperOS3FocusRestoreHook.this.error("device battery update", error); }
                }
            }).size();
            debug("device stage=install battery hooks=" + count);
        } catch (Throwable error) { error("device battery hook", error); }
    }

    private synchronized void handleDeviceBatteryUpdate(Object status) throws Exception {
        if (!modeHooksInstalled) return;
        Integer plugged = deviceBatteryInt(status, "plugged");
        Integer state = deviceBatteryInt(status, "status");
        Integer level = deviceBatteryInt(status, "level");
        Integer wire = deviceBatteryInt(status, "wireState");
        Integer speed = deviceBatteryInt(status, "chargeSpeed");
        Integer watts = deviceBatteryInt(status, "maxChargingWattage");
        if (plugged == null || state == null || level == null || wire == null) {
            debug("device stage=battery result=required-field-missing");
            return;
        }
        int chargeSpeed = speed == null ? 0 : speed;
        DeviceChargingEventPolicy.Event event = deviceChargingPolicy.observe(plugged, state,
                chargeSpeed, watts == null ? 0 : watts, wire);
        debug("device stage=battery event=" + event + " plugged=" + plugged + " status=" + state
                + " level=" + level + " wire=" + wire + " speed=" + speed + " watts=" + watts);
        if (event == DeviceChargingEventPolicy.Event.CANCEL) {
            deviceChargeSessionClosed = true;
            cancelDeviceCharge("battery-disconnected");
            return;
        }
        if (event == DeviceChargingEventPolicy.Event.START) {
            deviceChargeSessionClosed = false;
            deviceChargeDeadline = 0L;
        }
        if (installedHookMode != FocusRestoreSettings.HOOK_MODE_OS4) return;
        if (event != DeviceChargingEventPolicy.Event.START && event != DeviceChargingEventPolicy.Event.UPDATE) return;
        // 已超时的充电提示不因普通电池状态变化重新弹出；只有新接电事件启动新提示。
        if (event == DeviceChargingEventPolicy.Event.UPDATE && !"charge".equals(activeDeviceEventId)
                && (deviceChargeSessionClosed || deviceChargeDeadline == 0L
                || SystemClock.elapsedRealtime() >= deviceChargeDeadline)) {
            debug("device stage=battery result=update-without-active-charge");
            return;
        }
        boolean wireless = wire == 10;
        long chargeWindowMs = wireless ? 10000L : 5000L;
        if (event == DeviceChargingEventPolicy.Event.START) {
            deviceChargeDeadline = SystemClock.elapsedRealtime() + chargeWindowMs;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("notifyId", "charge");
        metadata.put("package_name", "miui.systemui.plugin");
        metadata.put("duration", wireless ? 10000L : 5000L);
        DeviceNotificationPayload payload = null;
        Object listener = deviceListenerRef.get();
        if (listener != null) {
            try {
                Method builder = findChargeModelBuilder(listener.getClass(), status.getClass());
                if (builder == null) {
                    debug("device stage=battery-model result=builder-method-missing runtime="
                            + status.getClass().getName());
                    throw new NoSuchMethodException("structModelForCharge");
                }
                int modelType = chargeSpeed == 0 ? 0 : chargeSpeed == 3 ? 2 : 1;
                Object model = builder.invoke(listener, Integer.toString(level), modelType, status);
                payload = DeviceNotificationPayload.read(model, metadata, true,
                        message -> debug("device stage=battery-model " + message));
                debug("device stage=battery-model result=" + (model == null ? "null" : "rom-builder")
                        + " type=" + modelType);
            } catch (Throwable error) {
                debug("device stage=battery-model result=builder-failed error=" + error);
            }
        } else debug("device stage=battery-model result=listener-unavailable");
        if (payload == null || TextUtils.isEmpty(payload.text)) {
            Context context = systemUiContext;
            if (context == null) {
                debug("device stage=battery-resource result=context-not-ready");
                return;
            }
            String resource = chargeSpeed == 0 ? "strong_toast_charging" : "strong_toast_quick_charging";
            int id = context.getResources().getIdentifier(resource, "string", SYSTEM_UI);
            debug("device stage=battery-resource name=" + resource + " id=" + id);
            if (id == 0 || level < 0 || level > 100) {
                debug("device stage=battery-resource result=resource-or-level-invalid");
                return;
            }
            payload = DeviceNotificationPayload.chargingResource(context.getString(id), level, wireless);
        }
        postDevicePayload(payload, "batteryFallback");
    }

    private Integer deviceBatteryInt(Object status, String field) {
        Object value = readField(status, field);
        if (value instanceof Number) return ((Number) value).intValue();
        debug("device stage=battery-field name=" + field + " result=missing-or-invalid");
        return null;
    }
    private Method findChargeModelBuilder(Class<?> listenerClass, Class<?> statusClass) {
        for (Method method : listenerClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!"structModelForCharge".equals(method.getName()) || parameters.length != 3
                    || parameters[0] != String.class || parameters[1] != int.class
                    || !parameters[2].isAssignableFrom(statusClass)) continue;
            try { method.setAccessible(true); } catch (Throwable ignored) {
                debug("device stage=battery-model method-access=restricted");
            }
            return method;
        }
        return null;
    }

    private synchronized void cancelDeviceCharge(String reason) {
        debug("device stage=cancel reason=" + reason + " active=" + activeDeviceEventId);
        if (!"charge".equals(activeDeviceEventId)) return;
        if (deviceNotificationFocusPoster != null) deviceNotificationFocusPoster.cancel();
        activeDeviceEventId = null;
        pendingDeviceFocusId = -1;
    }

    private void hookDeviceEntry(String className, String methodName, boolean command) {
        try {
            Class<?> owner = FocusReflection.findClass(classLoader, className);
            if (owner == null) {
                debug("device stage=install entry=" + className + "." + methodName + " result=class-missing");
                return;
            }
            int count = XposedBridge.hookAllMethods(owner, methodName, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (command && !hasStrongToastAction(param.args)) return;
                    param.setObjectExtra("focusrestore.device.previous", deviceConversionPosted.get());
                    param.setObjectExtra("focusrestore.device.entered", Boolean.TRUE);
                    try {
                        debug("device stage=entry source=" + methodName + " mode=OS" + installedHookMode
                                + " args=" + describeDeviceNotification(param.args));
                        if (!modeHooksInstalled) {
                            debug("device stage=entry source=" + methodName + " result=mode-not-ready");
                            return;
                        }
                        if (Boolean.TRUE.equals(deviceConversionPosted.get())) {
                            debug("device stage=entry source=" + methodName + " result=already-posted-in-command");
                            return;
                        }
                        if (convertDeviceEntry(param.args, command, methodName)) deviceConversionPosted.set(true);
                    } catch (Throwable error) {
                        HyperOS3FocusRestoreHook.this.error("device entry " + methodName, error);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!Boolean.TRUE.equals(param.getObjectExtra("focusrestore.device.entered"))) return;
                    Object previous = param.getObjectExtra("focusrestore.device.previous");
                    if (previous == null) deviceConversionPosted.remove();
                    else deviceConversionPosted.set((Boolean) previous);
                    if (param.hasThrowable()) debug("device stage=entry-return source=" + methodName
                            + " romError=" + param.getThrowable());
                }
            }).size();
            debug("device stage=install entry=" + className + "." + methodName + " hooks=" + count);
        } catch (Throwable error) {
            error("device hook " + className + "." + methodName, error);
        }
    }

    private static boolean hasStrongToastAction(Object[] args) {
        if (args != null) for (Object arg : args) if ("strong_toast_action".equals(arg)) return true;
        return false;
    }

    private Map<String, Object> deviceMetadata(Object[] args) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (args == null) return metadata;
        for (Object arg : args) {
            if (!(arg instanceof Bundle)) continue;
            Bundle bundle = (Bundle) arg;
            for (String key : bundle.keySet()) {
                try {
                    Object value = bundle.get(key);
                    metadata.put(key, value);
                    debug("device stage=bundle key=" + key + " type="
                            + (value == null ? "null" : value.getClass().getName())
                            + " value=" + (value instanceof CharSequence ? preview(value.toString())
                            : value instanceof Number || value instanceof Boolean ? value : "<object>"));
                } catch (Throwable error) {
                    debug("device stage=bundle key=" + key + " result=read-failed error=" + error);
                }
            }
        }
        return metadata;
    }

    private boolean convertDeviceEntry(Object[] args, boolean command, String entry) {
        Map<String, Object> metadata = deviceMetadata(args);
        boolean os4 = installedHookMode == FocusRestoreSettings.HOOK_MODE_OS4;
        DeviceNotificationPayload payload = null;
        DeviceNotificationPayload.Logger logger = message -> debug("device stage=payload entry=" + entry + " " + message);
        if (command) {
            // island_param 仅在岛开启时写入；关闭时 param 的旧 guide 仍包含静音/勿扰文本。
            for (String key : new String[]{"island_param", "param"}) {
                Object json = metadata.get(key);
                debug("device stage=json entry=" + entry + " key=" + key
                        + " available=" + (json instanceof String && !((String) json).isEmpty()));
                if (!(json instanceof String)) continue;
                payload = DeviceNotificationPayload.readJson((String) json, metadata, os4, logger);
                if (payload != null && !TextUtils.isEmpty(payload.text)) break;
            }
        } else if (args != null) {
            for (Object model : args) {
                if (model == null || model instanceof Bundle) continue;
                payload = DeviceNotificationPayload.read(model, metadata, os4, logger);
                if (!TextUtils.isEmpty(payload.text)) break;
            }
        }
        if (payload == null || TextUtils.isEmpty(payload.text)) {
            // OS3 充电通知可只有 Bundle.charge 而没有 guide / island JSON。
            DeviceNotificationPayload charge = DeviceNotificationPayload.read(null, metadata, os4, logger);
            if (!TextUtils.isEmpty(charge.text)) payload = charge;
        }
        return postDevicePayload(payload, entry);
    }

    private synchronized boolean postDevicePayload(DeviceNotificationPayload payload, String entry) {
        if (payload == null || TextUtils.isEmpty(payload.text)) {
            debug("device stage=request entry=" + entry + " result=no-text legacy-fallback=available");
            return false;
        }
        // OS3 的 StrongToast 先于电池回调到达；它没有 OS4 那套接电会话状态，不能把上一条
        // 通知到期后的 closed 标记带到下一次充电提示，否则新接电时只剩竖线甚至完全没有焦点提示。
        boolean os4ChargeLifecycle = installedHookMode == FocusRestoreSettings.HOOK_MODE_OS4;
        // 同时记录模式与会话快照，避免把 OS3 的残留标记误判为 OS4 会话拒绝。
        debug("device stage=session entry=" + entry + " mode=" + installedHookMode
                + " event=" + payload.eventId + " active=" + activeDeviceEventId
                + " closed=" + deviceChargeSessionClosed + " deadline=" + deviceChargeDeadline
                + " now=" + SystemClock.elapsedRealtime()
                + " pendingId=" + pendingDeviceFocusId);
        if (os4ChargeLifecycle && "charge".equals(payload.eventId) && deviceChargeSessionClosed) {
            debug("device stage=request entry=" + entry + " result=closed-charge-session");
            return false;
        }
        PendingIntent target = payload.target instanceof PendingIntent ? (PendingIntent) payload.target : null;
        debug("device stage=request entry=" + entry + " source=" + payload.source
                + " text=" + preview(payload.text) + " icon=" + payload.iconName
                + " duration=" + payload.visibleMs + " target=" + (target != null)
                + " targetResult=" + (payload.target == null ? "absent" : target == null ? "unsupported-type" : "accepted"));
        boolean update = os4ChargeLifecycle && "charge".equals(payload.eventId)
                && "charge".equals(activeDeviceEventId);
        boolean chargeRetry = os4ChargeLifecycle && "charge".equals(payload.eventId)
                && !update && deviceChargeDeadline > 0L;
        long now = SystemClock.elapsedRealtime();
        long duration = payload.visibleMs;
        if (update || chargeRetry) {
            duration = deviceChargeDeadline - now;
            if (duration <= 0L) {
                deviceChargeSessionClosed = true;
                cancelDeviceCharge("charge-update-expired");
                return false;
            }
            if (update && now - lastDeviceChargeUpdate < 200L) {
                debug("device stage=request entry=" + entry + " result=charge-update-throttled");
                return true;
            }
        }
        boolean posted = postDeviceFocusNotification(payload.text, payload.iconName, duration,
                target, update, payload.iconPackage, payload.iconCategory, payload.iconFormat);
        if (posted) {
            if ("charge".equals(activeDeviceEventId) && !"charge".equals(payload.eventId)) {
                deviceChargeSessionClosed = true;
            }
            activeDeviceEventId = payload.eventId;
            if ("charge".equals(payload.eventId)) {
                if (!update && deviceChargeDeadline == 0L) deviceChargeDeadline = now + duration;
                lastDeviceChargeUpdate = now;
            }
        }
        debug("device stage=request entry=" + entry + " result=" + (posted ? "posted" : "failed"));
        return posted;
    }

    private void hookChargeCleanup(String className, String methodName) {
        try {
            Class<?> owner = FocusReflection.findClass(classLoader, className);
            if (owner == null) {
                debug("device stage=install cleanup=" + className + "." + methodName + " result=class-missing");
                return;
            }
            int count = XposedBridge.hookAllMethods(owner, methodName, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(deviceConversionPosted.get())) {
                        debug("device stage=cleanup source=" + methodName + " result=inside-new-event");
                        return;
                    }
                    cancelDeviceCharge("rom-" + methodName);
                }
            }).size();
            debug("device stage=install cleanup=" + methodName + " hooks=" + count);
        } catch (Throwable error) { error("device cleanup " + methodName, error); }
    }

    private void hookDeviceNotificationRemoval() {
        hookChargeCleanup("com.android.systemui.devicenotification.listener.DeviceNotificationListenerImpl", "clearChargeStatusCache");
        hookChargeCleanup("com.android.systemui.devicenotification.listener.DeviceNotificationListenerImpl", "access$releaseValueAnimation");
        hookChargeCleanup("com.android.systemui.devicenotification.listener.DeviceNotificationListenerImpl$removeChargeIslandRunnable$1", "run");
        try {
            Class<?> owner = FocusReflection.findClass(classLoader,
                    "com.android.systemui.statusbar.notification.DynamicIslandController");
            if (owner == null) {
                debug("device stage=install removal result=controller-missing");
                return;
            }
            int count = XposedBridge.hookAllMethods(owner, "removeDynamicIslandView", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !"charge".equals(param.args[0])) return;
                    debug("device stage=remove event=charge active=" + activeDeviceEventId
                            + " inCommand=" + Boolean.TRUE.equals(deviceConversionPosted.get())
                            + " romError=" + param.getThrowable());
                    // 新充电事件内部可能先撤旧岛；不能因此取消刚发出的替代通知。
                    if (!"charge".equals(activeDeviceEventId)
                            || Boolean.TRUE.equals(deviceConversionPosted.get())) return;
                    if (deviceNotificationFocusPoster != null) deviceNotificationFocusPoster.cancel();
                    activeDeviceEventId = null;
                    pendingDeviceFocusId = -1;
                }
            }).size();
            debug("device stage=install removal hooks=" + count);
        } catch (Throwable error) {
            error("device removal hook", error);
        }
    }

    private String describeDeviceNotification(Object[] args) {
        StringBuilder text = new StringBuilder();
        for (Object argument : args) {
            if (text.length() > 0) text.append(" | ");
            if (argument instanceof Bundle) {
                Bundle bundle = (Bundle) argument;
                text.append("bundle[");
                for (String key : bundle.keySet()) {
                    Object value = bundle.get(key);
                    if (value instanceof String || value instanceof Integer || value instanceof Long
                            || value instanceof Boolean) {
                        text.append(key).append('=').append(value).append(' ');
                    }
                }
                text.append(']');
            } else if (argument == null) {
                text.append("null");
            } else {
                text.append(argument.getClass().getSimpleName())
                        .append('{').append(describeDeviceNotificationModel(argument)).append('}');
            }
        }
        return text.toString();
    }

    private String describeDeviceNotificationModel(Object model) {
        StringBuilder text = new StringBuilder();
        for (String side : new String[]{"getLeft", "getRight"}) {
            Object sideValue = callGetter(model, side);
            if (sideValue == null) continue;
            Object textParams = callGetter(sideValue, "getTextParams");
            Object content = textParams == null ? null : callGetter(textParams, "getText");
            text.append(side).append('=').append(content).append(' ');
        }
        return text.toString().trim();
    }

    private Object callGetter(Object target, String method) {
        if (target == null) return null;
        try {
            return XposedHelpers.callMethod(target, method);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 去掉强提示（StrongToast）里那块横跨状态栏的黑色"脑门"。
     *
     * <p>设备通知（充电/静音/勿扰）在 {@code FEATURE_DYNAMIC_ISLAND=false} 时不再走岛，而是走
     * {@code com.miui.toast.MIUIStrongToast}：它自带名为 {@code StrongToastView} 的独立窗口，
     * 其中 {@code mCutOut} 覆盖挖孔、{@code mRoundRect}（{@code com.miui.toast.view.RoundRect}）
     * 画出两端外反圆角的黑色圆角矩形。拦掉 RoundRect 的绘制即可消除黑底，图标与文字不受影响。
     */
    private void hideStrongToastMask() {
        try {
            Class<?> roundRect = FocusReflection.findClass(classLoader,
                    "com.miui.toast.view.RoundRect");
            if (roundRect == null) {
                log("strong toast mask kept: RoundRect missing");
            } else {
                suppressCutoutDraw(roundRect, "onDraw", "strong-toast-round-rect");
                log("strong toast mask suppressed: RoundRect.onDraw skipped");
            }
        } catch (Throwable t) {
            error("hideStrongToastMask", t);
        }
        suppressStrongToastWindow();
        logStrongToastCalls();
    }

    /**
     * 直接不显示强提示窗口。
     *
     * <p>日志已确认充电时必然走到 {@code showCustomStrongToast → getWindowParam → setValue}，
     * 而 {@code RoundRect.onDraw} 从未被调用，说明那块横跨状态栏的黑条由该窗口内其它视图绘制
     * （候选：{@code mCutOut} / {@code mSTBgFL} / {@code mSTBgCenterIv}）。这里先把窗口整个按掉，
     * 若仍出现黑条，{@code onAttachedToWindow} 上的视图树 dump 会给出确切节点。
     */
    private void suppressStrongToastWindow() {
        Class<?> toast = FocusReflection.findClass(classLoader, "com.miui.toast.MIUIStrongToast");
        if (toast == null) {
            log("strong toast window kept: MIUIStrongToast missing");
            return;
        }
        suppressStrongToastShow(toast, "showCustomStrongToast");
        suppressStrongToastShow(toast, "showStrongToast");
        XposedBridge.hookAllMethods(toast, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof View)) return;
                View root = (View) param.thisObject;
                root.post(() -> {
                    log("strong toast tree:");
                    dumpViewTree(root, 0, new StringBuilder());
                });
            }
        });
    }

    private void suppressStrongToastShow(Class<?> owner, String name) {
        try {
            int hooked = XposedBridge.hookAllMethods(owner, name, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // 先按掉窗口：横幅只是替代呈现，它自己失败不能把强提示放回屏幕。
                    param.setResult(null);
                    try {
                        debug("device stage=legacy source=" + name + " " + describeStrongToast(param.args));
                        showDeviceNotificationBanner(param.args);
                    } catch (Throwable t) {
                        error("device notification banner", t);
                    }
                }
            }).size();
            log("strong toast suppression " + name + " hooks=" + hooked);
        } catch (Throwable t) {
            error("suppressStrongToastShow " + name, t);
        }
    }

    /**
     * 读取强提示自带的状态栏内容模型。
     *
     * <p>{@code StrongToastModel.statusBarGuideModel} 就是 ROM 给状态栏/岛准备的内容
     * （left/center/right 各自的 text/textColor 与 iconResName），岛开着时渲染成岛，
     * 关掉后只剩强提示窗口那块黑底。转焦点通知直接复用这份数据，不再自行拼文案。
     */
    private String describeStrongToast(Object[] args) {
        if (args.length == 0 || args[0] == null) return "<no-model>";
        Object model = args[0];
        StringBuilder text = new StringBuilder("category=")
                .append(readField(model, "strongToastCategory"))
                .append(" charge=").append(readField(model, "charge"))
                .append(" rate=").append(readField(model, "chargeRate"))
                .append(" duration=").append(readField(model, "duration"));
        Object guide = readField(model, "statusBarGuideModel");
        if (guide == null) return text.append(" guide=null").toString();
        for (String side : new String[]{"getLeft", "getCenter", "getRight"}) {
            GuidePart part = readGuidePart(guide, side);
            if (part == null) continue;
            text.append(' ').append(side).append("[text=").append(part.text)
                    .append(" color=").append(part.textColor)
                    .append(" icon=").append(part.iconResName)
                    .append(']');
        }
        return text.toString();
    }

    /** 一条 guide 侧栏（left/center/right）里的文本、颜色与图标名；该侧栏缺失时为 null。 */
    private GuidePart readGuidePart(Object guide, String getter) {
        Object side = callGetter(guide, getter);
        if (side == null) return null;
        String text = null;
        Integer textColor = null;
        String iconResName = null;
        Object textParams = callGetter(side, "getTextParams");
        if (textParams != null) {
            Object value = callGetter(textParams, "getText");
            if (value instanceof String && !((String) value).isEmpty()) text = (String) value;
            Object color = callGetter(textParams, "getTextColor");
            if (color instanceof Integer) textColor = (Integer) color;
        }
        Object iconParams = callGetter(side, "getIconParams");
        if (iconParams != null) {
            Object value = callGetter(iconParams, "getIconResName");
            if (value instanceof String && !((String) value).isEmpty()) {
                iconResName = (String) value;
            }
        }
        return new GuidePart(text, textColor, iconResName);
    }

    private Object readField(Object target, String name) {
        if (target == null) return null;
        try {
            return XposedHelpers.getObjectField(target, name);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把强提示携带的状态栏内容改由系统焦点通知呈现。
     *
     * <p>勿扰/静音取 guide 的文本与图标（左右两侧分工：文本在 left，图标在 right），充电没有
     * guide，取 {@code charge} 文案；停留时长取模型的 {@code duration}，点击动作取 {@code target}。
     * 模块按 ROM 的焦点契约构造一条通知（{@link DeviceNotificationFocusPoster}），由系统自己把它
     * 显示在状态栏焦点位。
     */
    private void showDeviceNotificationBanner(Object[] args) {
        if (!modeHooksInstalled || Boolean.TRUE.equals(deviceConversionPosted.get())) {
            debug("device stage=legacy result=" + (!modeHooksInstalled ? "mode-not-ready" : "already-posted-in-command"));
            return;
        }
        convertDeviceEntry(args, false, "strongToastFallback");
    }

    /**
     * 发一条按 ROM 焦点契约构造的通知，让系统自己显示在状态栏焦点位。
     *
     * <p>没有横幅兜底：这条通路不可用时只记录原因（设备通知呈现本就依赖系统焦点通路）。
     */
    private synchronized boolean postDeviceFocusNotification(String text, String iconResName, long visibleMs,
                                                PendingIntent target, boolean update,
                                                String iconPackage, String iconCategory, String iconFormat) {
        Context context = systemUiContext;
        if (context == null) {
            debug("device stage=post result=context-not-ready");
            return false;
        }
        DeviceNotificationFocusPoster poster = deviceNotificationFocusPoster;
        if (poster == null) {
            poster = new DeviceNotificationFocusPoster(context);
            deviceNotificationFocusPoster = poster;
        }
        if (!poster.isAvailable()) {
            debug("device stage=post result=unavailable");
            return false;
        }
        if (!poster.post(text, iconResName, visibleMs, target, update,
                iconPackage, iconCategory, iconFormat)) return false;
        deviceFocusPostGeneration = poster.lastPostSequence();
        pendingDeviceFocusId = poster.lastPostedId();
        final long sequence = deviceFocusPostGeneration;
        final DeviceNotificationFocusPoster activePoster = poster;
        final int id = pendingDeviceFocusId;
        Handler handler = mainHandler;
        if (handler != null && BuildConfig.DEBUG) handler.postDelayed(() -> {
            if (sequence == deviceFocusPostGeneration && id == pendingDeviceFocusId
                    && deviceFocusDeliveredGeneration != sequence) {
                debug("device stage=delivery result=not-observed-after-1500ms id=" + id
                        + " sequence=" + sequence + " mode=OS" + installedHookMode);
            }
        }, 1500L);
        if (handler != null) handler.postDelayed(() -> {
            synchronized (HyperOS3FocusRestoreHook.this) {
                if (sequence != deviceFocusPostGeneration || id != pendingDeviceFocusId) return;
                debug("device stage=expiry id=" + id + " sequence=" + sequence
                        + " event=" + activeDeviceEventId);
                boolean expiringCharge = "charge".equals(activeDeviceEventId);
                activePoster.cancel();
                activeDeviceEventId = null;
                pendingDeviceFocusId = -1;
                if (expiringCharge) deviceChargeSessionClosed = true;
            }
        }, update ? Math.max(1L, visibleMs) : Math.max(1000L, visibleMs));
        return true;
    }

    /**
     * 模块构造的焦点通知进入通知管线后记录 ROM 侧的判定结果。
     *
     * <p>标记是模块自己写进 extras 的，不会与系统或第三方通知冲突。
     */
    private synchronized void noteConstructedFocusNotification(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        Bundle extras = notification == null ? null : notification.extras;
        if (extras == null
                || !extras.getBoolean(DeviceNotificationFocusPoster.EXTRA_MODULE_MARKER, false)) {
            return;
        }
        // 没有待确认的投递时忽略：SystemUI 重启后系统会把历史通知重投一遍，那不是本次投递的证据。
        long sequence = extras.getLong(DeviceNotificationFocusPoster.EXTRA_POST_SEQUENCE, -1L);
        if (deviceFocusPostGeneration == 0 || sbn.getId() != pendingDeviceFocusId
                || sequence != deviceFocusPostGeneration) {
            debug("device stage=delivery result=stale-or-untracked id=" + sbn.getId()
                    + " sequence=" + sequence + " expected=" + deviceFocusPostGeneration);
            return;
        }
        if (deviceFocusDeliveredGeneration == sequence) return;
        deviceFocusDeliveredGeneration = sequence;
        debug("device stage=delivery result=observed id=" + sbn.getId() + " sequence=" + sequence);
        // 一次真机日志就要能定位失败点，所以把 ROM 的判定与提示内容一起打出来。
        log("device focus notification delivered key=" + sbn.getKey()
                + describeFocusGates(sbn));
        scheduleFocusPromptTextDump(sbn.getKey());
    }

    /**
     * 诊断构造的焦点通知在 ROM 侧的判定结果。
     *
     * <p>{@code mIsFocusNotification} 看模块写的 extras 有没有被 ROM 认下；{@code showOnStatusBar}
     * 是状态栏显示的闸门；{@code ticker} 与 {@code tickerIcon} 是提示最终显示的文字与图标。
     */
    private String describeFocusGates(StatusBarNotification sbn) {
        StringBuilder text = new StringBuilder(" isFocusNotification=")
                .append(getBooleanField(sbn, "mIsFocusNotification", false));
        try {
            Class<?> utils = FocusReflection.findClass(classLoader,
                    "com.android.systemui.statusbar.notification.utils.FocusUtils");
            if (utils == null) return text.append(" focusUtils=missing").toString();
            Object showOnStatusBar = XposedHelpers.callStaticMethod(utils, "showOnStatusBar", sbn);
            Object ticker = XposedHelpers.callStaticMethod(utils, "getStatusBarTicker", sbn);
            // OS4 FocusUtils 已无 getStatusBarTickerIcon；直接按 extras 二级键读取，不猜 ROM 方法。
            SelectedFocusIcon tickerIcon = selectFocusIcon(sbn.getNotification(), null,
                    sbn.getPackageName(), false, true);
            SelectedFocusIcon darkIcon = selectFocusIcon(sbn.getNotification(), null,
                    sbn.getPackageName(), true, true);
            text.append(" showOnStatusBar=").append(showOnStatusBar)
                    .append(" ticker=").append(preview(stringValue(ticker)))
                    .append(" tickerIcon=").append(tickerIcon == null ? "none" : tickerIcon.source)
                    .append(" tickerIconDark=").append(darkIcon == null ? "none" : darkIcon.source);
        } catch (Throwable t) {
            text.append(" gates=error:").append(t);
        }
        return text.toString();
    }

    /** guide 侧栏的文本／颜色／图标；{@code StatusBarGuideModel$TextParams} 与 {@code $IconParams} 的可读字段。 */
    private static final class GuidePart {
        final String text;
        final Integer textColor;
        final String iconResName;

        GuidePart(String text, Integer textColor, String iconResName) {
            this.text = text;
            this.textColor = textColor;
            this.iconResName = iconResName;
        }
    }

    /** 诊断：强提示实际走了哪些方法，每个方法名只记一次。 */
    private void logStrongToastCalls() {
        Class<?> toast = FocusReflection.findClass(classLoader, "com.miui.toast.MIUIStrongToast");
        if (toast == null) {
            log("strong toast call trace skipped: MIUIStrongToast missing");
            return;
        }
        int hooked = 0;
        for (java.lang.reflect.Method method : toast.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(method.getModifiers())) continue;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    private boolean reported;

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (reported) return;
                        reported = true;
                        log("strong toast call " + method.getName());
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
                // 单个方法挂不上不影响其余诊断。
            }
        }
        log("strong toast call trace hooks=" + hooked);
    }

    /**
     * 隐藏系统自身绘制的挖孔覆盖层。
     *
     * <p>0.21.48/0.21.49 的日志证明插件岛通路一次都没走（FEATURE_DYNAMIC_ISLAND=false 已把插件岛关死），
     * 但黑色"脑门"仍在，说明它不是岛画的，而是 {@code DisplayCutoutBaseView} 把
     * {@code DisplayCutout.getCutoutPath()} 涂黑的结果：{@code onDraw} → {@code drawCutouts(Canvas)}，
     * 另有 {@code drawCutoutProtection(Canvas)} 负责相机保护区域。两者都不画，挖孔区就不会再出现黑块。
     */
    private void hideDisplayCutoutPainting() {
        try {
            Class<?> cutoutView = FocusReflection.findClass(classLoader,
                    "com.android.systemui.DisplayCutoutBaseView");
            if (cutoutView == null) {
                log("display cutout painting kept: DisplayCutoutBaseView missing");
                return;
            }
            suppressCutoutDraw(cutoutView, "drawCutouts", "cutout");
            suppressCutoutDraw(cutoutView, "drawCutoutProtection", "protection");
            log("display cutout painting suppressed: drawCutouts/drawCutoutProtection skipped");
        } catch (Throwable t) {
            error("hideDisplayCutoutPainting", t);
        }
    }

    /** 每类只记录第一次命中，避免逐帧刷屏。 */
    private void suppressCutoutDraw(Class<?> owner, String name, String label) {
        XposedBridge.hookAllMethods(owner, name, new XC_MethodHook() {
            private boolean reported;

            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(null);
                if (reported) return;
                reported = true;
                log("display cutout painting suppressed first " + label + " draw skipped");
            }
        });
    }

    /**
     * 屏蔽原生超级岛的视图层，在插件 ClassLoader 就绪后调用。
     *
     * <p>{@code FEATURE_DYNAMIC_ISLAND=false} 只挡住 SystemUI 侧的内容下发与图标归并；岛窗口仍可能被
     * 插件创建出来，此时没有内容、只剩插件 {@code DynamicIslandBackgroundView} 画的那块黑色圆角背景，
     * 表现就是整个状态栏全黑显示"脑门"。所以这里在插件入口拦截：不新增、不更新岛视图，
     * 并把背景透明度钉为 0（只让黑底不可见，不改动插件的布局与状态计算）。
     *
     * <p>岛内容转焦点通知由 {@code islandCompat} 通路负责，这里不做任何转换。
     */
    private void blockDynamicIslandViews(ClassLoader pluginLoader) {
        if (islandViewsBlocked) return;
        try {
            Class<?> contentPlugin = FocusReflection.findClass(pluginLoader,
                    "miui.systemui.notification.NotificationDynamicIslandPluginImpl");
            Class<?> windowView = FocusReflection.findClass(pluginLoader,
                    "miui.systemui.dynamicisland.window.DynamicIslandWindowView");
            Class<?> windowController = FocusReflection.findClass(pluginLoader,
                    "miui.systemui.dynamicisland.window.DynamicIslandWindowViewController");
            Class<?> background = FocusReflection.findClass(pluginLoader,
                    "miui.systemui.dynamicisland.DynamicIslandBackgroundView");
            if (background == null) {
                log("Dynamic Island view blocking skipped: contentPlugin=" + (contentPlugin != null)
                        + " windowView=" + (windowView != null)
                        + " controller=" + (windowController != null) + " background=null");
                return;
            }
            suppressIslandViewCall(contentPlugin, "addDynamicIslandView");
            suppressIslandViewCall(contentPlugin, "updateDynamicIslandView");
            suppressIslandViewCall(windowView, "updateDynamicIslandView");
            suppressIslandViewCall(windowView, "updateDynamicIslandViewSuspend");
            suppressIslandViewCall(windowController, "addDynamicIslandView");
            suppressIslandViewCall(windowController, "updateDynamicIslandView");
            // 兜底：黑色圆角底由该 View 的 onDraw 画，直接不画；并让它始终不可见、透明。
            XposedBridge.hookAllMethods(background, "onDraw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                }
            });
            XposedBridge.hookAllMethods(background, "setVisibility", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 1 && param.args[0] instanceof Integer) {
                        param.args[0] = View.GONE;
                    }
                }
            });
            XposedBridge.hookAllMethods(background, "alphaAnimation", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 1 && param.args[0] instanceof Float) param.args[0] = 0f;
                }
            });
            if (contentPlugin != null) {
                XposedBridge.hookAllMethods(contentPlugin, "handleDynamicIsland", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        log("Dynamic Island command "
                                + (param.args.length > 0 ? islandCommand(param.args[0]) : "<none>"));
                    }
                });
            }
            islandViewsBlocked = true;
            log("Dynamic Island views blocked: entries suppressed, background never drawn"
                    + " contentPlugin=" + (contentPlugin != null) + " windowView=" + (windowView != null)
                    + " controller=" + (windowController != null));
        } catch (Throwable t) {
            error("blockDynamicIslandViews", t);
        }
    }

    /** 按方法名整组拦截，避免依赖只在插件 ClassLoader 里可见的参数类型。 */
    private void suppressIslandViewCall(Class<?> owner, String name) {
        if (owner == null) return;
        XposedBridge.hookAllMethods(owner, name, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(null);
                log("Dynamic Island view suppressed: " + owner.getSimpleName() + "#" + name);
            }
        });
    }

    /** 打印插件命令的 action 与标量参数，用于定位设备通知等真实入口。 */
    private static String islandCommand(Object argument) {
        if (!(argument instanceof Bundle)) return String.valueOf(argument);
        Bundle bundle = (Bundle) argument;
        StringBuilder text = new StringBuilder("action=").append(bundle.getString("action_key"));
        for (String key : bundle.keySet()) {
            if ("action_key".equals(key)) continue;
            Object value = bundle.get(key);
            if (value instanceof String || value instanceof Integer || value instanceof Boolean) {
                text.append(' ').append(key).append('=').append(value);
            }
        }
        return text.toString();
    }

    private void hookShowOnStatusBar() {
        try {
            Class<?> utils = FocusReflection.findClass(
                    "com.android.systemui.statusbar.notification.utils.FocusUtils",
                    classLoader);
            Class<?> expanded = FocusReflection.findClass(
                    "com.android.systemui.statusbar.notification.ExpandedNotification",
                    classLoader);

            XposedHelpers.findAndHookMethod(utils, "showOnStatusBar", expanded,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!currentSettings.islandCompat && !currentSettings.mediaFocusEnabled) return;
                            FocusData data = inspectExpanded(param.args[0]);
                            if (isFocusDisplayExpired(data)) {
                                clearPreMark(param.args[0], true);
                                log("focus display limit reached before show key="
                                        + (data == null ? "null" : data.key));
                                return;
                            }
                            if (data != null && currentSettings.mediaFocusEnabled
                                    
                                    && activeMediaNotificationKeys.contains(data.key)
) {
                                try {
                                    boolean originalFocus = getBooleanField(param.args[0],
                                            "mIsFocusNotification", false);
                                    preMarkedOriginalFocus.put(param.args[0], originalFocus);
                                    preMarkedIslands.add(param.args[0]);
                                    XposedHelpers.setBooleanField(param.args[0],
                                            "mIsFocusNotification", true);
                                    debug("media premark key=" + data.key + " package="
                                            + data.packageName);
                                } catch (Throwable t) {
                                    error("markMediaFocusBeforeShow", t);
                                }
                            }
                            IslandText islandText = (data != null && shouldConvert(data))
                                    ? extractIslandContent(data) : null;
                            if (islandText != null && !TextUtils.isEmpty(islandText.text)) {
                                try {
                                    boolean originalFocus = getBooleanField(param.args[0],
                                            "mIsFocusNotification", false);
                                    preMarkedOriginalFocus.put(param.args[0], originalFocus);
                                    XposedHelpers.setBooleanField(param.args[0], "mIsFocusNotification", true);
                                    preMarkedIslands.add(param.args[0]);
                                    log("marked island notification for focus conversion source="
                                            + islandText.source);
                                } catch (Throwable t) {
                                    error("markIslandFocusBeforeShow", t);
                                }
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            FocusData data = inspectExpanded(param.args[0]);
                            if (data == null) return;
                            if (isFocusDisplayExpired(data)) {
                                clearPreMark(param.args[0], true);
                                param.setResult(false);
                                log("focus display limit reached key=" + data.key);
                                return;
                            }

                            boolean original = Boolean.TRUE.equals(param.getResult());
                            if (data != null && currentSettings.mediaFocusEnabled
                                    && activeMediaNotificationKeys.contains(data.key)
) {
                                try {
                                    XposedHelpers.setBooleanField(param.args[0],
                                            "mIsFocusNotification", true);
                                } catch (Throwable t) {
                                    error("markMediaFocus", t);
                                }
                                param.setResult(true);
                                markFocusDisplayed(data);
                                log("media converted to focus key=" + data.key
                                        + " playing=" + (data.mediaContent == null ? "unknown"
                                        : data.mediaContent.playing));
                                return;
                            }
                            IslandText islandText = (!data.isOriginalFocus && currentSettings.islandCompat)
                                    ? extractIslandContent(data) : null;
                            if (islandText != null && !TextUtils.isEmpty(islandText.text)) {
                                try {
                                    XposedHelpers.setBooleanField(param.args[0], "mIsFocusNotification", true);
                                } catch (Throwable t) {
                                    error("markIslandFocus", t);
                                }
                                param.setResult(true);
                                markFocusDisplayed(data);
                                log("island converted to focus source=" + islandText.source
                                        + " content=" + islandText.text);
                                return;
                            }
                            clearPreMark(param.args[0], true);
                            boolean fallback = data.isFocus && data.hasMainRv;
                            if (!original && fallback) {
                                param.setResult(true);
                                markFocusDisplayed(data);
                                log("showOnStatusBar fallback=true " + data.summary());
                            } else {
                                log("showOnStatusBar=" + original + " " + data.summary());
                            }
                        }
                    });
        } catch (Throwable t) {
            error("hookShowOnStatusBar", t);
        }
    }

    private void hookPromptViewSetData() {
        try {
            Class<?> view = FocusReflection.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptView",
                    classLoader);
            Class<?> bean = FocusReflection.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptController$FocusedNotifBean",
                    classLoader);


            XposedHelpers.findAndHookMethod(view, "setData", bean, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object bean = param.args[0];
                            patchBean(bean, "before setData");
                            // The application's own RemoteViews are hidden on this prompt view when
                            // this module writes its own text, and the ROM only ever puts them back
                            // through updateRemoteViews — which it does not call for a notification
                            // that carries no miui.focus.rv. The prompt view is reused across
                            // notifications, so a container left GONE for an earlier notification
                            // would blank the pill of any later one this module does not manage.
                            if (!convertedBeans.contains(bean)) {
                                String reverted = restoreRemoteViewsPrompt(param.thisObject);
                                if (reverted != null) {
                                    debug("setData reverted a leaked RemoteViews hide " + reverted);
                                }
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof View) {
                                synchronized (os3PromptViews) { os3PromptViews.add((View) param.thisObject); }
                            }
                            FocusData data = inspectBean(param.args[0]);
                            log("after setData "
                                    + (data == null ? "bean=null" : data.summary()));
                            reloadSettings(false);
                            applyTextWidth(param.thisObject);
                            // A focus notification that carries no miui.focus.rv of its own never
                            // reaches updateRemoteViews, so this is the only point at which its pill
                            // can be given the parsed island text; without it the pill shows whatever
                            // the ROM derived from a notification whose text lives in the payload.
                            // Idempotent: preferConvertedIslandText reuses the text already applied
                            // to this view and only touches the view tree when that text changes.
                            preferConvertedIslandText(param.thisObject, data);
                            preferMediaText(param.thisObject, data);
                            scheduleNativeMarquee(param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            error("hookPromptViewSetData", t);
        }
    }

    private synchronized boolean reloadSettings(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && lastProviderReadAttemptMs != Long.MIN_VALUE
                && now - lastProviderReadAttemptMs < SETTINGS_REFRESH_INTERVAL_MS) {
            return hasSuccessfulProviderSettings;
        }
        lastProviderReadAttemptMs = now;
        if (!force && settingsReadQueued) return hasSuccessfulProviderSettings;
        final long generation = ++settingsReadGeneration;
        if (force) {
            synchronized (SETTINGS_READ_LOCK) {
                return readProviderSettings(generation);
            }
        }
        settingsReadQueued = true;
        SETTINGS_EXECUTOR.execute(() -> {
            try {
                synchronized (SETTINGS_READ_LOCK) {
                    readProviderSettings(generation);
                }
            } finally {
                synchronized (HyperOS3FocusRestoreHook.this) {
                    settingsReadQueued = false;
                }
            }
        });
        return hasSuccessfulProviderSettings;
    }

    private boolean readProviderSettings(long generation) {
        try {
            Context context = systemUiContext;
            if (context == null) {
                Object currentApplication = XposedHelpers.callStaticMethod(
                        Class.forName("android.app.ActivityThread"), "currentApplication");
                if (currentApplication instanceof Context) {
                    Context application = (Context) currentApplication;
                    Context applicationContext = application.getApplicationContext();
                    context = applicationContext != null ? applicationContext : application;
                    systemUiContext = context;
                }
            }
            if (context == null) {
                logProviderSettingsState(false, "application context unavailable");
                return false;
            }
            HookSettings next = HookSettingsReader.read(context);
            if (next == null) {
                logProviderSettingsState(false, "provider query returned no settings");
                return false;
            }
            synchronized (this) {
                if (generation != settingsReadGeneration) return false;
                currentSettings = next;
                if (next.mediaFocusCastPicker != FocusRestoreSettings.CAST_PICKER_MIPLAY) {
                    hideMiPlayPanel();
                }
                if (modeHooksInstalled && next.hookMode != installedHookMode) {
                    log("hook mode change saved configuredMode=OS" + next.hookMode
                            + " installedMode=OS" + installedHookMode
                            + "; restart SystemUI or device to apply");
                }
            }
            if (!next.independentFocusBanner) {
                FocusBannerController controller = bannerController;
                if (controller != null) controller.dismiss("setting-disabled");
            }
            hasSuccessfulProviderSettings = true;
            logProviderSettingsState(true, null);
            return true;
        } catch (Throwable t) {
            logProviderSettingsState(false, t.getClass().getSimpleName());
            error("readProviderSettings", t);
            return false;
        }
    }

    private void logProviderSettingsState(boolean available, String reason) {
        int state = available ? 1 : hasSuccessfulProviderSettings ? 0 : -1;
        if (providerSettingsState == state) return;
        providerSettingsState = state;
        if (available) {
            log("provider settings updated: " + currentSettings.describe());
        } else {
            log("provider settings unavailable: "
                    + (hasSuccessfulProviderSettings ? "keeping cached settings" : "using defaults")
                    + (TextUtils.isEmpty(reason) ? "" : " (" + reason + ")"));
        }
    }

    // Restored 0.7 behavior: constrain the text and its content slot, not the outer prompt.
    private void applyTextWidth(Object promptView) {
        try {
            Object value = XposedHelpers.getObjectField(promptView, "mContentText");
            if (!(value instanceof TextView)) return;
            TextView textView = (TextView) value;
            ViewGroup.LayoutParams params = textView.getLayoutParams();
            if (!currentSettings.limitWidth) {
                OriginalWidthState original;
                synchronized (originalTextWidths) {
                    original = originalTextWidths.remove(textView);
                }
                if (original == null) return;
                boolean changed = textView.getMaxWidth() != original.maxWidth;
                if (changed) textView.setMaxWidth(original.maxWidth);
                if (params != null && original.hasLayoutParams
                        && params.width != original.layoutWidth) {
                    params.width = original.layoutWidth;
                    textView.setLayoutParams(params);
                    changed = true;
                }
                if (changed) textView.requestLayout();
                log("restored system focus text width maxWidth=" + original.maxWidth
                        + " layoutWidth=" + (original.hasLayoutParams
                        ? original.layoutWidth : "unavailable"));
                return;
            }
            float density = textView.getResources().getDisplayMetrics().density;
            int configuredWidthDp = focusWidthDp(textView.getResources().getConfiguration());
            int widthPx = Math.max(1, Math.round(configuredWidthDp * density));
            synchronized (originalTextWidths) {
                OriginalWidthState original = originalTextWidths.get(textView);
                if (original == null) {
                    original = new OriginalWidthState(textView.getMaxWidth(),
                            params == null ? 0 : params.width, params != null, widthPx);
                    originalTextWidths.put(textView, original);
                } else {
                    if (textView.getMaxWidth() != original.lastAppliedWidth) {
                        original.maxWidth = textView.getMaxWidth();
                    }
                    if (params != null && params.width != original.lastAppliedWidth) {
                        original.layoutWidth = params.width;
                        original.hasLayoutParams = true;
                    }
                    original.lastAppliedWidth = widthPx;
                }
            }
            boolean changed = textView.getMaxWidth() != widthPx;
            if (changed) textView.setMaxWidth(widthPx);
            if (params != null && params.width != widthPx) {
                params.width = widthPx;
                textView.setLayoutParams(params);
                changed = true;
            }
            if (changed) textView.requestLayout();
            log("applied 0.7 manual focus text width=" + configuredWidthDp + "dp px=" + widthPx);
        } catch (Throwable t) {
            error("applyTextWidth", t);
        }
    }

    private int focusWidthDp(android.content.res.Configuration configuration) {
        boolean landscape = configuration != null
                && configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int value = landscape ? currentSettings.widthLandscapeDp : currentSettings.widthDp;
        log("focus width select orientation=" + (landscape ? "landscape" : "portrait")
                + " widthDp=" + value + " portraitDp=" + currentSettings.widthDp
                + " landscapeDp=" + currentSettings.widthLandscapeDp);
        return value;
    }

    private void startNativeMarquee(Object promptView) {
        try {
            Object value = XposedHelpers.getObjectField(promptView, "mContentText");
            if (value instanceof TextView) startNativeMarquee((TextView) value);
        } catch (Throwable t) {
            error("startNativeMarquee", t);
        }
    }

    private boolean startNativeMarquee(TextView textView) {
        try {
            if (textView.getVisibility() != View.VISIBLE) return true;
            textView.setSingleLine(true);
            textView.setHorizontallyScrolling(true);
            textView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            CharSequence content = textView.getText();
            float textWidth = content == null ? 0f
                    : textView.getPaint().measureText(content.toString());
            float visibleContentWidth = getVisibleContentWidth(textView);
            // 装得下就静态显示。0.25.3 真机（竖屏、关闭宽度限制）：textWidth=126.0 与
            // visibleContentWidth=126.0 相等，此时启动 MARQUEE 会把文字推出可见区，界面上只剩
            // 焦点分隔竖线；横屏可见区足够宽才不会出现。空文本同样不启动。
            if (visibleContentWidth > 0f && textWidth <= visibleContentWidth) {
                stopNativeMarquee(textView);
                textView.setSelected(false);
                textView.setMarqueeRepeatLimit(0);
                textView.scrollTo(0, 0);
                log("focus text fits; marquee skipped width=" + textView.getWidth()
                        + " textWidth=" + textWidth
                        + " visibleContentWidth=" + visibleContentWidth
                        + describeFocusTextGeometry(textView));
                return textView.getWidth() > 0;
            }
            textView.setFocusable(true);
            textView.setFocusableInTouchMode(true);
            // Re-selecting resets a marquee left in a completed/stale state by
            // the previous RemoteViews update.
            textView.setSelected(false);
            textView.setSelected(true);
            textView.setMarqueeRepeatLimit(MARQUEE_REPEAT_LIMIT);
            XposedHelpers.callMethod(textView, "startMarqueeLocal");
            registerActiveMarqueeOwner(textView);
            if (hasMarqueeOverflow(textView)) {
                stopNativeMarquee(textView);
                startFallbackMarquee(textView);
            }
            log("started native focus marquee width=" + textView.getWidth()
                    + " measured=" + textView.getMeasuredWidth()
                    + " selected=" + textView.isSelected()
                    + " focused=" + textView.isFocused()
                    + " textWidth=" + textView.getPaint().measureText(textView.getText().toString())
                    + " visibleContentWidth=" + getVisibleContentWidth(textView));
            return textView.getWidth() > 0;
        } catch (Throwable t) {
            error("startNativeMarqueeText", t);
            return true;
        }
    }

    private boolean startNativeMarquee(TextView textView, int attempt) {
        boolean ready = startNativeMarquee(textView);
        log("native focus marquee attempt=" + attempt);
        return ready;
    }

    /**
     * 设备通知的焦点提示显示后，把提示里的文本视图现场打两次（0.3s 与 1.3s）。
     *
     * <p>用来区分"文字颜色/几何不可见"和"显示后被清空"：前者的两次日志相同（都有文字、位置也对），
     * 后者会看到 t0 有文字、t1 变成空 —— 真机上竖屏只剩分隔竖线时，这两次日志的差别就是答案。
     */
    private void scheduleFocusPromptTextDump(String key) {
        mainHandler.postDelayed(() -> dumpFocusPromptTexts(key, "t0"), 300L);
        mainHandler.postDelayed(() -> dumpFocusPromptTexts(key, "t1"), 1_300L);
    }

    private void dumpFocusPromptTexts(String key, String mark) {
        try {
            List<View> prompts = new ArrayList<>(os3PromptViews);
            if (prompts.isEmpty()) {
                log("focus prompt text dump " + mark + " key=" + key + " prompt=none");
                return;
            }
            StringBuilder text = new StringBuilder();
            int[] budget = {4};
            for (View prompt : prompts) {
                // 文本有值不等于宿主可见；只在 Debug 的有限次现场采样记录父层状态。
                if (BuildConfig.DEBUG && prompt != null) {
                    Rect visible = new Rect();
                    boolean globalVisible = prompt.getGlobalVisibleRect(visible);
                    ViewParent parent = prompt.getParent();
                    debug("OS3 prompt host mark=" + mark + " key=" + key
                            + " attached=" + prompt.isAttachedToWindow()
                            + " shown=" + prompt.isShown() + " vis=" + prompt.getVisibility()
                            + " alpha=" + prompt.getAlpha() + " width=" + prompt.getWidth()
                            + " height=" + prompt.getHeight() + " globalVisible=" + globalVisible
                            + " rect=" + visible.toShortString()
                            + " parentAlpha=" + (parent instanceof View ? ((View) parent).getAlpha() : -1f)
                            + " parentVis=" + (parent instanceof View ? ((View) parent).getVisibility() : -1));
                }
                collectFocusTexts(prompt, text, budget);
            }
            log("focus prompt text dump " + mark + " key=" + key
                    + (text.length() == 0 ? " textViews=none" : text.toString()));
        } catch (Throwable t) {
            error("focus prompt text dump", t);
        }
    }

    private void collectFocusTexts(View node, StringBuilder out, int[] budget) {
        if (node == null || budget[0] <= 0) return;
        if (node instanceof TextView) {
            out.append('[').append(describeFocusTextGeometry((TextView) node));
            ViewParent parent = node.getParent();
            if (parent instanceof View) {
                View container = (View) parent;
                out.append(" parentWidth=").append(container.getWidth())
                        .append(" parentScrollX=").append(container.getScrollX());
            }
            out.append(']');
            budget[0]--;
        }
        if (!(node instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) node;
        for (int index = 0; index < group.getChildCount() && budget[0] > 0; index++) {
            collectFocusTexts(group.getChildAt(index), out, budget);
        }
    }

    /**
     * 焦点文本视图的现场状态，用于定位"竖屏只看得到分隔竖线"这一类不可见问题。
     *
     * <p>文字装得下、又没启动跑马灯却仍然看不见时，只可能是这三类原因之一：横向滚动没归零、
     * 颜色/透明度不可见、或者视图位置落在可见区之外。一次日志把三者一起打出来，避免继续靠猜。
     * 0.25.6 起再加 {@code text} / {@code gravity} / {@code layoutAlign}，用来区分"文字没落到视图上"
     * 与"文字在视图上但排布不可见"。
     */
    private String describeFocusTextGeometry(TextView textView) {
        try {
            int[] location = new int[2];
            textView.getLocationOnScreen(location);
            Rect visible = new Rect();
            boolean visibleRectOk = textView.getGlobalVisibleRect(visible);
            StringBuilder text = new StringBuilder()
                    .append(" text=").append(preview(textView.getText() == null
                            ? null : textView.getText().toString()))
                    .append(" scrollX=").append(textView.getScrollX())
                    .append(" alpha=").append(textView.getAlpha())
                    .append(" color=#").append(Integer.toHexString(textView.getCurrentTextColor()))
                    .append(" vis=").append(textView.getVisibility())
                    .append(" gravity=").append(textView.getGravity())
                    .append(" layoutAlign=").append(textView.getLayout() == null
                            ? "none" : textView.getLayout().getAlignment())
                    .append(" left=").append(textView.getLeft())
                    .append(" screenX=").append(location[0])
                    .append(" visibleRect=").append(visibleRectOk ? visible.toShortString() : "none");
            ViewParent parent = textView.getParent();
            if (parent instanceof View) {
                View container = (View) parent;
                text.append(" parentWidth=").append(container.getWidth())
                        .append(" parentScrollX=").append(container.getScrollX())
                        .append(" parentLeft=").append(container.getLeft());
            }
            return text.toString();
        } catch (Throwable t) {
            return " geometry=error:" + t;
        }
    }

    private boolean hasMarqueeOverflow(TextView textView) {
        float textWidth = textView.getPaint().measureText(textView.getText().toString());
        float layoutWidth = textView.getLayout() == null
                ? 0f : textView.getLayout().getLineWidth(0);
        float availableWidth = getVisibleContentWidth(textView);
        return availableWidth > 0f && Math.max(textWidth, layoutWidth) > availableWidth;
    }

    private float getVisibleContentWidth(TextView textView) {
        float localWidth = textView.getWidth()
                - textView.getCompoundPaddingLeft() - textView.getCompoundPaddingRight();
        int[] location = new int[2];
        textView.getLocationOnScreen(location);
        int visibleLeft = location[0];
        int visibleRight = visibleLeft + textView.getWidth();

        Rect visibleRect = new Rect();
        if (textView.getGlobalVisibleRect(visibleRect) && visibleRect.width() > 0) {
            visibleLeft = Math.max(visibleLeft, visibleRect.left);
            visibleRight = Math.min(visibleRight, visibleRect.right);
        }

        ViewParent ancestor = textView.getParent();
        while (ancestor instanceof View && visibleRight > visibleLeft) {
            View parent = (View) ancestor;
            parent.getLocationOnScreen(location);
            visibleLeft = Math.max(visibleLeft, location[0]);
            visibleRight = Math.min(visibleRight, location[0] + parent.getWidth());
            ancestor = parent.getParent();
        }

        float visibleWidth = visibleRight - visibleLeft
                - textView.getCompoundPaddingLeft() - textView.getCompoundPaddingRight();
        return Math.max(0f, Math.min(localWidth, visibleWidth));
    }

    private void stopNativeMarquee(TextView textView) {
        try {
            Object marquee = getField(textView, "mMarquee");
            if (marquee != null) XposedHelpers.callMethod(marquee, "stop");
        } catch (Throwable t) {
            error("stopNativeMarquee", t);
        }
    }

    private synchronized void registerActiveMarqueeOwner(final TextView textView) {
        if (activeMarqueeText == textView && activeMarqueeDetachListener != null) return;
        clearActiveMarqueeOwnerLocked();
        activeMarqueeText = textView;
        activeMarqueeDetachListener = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
            }

            @Override public void onViewDetachedFromWindow(View view) {
                synchronized (HyperOS3FocusRestoreHook.this) {
                    if (activeMarqueeText == textView) clearActiveMarqueeOwnerLocked();
                }
            }
        };
        textView.addOnAttachStateChangeListener(activeMarqueeDetachListener);
    }

    private void clearActiveMarqueeOwnerLocked() {
        TextView textView = activeMarqueeText;
        if (textView != null && activeMarqueeDetachListener != null) {
            textView.removeOnAttachStateChangeListener(activeMarqueeDetachListener);
        }
        activeMarqueeText = null;
        activeMarqueeDetachListener = null;
        if (fallbackMarqueeAnimator != null) {
            fallbackMarqueeAnimator.cancel();
            fallbackMarqueeAnimator = null;
        }
        if (textView != null) {
            stopNativeMarquee(textView);
            textView.scrollTo(0, 0);
        }
    }

    private void startFallbackMarquee(TextView textView) {
        try {
            float textWidth = textView.getPaint().measureText(textView.getText().toString());
            float layoutWidth = textView.getLayout() == null
                    ? 0f : textView.getLayout().getLineWidth(0);
            textWidth = Math.max(textWidth, layoutWidth);
            float availableWidth = getVisibleContentWidth(textView);
            final int distance = Math.round(textWidth - availableWidth);
            if (distance <= 0) return;
            if (fallbackMarqueeAnimator != null) fallbackMarqueeAnimator.cancel();
            fallbackMarqueeAnimator = currentSettings.marqueeBounce
                    ? ValueAnimator.ofInt(0, distance, 0)
                    : ValueAnimator.ofInt(0, distance);
            fallbackMarqueeAnimator.setDuration(Math.max(2500L, distance * 35L));
            fallbackMarqueeAnimator.setInterpolator(new LinearInterpolator());
            fallbackMarqueeAnimator.setRepeatCount(ValueAnimator.INFINITE);
            fallbackMarqueeAnimator.addUpdateListener(animation -> {
                if (textView.getVisibility() == View.VISIBLE) {
                    textView.scrollTo((Integer) animation.getAnimatedValue(), 0);
                }
            });
            fallbackMarqueeAnimator.start();
            log("started fallback focus marquee textWidth=" + textWidth
                    + " availableWidth=" + availableWidth + " distance=" + distance);
        } catch (Throwable t) {
            error("startFallbackMarquee", t);
        }
    }

    private void hookFocusedTextMarquee() {
        try {
            Class<?> textClass = FocusReflection.findClass(
                    "com.android.systemui.statusbar.widget.FocusedTextView",
                    classLoader);
            XposedBridge.hookAllMethods(textClass, "startMarqueeLocal", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // The OEM method copies its private marqueeLimit into
                    // TextView immediately before starting the animator.
                    XposedHelpers.setIntField(param.thisObject,
                            "marqueeLimit", MARQUEE_REPEAT_LIMIT);
                }
            });
            log("hooked FocusedTextView.startMarqueeLocal repeatLimit="
                    + MARQUEE_REPEAT_LIMIT);
        } catch (Throwable t) {
            error("hookFocusedTextMarquee", t);
        }
    }

    private synchronized void scheduleNativeMarquee(Object promptView) {
        clearPendingMarqueeLocked();
        final long generation = ++marqueeGeneration;
        clearActiveMarqueeOwnerLocked();
        try {
            Object value = XposedHelpers.getObjectField(promptView, "mContentText");
            if (!(value instanceof TextView)) return;
            final TextView textView = (TextView) value;
            textView.scrollTo(0, 0);
            pendingMarqueeText = textView;
            pendingMarqueeRunnable = new Runnable() {
                private int attempts;

                @Override public void run() {
                    synchronized (HyperOS3FocusRestoreHook.this) {
                        if (generation != marqueeGeneration || pendingMarqueeText != textView) return;
                    }
                    if (Build.VERSION.SDK_INT >= 19 && !textView.isAttachedToWindow()) {
                        waitForMarqueeAttach(textView, generation, this);
                        return;
                    }
                    attempts++;
                    if (textView.getText() == null || textView.getText().length() == 0) {
                        if (attempts < 3) {
                            textView.postDelayed(this, 100L);
                        } else {
                            finishPendingMarquee(textView, generation);
                        }
                        return;
                    }
                    boolean ready = startNativeMarquee(textView, attempts);
                    // Wait briefly when RemoteViews has supplied text but the
                    // final one-line layout or Marquee instance is not ready yet.
                    if (!ready && attempts < 3) {
                        textView.postDelayed(this, 100L);
                        return;
                    }
                    // Compatibility mode adds one retry for ROMs that reset
                    // marquee state immediately after the first native start.
                    if (currentSettings.compatRetry && attempts < 2) {
                        textView.postDelayed(this, 150L);
                        return;
                    }
                    finishPendingMarquee(textView, generation);
                }
            };
            textView.postDelayed(pendingMarqueeRunnable,
                    Math.max(0, Math.min(5000, currentSettings.marqueeDelayMs)));
            log("scheduled native focus marquee delayMs=" + currentSettings.marqueeDelayMs);
        } catch (Throwable t) {
            error("scheduleNativeMarquee", t);
        }
    }

    private synchronized void cancelCurrentMarquee() {
        clearPendingMarqueeLocked();
        marqueeGeneration++;
        clearActiveMarqueeOwnerLocked();
    }

    private synchronized void waitForMarqueeAttach(final TextView textView,
                                                    final long generation,
                                                    final Runnable startRunnable) {
        if (generation != marqueeGeneration || pendingMarqueeText != textView
                || pendingMarqueeAttachListener != null) return;
        pendingMarqueeAttachListener = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                synchronized (HyperOS3FocusRestoreHook.this) {
                    if (generation != marqueeGeneration || pendingMarqueeText != textView) return;
                    clearMarqueeAttachWaitLocked();
                }
                mainHandler.post(startRunnable);
            }

            @Override public void onViewDetachedFromWindow(View view) {
            }
        };
        pendingMarqueeAttachTimeout = () -> {
            synchronized (HyperOS3FocusRestoreHook.this) {
                if (generation != marqueeGeneration || pendingMarqueeText != textView) return;
                clearMarqueeAttachWaitLocked();
                pendingMarqueeText = null;
                pendingMarqueeRunnable = null;
            }
            log("focus marquee attach wait timed out");
        };
        textView.addOnAttachStateChangeListener(pendingMarqueeAttachListener);
        mainHandler.postDelayed(pendingMarqueeAttachTimeout, MARQUEE_ATTACH_TIMEOUT_MS);
        log("waiting for focus text attach before marquee");
    }

    private synchronized void finishPendingMarquee(TextView textView, long generation) {
        if (generation != marqueeGeneration || pendingMarqueeText != textView) return;
        clearMarqueeAttachWaitLocked();
        pendingMarqueeText = null;
        pendingMarqueeRunnable = null;
    }

    private void clearPendingMarqueeLocked() {
        if (pendingMarqueeText != null && pendingMarqueeRunnable != null) {
            pendingMarqueeText.removeCallbacks(pendingMarqueeRunnable);
        }
        clearMarqueeAttachWaitLocked();
        pendingMarqueeText = null;
        pendingMarqueeRunnable = null;
    }

    private void clearMarqueeAttachWaitLocked() {
        if (pendingMarqueeText != null && pendingMarqueeAttachListener != null) {
            pendingMarqueeText.removeOnAttachStateChangeListener(pendingMarqueeAttachListener);
        }
        if (pendingMarqueeAttachTimeout != null) {
            mainHandler.removeCallbacks(pendingMarqueeAttachTimeout);
        }
        pendingMarqueeAttachListener = null;
        pendingMarqueeAttachTimeout = null;
    }

    private void hookFocusedParentParams() {
        try {
            Class<?> fragment = FocusReflection.findClass(
                    "com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment",
                    classLoader);
            XposedHelpers.findAndHookMethod(fragment, "updateFocusedParentParams", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyParentWidth(param.thisObject);
                        }
                    });
            log("hooked updateFocusedParentParams for 0.4 width behavior");
        } catch (Throwable t) {
            error("hookFocusedParentParams", t);
        }
    }

    private void applyParentWidth(Object fragment) {
        try {
            reloadSettings(false);
            Object value = XposedHelpers.getObjectField(fragment, "mFocusedNotifParent");
            if (!(value instanceof View)) return;
            View parent = (View) value;
            ViewGroup.LayoutParams params = parent.getLayoutParams();
            if (params == null) return;
            if (currentSettings.limitWidth) {
                int configuredWidthDp = focusWidthDp(parent.getResources().getConfiguration());
                int widthPx = Math.round(configuredWidthDp
                        * parent.getResources().getDisplayMetrics().density);
                synchronized (originalParentWidths) {
                    ParentWidthState original = originalParentWidths.get(parent);
                    if (original == null) {
                        originalParentWidths.put(parent,
                                new ParentWidthState(params.width, widthPx));
                    } else {
                        if (params.width != original.lastAppliedWidth) {
                            original.originalWidth = params.width;
                        }
                        original.lastAppliedWidth = widthPx;
                    }
                }
                if (params.width != widthPx) {
                    params.width = widthPx;
                    parent.setLayoutParams(params);
                    log("applied 0.4 manual focus parent width=" + configuredWidthDp
                            + "dp px=" + widthPx);
                }
            } else {
                ParentWidthState original;
                synchronized (originalParentWidths) {
                    original = originalParentWidths.remove(parent);
                }
                if (original != null && params.width != original.originalWidth) {
                    params.width = original.originalWidth;
                    parent.setLayoutParams(params);
                    log("restored system focus parent width=" + original.originalWidth);
                }
            }
        } catch (Throwable t) {
            error("applyParentWidth", t);
        }
    }

    private boolean hasHigherPriorityFocus(Object promptController, String mediaKey) {
        try {
            FocusData current = inspectBean(getField(promptController, "mCurrentNotifBean"));
            if (current == null || TextUtils.equals(mediaKey, current.key)) return false;
            if (current.isFocus && current.mediaContent == null) {
                log("media Focus yielded to currently displayed ordinary Focus key=" + current.key);
                return true;
            }
        } catch (Throwable throwable) {
            error("hasHigherPriorityFocus", throwable);
        }
        return false;
    }
    private void hookPromptShouldShow() {
        try {
            Class<?> controller = FocusReflection.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptController",
                    classLoader);
            Class<?> bean = FocusReflection.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptController$FocusedNotifBean",
                    classLoader);

            XposedHelpers.findAndHookMethod(controller, "shouldShow", bean, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            os3PromptControllerRef = new WeakReference<>(param.thisObject);
                            if (currentSettings.islandCompat) patchBean(param.args[0], "before shouldShow");
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object value = param.args[0];
                            FocusData data = inspectBean(value);
                            boolean result = Boolean.TRUE.equals(param.getResult());
                            log("shouldShow=" + result + (data == null ? " bean=null" : " " + data.summary()));
                            if (data == null) return;

                            boolean mediaCandidate = currentSettings.mediaFocusEnabled
                                    && data.mediaContent != null
                                    && activeMediaNotificationKeys.contains(data.key);
                            boolean mediaExpired = mediaCandidate && isFocusDisplayExpired(data);
                            boolean higherPriority = mediaCandidate
                                    && hasHigherPriorityFocus(param.thisObject, data.key);
                            if (mediaCandidate) {
                                if (mediaExpired || higherPriority) {
                                    log("media shouldShow blocked key=" + data.key
                                            + " expired=" + mediaExpired
                                            + " higherPriority=" + higherPriority);
                                } else {
                                    param.setResult(true);
                                    log("media shouldShow allowed key=" + data.key
                                            + " original=" + result);
                                    return;
                                }
                            }
                            if (result) return;

                            // 模块自己构造的设备通知焦点通知：ROM 的 shouldShow 只对携带 miui.focus.rv
                            // 的通知返回 true，因此它会在这里被清掉（0.25.2 真机：15 个事件里 12 个卡在
                            // 这一关，只有个别事件没走这次询问才显示出来）。构造的通知本来就是模块要
                            // 显示的，直接放行。
                            if (isConstructedDeviceFocusNotification(data)) {
                                param.setResult(true);
                                // ROM 的 update() 先按自己当时的 mShouldShow（false）算出"提示无需
                                // 变化"，于是走早退分支，压根不会把这次得到的 bean 交给提示视图；
                                // 同一轮还有一次 setData(null) 把文字视图清空。两者叠加就是 0.25.6
                                // 真机上"竖屏只剩分隔竖线"：文字从未落到视图上，t0/t1 两次 dump 都为空。
                                // 因此放行的同时把这次询问携带的 bean 直接给提示视图。
                                // 注意必须传 param.args[0]（bean 实例）：外层作用域的 bean 是
                                // findClass 拿到的 Class 对象，传它会得到
                                // IllegalArgumentException: … argument 1 has type FocusedNotifBean,
                                // got java.lang.Class<FocusedNotifBean>。
                                applyConstructedDevicePrompt(value, data);
                                log("device focus shouldShow forced=true key=" + data.key);
                                return;
                            }

                            // HyperOS answers shouldShow=true only for a notification carrying its own
                            // miui.focus.rv. A whitelisted island notification is otherwise parsed and
                            // written into the bean and then never displayed, which is exactly the
                            // "nothing shows up" report. The prompt is therefore forced visible once
                            // per key. Scoped to the whitelist because that is the user's explicit
                            // statement that this package's island content should appear as focus.
                            // 媒体通知已在前一阶段写入 Focus 标记，但 ROM 的默认判断仍只认原生
                            // focus RemoteViews；这里必须单独放行，否则刚转换就会被 shouldShow 清掉。
                            boolean whitelisted = currentSettings.islandCompat
                                    && currentSettings.islandForcePackages.contains(data.packageName);
                            // Bound to the notification generation, not just the key: re-posting the
                            // same id keeps the key but is a new post time, and that must be shown
                            // again instead of being suppressed by the earlier override.
                            if (mediaCandidate && !mediaExpired && !higherPriority) {
                                // 媒体会重复刷新同一代通知，不能套用白名单的一次性放行规则。
                                param.setResult(true);
                                return;
                            }
                            if (whitelisted && hasConvertibleIslandContent(data)
                                    && markForcedShouldShow(data.key, data.sbn)) {
                                param.setResult(true);
                                log("shouldShow forced=true key=" + data.key
                                        + " package=" + data.packageName);
                            } else if (firstDiagnosis("shouldShowNotForced|" + data.key)) {
                                debug("shouldShow not forced key=" + data.key
                                        + " package=" + data.packageName
                                        + " whitelisted=" + whitelisted
                                        + " islandParam=" + data.hasIslandParam);
                            }
                        }
                    });
        } catch (Throwable t) {
            error("hookPromptShouldShow", t);
        }
    }

    private void hookDisableConvertedFocusClick() {
        try {
            Class<?> promptView = FocusReflection.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptView", classLoader);
            XposedHelpers.findAndHookMethod(promptView, "onFocusNotifPromptClicked",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object bean = getField(param.thisObject, "mData");
                            FocusData data = inspectBean(bean);
                            if (isMediaFocus(data)) {
                                // 媒体焦点始终可点击：直接展开流转界面，否则展开媒体横幅。
                                // 这里不再要求横幅总开关，否则媒体横幅选项会被静默失效。
                                param.setResult(null);
                                if (currentSettings.mediaFocusCastDirect) {
                                    openMediaCast(data.key);
                                } else if (param.thisObject instanceof View) {
                                    showIndependentFocusBanner((View) param.thisObject,
                                            data, data.key, "OS3");
                                }
                                return;
                            }
                            if (currentSettings.independentFocusBanner) {
                                // Consume this explicit banner action even if the window fails;
                                // a failed expansion must not unexpectedly open the app.
                                param.setResult(null);
                                if (param.thisObject instanceof View && data != null) {
                                    if (data.notification != null) {
                                        showIndependentFocusBanner((View) param.thisObject,
                                                data, data.key, "OS3");
                                    } else {
                                        showIndependentFocusBanner((View) param.thisObject,
                                                (Object) null, data.key, "OS3");
                                    }
                                } else {
                                    log("OS3 independent banner rejected reason=focusData");
                                }
                                return;
                            }
                            if (currentSettings.allowFocusClick) {
                                if (currentSettings.notificationRowClickFallback
                                        && data != null
                                        && performNotificationRowClick(null, data.key, "OS3")) {
                                    param.setResult(null);
                                }
                                return;
                            }
                            boolean converted = convertedBeans.contains(bean)
                                    || isConvertedNotificationKey(data == null ? null : data.key);
                            boolean focus = data != null && (data.isFocus || data.hasExplicitFocusData);
                            if (focus || converted) {
                                param.setResult(null);
                                log("ignored focus click key=" + (data == null ? null : data.key)
                                        + " converted=" + converted);
                            }
                        }
                    });
            log("disabled converted focus click");
        } catch (Throwable t) {
            error("hookDisableConvertedFocusClick", t);
        }
    }

    /**
     * Records that this notification generation has had its prompt forced visible, returning false on
     * a repeat call. The set is bounded because notification keys are unbounded in principle and this
     * hook runs for every prompt evaluation.
     */
    /**
     * 是否是模块自己构造的设备通知焦点通知。
     *
     * <p>靠模块写进 extras 的标记识别（{@link DeviceNotificationFocusPoster#EXTRA_MODULE_MARKER}），
     * 不会与系统或第三方通知冲突。
     */
    private static boolean isConstructedDeviceFocusNotification(FocusData data) {
        if (data == null || data.sbn == null) return false;
        Notification notification = data.sbn.getNotification();
        Bundle extras = notification == null ? null : notification.extras;
        return extras != null
                && extras.getBoolean(DeviceNotificationFocusPoster.EXTRA_MODULE_MARKER, false);
    }

    /**
     * 把设备通知焦点通知的提示内容写进提示视图，绕开 ROM 那次不会生效的交接。
     *
     * <p>0.25.6 真机（`log/focus-restore.0.25.6.log`）里，静音 / 勿扰关闭 / 充电事件的提示文字视图
     * 在 t0、t1 两次现场 dump 中都是空的，而勿扰开启那次有文字，区别就在提示视图有没有拿到 bean：
     * ROM 的 {@code FocusedNotifPromptController.update(int)} 先按它自己当时的 {@code mShouldShow}
     * 算出"提示不用变化"并走早退分支，于是这次询问携带的 bean 永远不会经由
     * {@code notifyNotifBeanChanged} 落到视图上；同一轮里的 {@code setData(null)} 又把
     * {@code FocusedTextView} 清空，界面上只剩模块按自身状态画的分隔竖线。模块的放行发生在这次
     * 计算之后，补不回那次交接，所以在这里自己写一次。
     *
     * <p>写入分两层，缺一不可：
     * <ol>
     *   <li>把 bean 交给视图（{@code setData}）：视图自己记录的 {@code mData} 随之正确，之后的
     *       布局／点击路径读到的都是这条设备通知；</li>
     *   <li>直接把文字写进 {@code mContentText}：{@code setData} 内部是否真的把文字落到视图上，
     *       取决于它自己的 {@code shouldUpdate} 与动画时间窗（`mLastAnimationTime` 的 5036ms 判定）；
     *       0.25.7 首版只做第 1 层时，真机上写入落在提示动画之后，文字要到 0.3s 之后才出现，
     *       那一瞬间仍然只看得见分隔竖线。直接写一次就没有这个窗口。</li>
     * </ol>
     *
     * <p>只处理模块构造的、自身不带 RemoteViews 的提示文字；ROM 的原生 RemoteViews 渲染优先，
     * 不参与这条通路。
     */
    private void applyConstructedDevicePrompt(Object beanInstance, FocusData data) {
        if (beanInstance == null) return;
        // 文字取自已解析好的 FocusData，而不是在这里重读 bean 字段：0.26.1 真机证明，
        // 这里一旦传错对象（当时把给 findAndHookMethod 用的 Class 当 bean 传了进来），
        // 重读字段会静默拿到 null，而 FocusData 是 shouldShow 那一步已经解析好的结果。
        String text = data == null ? null : data.content;
        Object contentRv = getField(beanInstance, "contentRemoteViews");
        if (!DeviceFocusPromptPolicy.shouldApplyToPromptView(
                contentRv != null, !TextUtils.isEmpty(text), currentSettings.islandCompat)) {
            return;
        }
        Method setData = findPromptSetDataMethod();
        if (setData == null) {
            error("applyConstructedDevicePrompt setData=missing", null);
            return;
        }
        for (View promptView : promptViewsSnapshot()) {
            try {
                // 直接反射调用，不用 XposedHelpers.callMethod：后者会把 boolean 装箱成 Boolean，
                // 于是去找 setData(FocusedNotifBean, Boolean) 而永远找不到真正的方法
                //（0.26.0 真机：NoSuchMethodError ...#setData[class java.lang.Class, class java.lang.Boolean]）。
                setData.invoke(promptView, beanInstance, false);
                writePromptText(promptView, text);
                showPromptContentIfHidden(promptView);
            } catch (Throwable t) {
                error("applyConstructedDevicePrompt setData", t);
            }
        }
    }

    /**
     * 保证提示文字与内容容器都可见。只改这两处，提示整体的显示／动画仍由 ROM 决定，避免和它的
     * 动画状态机打架；容器沿用 ROM 自己的 {@code showImmediately}。
     */
    private void writePromptText(View promptView, String text) {
        Object content = getField(promptView, "mContentText");
        if (!(content instanceof TextView)) return;
        TextView textView = (TextView) content;
        if (!TextUtils.equals(textView.getText(), text)) textView.setText(text);
        if (textView.getVisibility() != View.VISIBLE) textView.setVisibility(View.VISIBLE);
    }

    /**
     * 提示视图的 {@code setData(FocusedNotifBean, boolean)}。
     *
     * <p>按名字 + 形参个数在类层次里查找，不用 {@code XposedHelpers.callMethod}：后者拿装箱后的
     * {@code Boolean} 去匹配形参类型，找不到 {@code boolean} 版本，会抛
     * {@code NoSuchMethodError: …#setData[class java.lang.Class, class java.lang.Boolean]}
     * （0.26.0 真机日志）。按个数取到的唯一匹配就是它，再反射调用即可正常传基本类型。
     */
    private Method findPromptSetDataMethod() {
        try {
            Class<?> owner = FocusReflection.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptView", classLoader);
            for (Class<?> type = owner; type != null && type != Object.class;
                 type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if ("setData".equals(method.getName())
                            && method.getParameterTypes().length == 2) {
                        method.setAccessible(true);
                        return method;
                    }
                }
            }
        } catch (Throwable t) {
            error("findPromptSetDataMethod", t);
        }
        return null;
    }

    /** 提示视图实例的快照；注册表由 {@code setData} 的 after-hook 维护，读取时先复制避免并发修改。 */
    private List<View> promptViewsSnapshot() {
        synchronized (os3PromptViews) {
            return new ArrayList<>(os3PromptViews);
        }
    }

    /**
     * 兜住 ROM 早退时连可见性也没动的情况：提示整体可见、但内容容器仍停在 hideImmediately 留下的
     * 不可见状态时，用 ROM 自己的显示方法补一次，避免"文字有了却仍然看不见"。
     */
    private void showPromptContentIfHidden(View promptView) {
        Object content = getField(promptView, "mContent");
        if (!(content instanceof View)) return;
        View contentView = (View) content;
        if (contentView.getVisibility() == View.VISIBLE) return;
        Class<?> controller = FocusReflection.findClass(
                "com.android.systemui.statusbar.phone.FocusedNotifPromptController", classLoader);
        if (controller == null) return;
        try {
            XposedHelpers.callStaticMethod(controller, "showImmediately", contentView);
        } catch (Throwable t) {
            error("applyConstructedDevicePrompt showImmediately", t);
        }
    }

    private boolean markForcedShouldShow(String key, StatusBarNotification sbn) {
        if (TextUtils.isEmpty(key)) return false;
        String generation = key + "@" + (sbn == null ? 0L : sbn.getPostTime());
        synchronized (forcedShouldShowKeys) {
            if (!forcedShouldShowKeys.add(generation)) return false;
            Iterator<String> oldest = forcedShouldShowKeys.iterator();
            while (forcedShouldShowKeys.size() > MAX_FORCED_SHOULD_SHOW_KEYS && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
            return true;
        }
    }

    private synchronized void rememberConvertedNotificationKey(String key) {
        if (TextUtils.isEmpty(key)) return;
        long now = SystemClock.elapsedRealtime();
        Iterator<Map.Entry<String, Long>> iterator = convertedNotificationKeys.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() >= CONVERTED_KEY_TTL_MS) iterator.remove();
        }
        convertedNotificationKeys.put(key, now);
        while (convertedNotificationKeys.size() > MAX_CONVERTED_KEYS) {
            iterator = convertedNotificationKeys.entrySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

    private synchronized boolean isConvertedNotificationKey(String key) {
        if (TextUtils.isEmpty(key)) return false;
        long now = SystemClock.elapsedRealtime();
        Iterator<Map.Entry<String, Long>> iterator = convertedNotificationKeys.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() >= CONVERTED_KEY_TTL_MS) iterator.remove();
        }
        Long seenAt = convertedNotificationKeys.get(key);
        return seenAt != null && now - seenAt < CONVERTED_KEY_TTL_MS;
    }

    private void hookRemoteViewsErrors() {
        try {
            Class<?> view = FocusReflection.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptView",
                    classLoader);
            XposedBridge.hookAllMethods(view, "updateRemoteViews", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    restoreRemoteViewsPrompt(param.thisObject);
                    Object bean = getField(param.thisObject, "mData");
                    FocusData data = inspectBean(bean);
                    log("updateRemoteViews begin " + (data == null ? "bean=null" : data.summary()));
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) {
                        Throwable failure = param.getThrowable();
                        error("updateRemoteViews throwable", failure);
                        Object bean = getField(param.thisObject, "mData");
                        FocusData data = inspectBean(bean);
                        // The module's own parse is the better fallback: the notification's content
                        // text is frequently empty on a focus notification whose wording lives in the
                        // RemoteViews that just failed, and the app's own layout has already been
                        // hidden by this point, so an empty fallback leaves an empty pill.
                        String fallback = parsedIslandText(data);
                        if (TextUtils.isEmpty(fallback) && data != null) fallback = data.content;
                        if (TextUtils.isEmpty(fallback) && data != null) fallback = data.ticker;
                        RemoteViewsFailurePolicy.Action action = RemoteViewsFailurePolicy.decide(
                                failure, !TextUtils.isEmpty(fallback));
                        if (action == RemoteViewsFailurePolicy.Action.RETHROW) return;
                        param.setResult(null);
                        Object content = getField(param.thisObject, "mContentText");
                        RemoteViewsFailurePolicy.Action appliedAction = action;
                        if (action == RemoteViewsFailurePolicy.Action.TEXT_FALLBACK
                                && content instanceof TextView) {
                            TextView textView = (TextView) content;
                            restoreRemoteViewsPrompt(param.thisObject);
                            hideKnownRemoteViewsContainers(param.thisObject, textView,
                                    data == null ? null : data.key);
                            textView.setText(fallback);
                            textView.setVisibility(View.VISIBLE);
                            scheduleNativeMarquee(param.thisObject);
                        } else {
                            appliedAction = RemoteViewsFailurePolicy.Action.DROP_CURRENT;
                            if (content instanceof TextView) ((TextView) content).setText(null);
                            hideRemoteViewsPrompt(param.thisObject);
                            cancelCurrentMarquee();
                        }
                        log("updateRemoteViews recovered action=" + appliedAction
                                + " key=" + (data == null ? null : data.key));
                    } else {
                        restoreRemoteViewsPrompt(param.thisObject);
                        FocusData data = inspectBean(getField(param.thisObject, "mData"));
                        // The pill hosts the notification's own RemoteViews next to a text view. For a
                        // converted island notification those RemoteViews show the application's own
                        // wording and cover the text this module produced, so the parsed content was
                        // never visible on the status bar. Prefer our text when we have it.
                        if (!preferConvertedIslandText(param.thisObject, data)) {
                            scheduleNativeMarquee(param.thisObject);
                        }
                        logPillTextState(param.thisObject, data);
                        log("updateRemoteViews end");
                    }
                }
            });
        } catch (Throwable t) {
            error("hookRemoteViewsErrors", t);
        }
    }

    /**
     * Shows the text this module parsed out of the island payload instead of the notification's own
     * RemoteViews. Only the known RemoteViews container fields are hidden, so unrelated parts of the
     * pill keep the ROM's behaviour, and when no converted text exists this does nothing at all —
     * the ROM then renders its RemoteViews exactly as before.
     */
    private boolean preferMediaText(Object promptObject, FocusData data) {
        if (!(promptObject instanceof View) || data == null || data.mediaContent == null
                || !currentSettings.mediaFocusEnabled) return false;
        Object content = getField(promptObject, "mContentText");
        if (!(content instanceof TextView)) return false;
        TextView text = (TextView) content;
        String value = data.mediaContent.text;
        if (!TextUtils.equals(text.getText(), value)) text.setText(value);
        text.setVisibility(View.VISIBLE);
        if (firstDiagnosis("mediaText|" + data.key)) {
            debug("media text rendered key=" + data.key + " playing="
                    + data.mediaContent.playing);
        }
        return true;
    }

    private boolean preferConvertedIslandText(Object promptObject, FocusData data) {
        if (!(promptObject instanceof View) || data == null) return false;
        if (!currentSettings.islandCompat || !data.hasIslandParam) return false;
        Object content = getField(promptObject, "mContentText");
        if (!(content instanceof TextView)) return false;
        TextView textView = (TextView) content;

        String applied;
        synchronized (appliedIslandTexts) {
            applied = appliedIslandTexts.get(promptObject);
        }
        String text;
        String source = null;
        if (applied != null && TextUtils.equals(data.content, applied)) {
            // Same payload as the previous pass: reuse the conversion instead of re-parsing the JSON.
            text = applied;
        } else {
            IslandText parsed = shouldConvert(data) ? extractIslandContent(data) : null;
            if (parsed == null || TextUtils.isEmpty(parsed.text)) return false;
            text = parsed.text;
            source = parsed.source;
        }

        // updateRemoteViews runs for every layout pass and its before-hook restores the
        // notification's own RemoteViews each time, so the containers must be hidden again here. The
        // text and marquee only need attention when the converted text actually changed, otherwise a
        // marquee-driven layout pass would rewrite the view tree and log on every frame.
        hideKnownRemoteViewsContainers(promptObject, textView, data.key);
        boolean changed = !TextUtils.equals(applied, text);
        if (!TextUtils.equals(textView.getText(), text)) textView.setText(text);
        textView.setVisibility(View.VISIBLE);
        if (changed) {
            synchronized (appliedIslandTexts) {
                appliedIslandTexts.put(promptObject, text);
            }
            scheduleNativeMarquee(promptObject);
            log("updateRemoteViews prefers converted island text key=" + data.key
                    + " source=" + source + " text=" + preview(text));
        }
        return true;
    }

    /** The island text this module parsed for the notification, or null when there is none to use. */
    private String parsedIslandText(FocusData data) {
        if (data == null || !shouldConvert(data)) return null;
        IslandText parsed = extractIslandContent(data);
        return parsed == null || TextUtils.isEmpty(parsed.text) ? null : parsed.text;
    }

    /**
     * One line per notification reporting what the focus pill displays after this module's write.
     * Hiding the application's RemoteViews without knowing whether our own text landed would leave an
     * empty pill with no way to tell from the log which half happened, so the state is read back and
     * reported directly. Bounded to the first pass per key: {@code updateRemoteViews} runs for every
     * layout pass, and the value only changes when the text or the view does.
     */
    private void logPillTextState(Object promptObject, FocusData data) {
        if (data == null || !firstDiagnosis("pillText|" + data.key)) return;
        Object content = getField(promptObject, "mContentText");
        boolean isTextView = content instanceof TextView;
        CharSequence shown = isTextView ? ((TextView) content).getText() : null;
        debug("pill text state key=" + data.key
                + " writeTarget=" + (isTextView ? "mContentText" : content == null ? "absent" : "not-a-textview")
                + " text=" + preview(shown == null ? null : shown.toString())
                + " textVisibility=" + visibilityName(content)
                + " promptVisibility=" + visibilityName(promptObject)
                + " attached=" + (promptObject instanceof View
                        && ((View) promptObject).isAttachedToWindow()));
    }

    private static String visibilityName(Object view) {
        if (!(view instanceof View)) return "-";
        switch (((View) view).getVisibility()) {
            case View.VISIBLE: return "VISIBLE";
            case View.INVISIBLE: return "INVISIBLE";
            case View.GONE: return "GONE";
            default: return "unexpected";
        }
    }

    private void hideRemoteViewsPrompt(Object promptObject) {
        if (!(promptObject instanceof View)) return;
        View prompt = (View) promptObject;
        synchronized (remoteViewsHiddenPrompts) {
            if (!remoteViewsHiddenPrompts.containsKey(prompt)) {
                remoteViewsHiddenPrompts.put(prompt, prompt.getVisibility());
            }
        }
        prompt.setVisibility(View.GONE);
    }

    /**
     * Reverts this module's own mutations on a prompt view. Returns null when there was nothing to
     * revert (the normal case) or a short description of what was reverted. The description is what
     * makes a leak observable: the normal result is "nothing", so anything else means an earlier
     * notification had left this reused view altered, and the named key says which one.
     */
    private String restoreRemoteViewsPrompt(Object promptObject) {
        if (!(promptObject instanceof View)) return null;
        View prompt = (View) promptObject;
        Integer visibility;
        synchronized (remoteViewsHiddenPrompts) {
            visibility = remoteViewsHiddenPrompts.remove(prompt);
        }
        if (visibility != null) prompt.setVisibility(visibility);
        Map<View, Integer> containers;
        synchronized (remoteViewsHiddenContainers) {
            containers = remoteViewsHiddenContainers.remove(prompt);
        }
        String hiddenFor;
        synchronized (remoteViewsHiddenKeys) {
            hiddenFor = remoteViewsHiddenKeys.remove(prompt);
        }
        if (containers != null) {
            for (Map.Entry<View, Integer> entry : containers.entrySet()) {
                entry.getKey().setVisibility(entry.getValue());
            }
            return "containers=" + containers.size() + " hiddenFor=" + hiddenFor;
        }
        return visibility != null ? "prompt-visibility" : null;
    }

    private void hideKnownRemoteViewsContainers(Object promptObject, TextView contentText, String key) {
        if (!(promptObject instanceof View)) return;
        View prompt = (View) promptObject;
        Map<View, Integer> containers = new WeakHashMap<>();
        int hidden = 0;
        for (String fieldName : REMOTE_VIEWS_CONTAINER_FIELDS) {
            Object value = getField(promptObject, fieldName);
            if (!(value instanceof View)) continue;
            View candidate = (View) value;
            if (candidate == contentText || isViewAncestor(candidate, contentText)
                    || containers.containsKey(candidate)) continue;
            containers.put(candidate, candidate.getVisibility());
            candidate.setVisibility(View.GONE);
            hidden++;
        }
        if (!containers.isEmpty()) {
            synchronized (remoteViewsHiddenContainers) {
                remoteViewsHiddenContainers.put(prompt, containers);
            }
            synchronized (remoteViewsHiddenKeys) {
                remoteViewsHiddenKeys.put(prompt, key);
            }
        }
        log("updateRemoteViews textFallback hiddenRemoteContainers=" + hidden);
    }

    private static boolean isViewAncestor(View ancestor, View child) {
        ViewParent parent = child == null ? null : child.getParent();
        while (parent instanceof View) {
            if (parent == ancestor) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private boolean shouldConvert(FocusData data) {
        if (data == null || !data.hasIslandParam || !currentSettings.islandCompat) {
            // Called from several hooks on every layout pass, so a per-outcome one-shot diagnosis
            // replaces what would otherwise flood the debug log.
            if (firstDiagnosis("shouldConvert|" + (data == null ? "-" : data.key) + "|false")) {
                debug("shouldConvert=false reason=" + (data == null ? "no-data"
                        : !data.hasIslandParam ? "no-island-param" : "island-compat-disabled")
                        + " package=" + (data == null ? "-" : data.packageName));
            }
            return false;
        }
        boolean whitelisted = currentSettings.islandForcePackages.contains(data.packageName);
        boolean smsVerification = isSmsVerificationCode(data);
        boolean convert = !data.isOriginalFocus || whitelisted || smsVerification;
        if (firstDiagnosis("shouldConvert|" + data.key + "|" + convert)) {
            debug("shouldConvert=" + convert + " package=" + data.packageName
                    + " isOriginalFocus=" + data.isOriginalFocus
                    + " whitelist=" + whitelisted + " smsVerification=" + smsVerification);
        }
        return convert;
    }

    private boolean isSmsVerificationCode(FocusData data) {
        if (data == null || !"com.android.mms".equals(data.packageName)
                || TextUtils.isEmpty(data.islandParam)
                || !InputLimits.isPayloadAllowed(data.islandParam)) return false;
        try {
            JSONObject root = new JSONObject(data.islandParam);
            return root.optInt("protocol", -1) == 1
                    && "verifyCode".equals(root.optString("scene"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasConvertibleIslandContent(FocusData data) {
        if (!currentSettings.islandCompat || data == null || !data.hasIslandParam) return false;
        IslandText text = extractIslandContent(data);
        return text != null && !TextUtils.isEmpty(text.text);
    }

    private void patchMediaBean(Object bean, FocusData data, String stage) {
        String current = stringValue(getField(bean, "content"));
        OriginalBeanState state;
        synchronized (originalBeanStates) {
            state = originalBeanStates.get(bean);
            if (state == null) {
                Object expanded = getField(bean, "sbn");
                Boolean original = preMarkedOriginalFocus.remove(expanded);
                state = new OriginalBeanState(expanded,
                        original != null ? original : getBooleanField(expanded,
                                "mIsFocusNotification", false), current,
                        getField(bean, "icon"), getField(bean, "iconDark"),
                        getField(bean, "drawable"), getField(bean, "drawableDark"));
                originalBeanStates.put(bean, state);
            } else if (!TextUtils.equals(current, state.lastConvertedContent)) {
                state.originalContent = current;
            }
            state.lastConvertedContent = data.mediaContent.text;
        }
        try {
            XposedHelpers.setObjectField(bean, "content", data.mediaContent.text);
            data.content = data.mediaContent.text;
            data.isFocus = true;
            preMarkedIslands.remove(state.expanded);
            log(stage + " applied media content key=" + data.key
                    + " playing=" + (data.mediaContent == null ? "unknown"
                                        : data.mediaContent.playing));
        } catch (Throwable t) {
            error(stage + " applyMediaContent", t);
        }
    }

    private void patchBean(Object bean, String stage) {
        FocusData data = inspectBean(bean);
        if (data == null) {
            log(stage + " bean=null");
            return;
        }

        OriginalBeanState savedState;
        synchronized (originalBeanStates) {
            savedState = originalBeanStates.get(bean);
        }
        if (savedState != null) {
            data.isFocus = savedState.originalFocus || data.hasExplicitFocusData;
            data.isOriginalFocus = savedState.originalFocus || data.hasExplicitFocusData;
        }
        if (data.mediaContent != null && activeMediaNotificationKeys.contains(data.key)
                && currentSettings.mediaFocusEnabled) {
            patchMediaBean(bean, data, stage);
        } else {
            boolean convert = shouldConvert(data);
            IslandText islandText = convert ? extractIslandContent(data) : null;
            if (islandText != null && !TextUtils.isEmpty(islandText.text)) {
            try {
                // Keep the OEM value so a reused Bean can be restored when the
                // payload, settings, or notification identity changes.
                String current = stringValue(getField(bean, "content"));
                OriginalBeanState state;
                synchronized (originalBeanStates) {
                    state = originalBeanStates.get(bean);
                    if (state == null) {
                        Object expanded = getField(bean, "sbn");
                        Boolean preMarkedFocus = preMarkedOriginalFocus.remove(expanded);
                        boolean originalFocus = preMarkedFocus != null
                                ? preMarkedFocus : getBooleanField(expanded,
                                "mIsFocusNotification", data.isFocus);
                        state = new OriginalBeanState(expanded, originalFocus, current,
                                getField(bean, "icon"), getField(bean, "iconDark"),
                                getField(bean, "drawable"), getField(bean, "drawableDark"));
                        originalBeanStates.put(bean, state);
                    } else if (!TextUtils.equals(current, state.lastConvertedContent)) {
                        state.originalContent = current;
                    }
                    state.lastConvertedContent = islandText.text;
                }
                XposedHelpers.setObjectField(bean, "content", islandText.text);
                Object expanded = state.expanded;
                preMarkedIslands.remove(expanded);
                preMarkedOriginalFocus.remove(expanded);
                data.content = islandText.text;
                data.isFocus = true;
                applyConvertedBeanIcon(bean, state, data, stage);
                convertedBeans.add(bean);
                rememberConvertedNotificationKey(data.key);
                log(stage + " applied island focus source=" + islandText.source
                        + " oemContent=" + preview(current));
            } catch (Throwable t) {
                error(stage + " applyIslandContent", t);
            }
        } else if (convert && hasSelectableIslandIcon(data)) {
            // Some island payloads publish a picture without a text section. The icon is still
            // meaningful for the experimental switch, so preserve OEM content and patch the icon.
            String current = stringValue(getField(bean, "content"));
            OriginalBeanState state;
            synchronized (originalBeanStates) {
                state = originalBeanStates.get(bean);
                if (state == null) {
                    Object expanded = getField(bean, "sbn");
                    Boolean preMarkedFocus = preMarkedOriginalFocus.remove(expanded);
                    boolean originalFocus = preMarkedFocus != null
                            ? preMarkedFocus : getBooleanField(expanded,
                            "mIsFocusNotification", data.isFocus);
                    state = new OriginalBeanState(expanded, originalFocus, current,
                            getField(bean, "icon"), getField(bean, "iconDark"),
                            getField(bean, "drawable"), getField(bean, "drawableDark"));
                    originalBeanStates.put(bean, state);
                }
            }
            if (applyConvertedBeanIcon(bean, state, data, stage + "IconOnly")) {
                data.isFocus = true;
                convertedBeans.add(bean);
                rememberConvertedNotificationKey(data.key);
                log(stage + " applied island icon-only focus");
            } else {
                restoreOriginalBean(bean, data, stage);
            }
        } else {
            if (firstDiagnosis(stage + "|" + data.key)) {
                debug(stage + " island not applied key=" + data.key + " package=" + data.packageName
                        + " shouldConvert=" + convert + " parsedText="
                        + (islandText == null ? "none" : "empty"));
            }
            restoreOriginalBean(bean, data, stage);
            Object expanded = getField(bean, "sbn");
            preMarkedIslands.remove(expanded);
            preMarkedOriginalFocus.remove(expanded);
        }
        }

        if (FALLBACK_MAIN_RV_FOR_STATUS_BAR && data.isFocus) {
            try {
                Object contentRv = getField(bean, "contentRemoteViews");
                if (contentRv == null && data.mainRv != null) {
                    XposedHelpers.setObjectField(bean, "contentRemoteViews", data.mainRv);
                    data.contentRv = data.mainRv;
                    log(stage + " filled contentRemoteViews from miui.focus.rv");
                }

                Object nightRv = getField(bean, "contentNightRemoteViews");
                if (nightRv == null && data.mainNightRv != null) {
                    XposedHelpers.setObjectField(bean, "contentNightRemoteViews", data.mainNightRv);
                    data.contentNightRv = data.mainNightRv;
                    log(stage + " filled contentNightRemoteViews from miui.focus.rvNight");
                }
            } catch (Throwable t) {
                error(stage + " patchBean", t);
            }
        }

        log(stage + " " + data.summary());
    }

    private boolean applyConvertedBeanIcon(Object bean, OriginalBeanState state,
                                        FocusData data, String stage) {
        SelectedFocusIcon light = selectFocusIcon(data.notification, data.islandParam, data.packageName,
                false, true);
        if (light == null) {
            restoreConvertedBeanIcon(bean, state, stage);
            return false;
        }
        if (systemUiContext == null) return false;
        SelectedFocusIcon dark = selectFocusIcon(data.notification, data.islandParam, data.packageName,
                true, true);
        if (dark == null) dark = light;
        final FocusIconStyler.Result styledLight;
        final FocusIconStyler.Result styledDark;
        try {
            styledLight = FocusIconStyler.load(systemUiContext, light.icon,
                    light.islandIcon, light.tint, Color.WHITE, 13);
            styledDark = FocusIconStyler.load(systemUiContext, dark.icon,
                    dark.islandIcon, dark.tint, Color.BLACK, 13);
            if (styledLight == null || styledDark == null) {
                log(stage + " island focus icon load returned null package=" + data.packageName
                        + " lightType=" + light.icon.getType()
                        + " darkType=" + dark.icon.getType());
                restoreConvertedBeanIcon(bean, state, stage + " nullIslandIcon");
                return false;
            }
        } catch (Throwable throwable) {
            error(stage + " loadIslandIcon", throwable);
            return false;
        }
        Icon lightIcon = styledLight.icon;
        Icon darkIcon = styledDark.icon;
        Drawable drawable = styledLight.drawable;
        Drawable drawableDark = styledDark.drawable;

        refreshOriginalBeanIconState(bean, state);
        boolean success = true;
        if (hasField(bean, "icon")) {
            state.patchedIcon = setObjectField(bean, "icon", lightIcon,
                    stage + " setIcon");
            if (state.patchedIcon) state.lastConvertedIcon = lightIcon;
            else success = false;
        }
        if (hasField(bean, "iconDark")) {
            state.patchedIconDark = setObjectField(bean, "iconDark", darkIcon,
                    stage + " setIconDark");
            if (state.patchedIconDark) state.lastConvertedIconDark = darkIcon;
            else success = false;
        }
        if (hasField(bean, "drawable")) {
            state.patchedDrawable = setObjectField(bean, "drawable", drawable,
                    stage + " setDrawable");
            if (state.patchedDrawable) state.lastConvertedDrawable = drawable;
            else success = false;
        }
        if (hasField(bean, "drawableDark")) {
            state.patchedDrawableDark = setObjectField(bean, "drawableDark", drawableDark,
                    stage + " setDrawableDark");
            if (state.patchedDrawableDark) state.lastConvertedDrawableDark = drawableDark;
            else success = false;
        }
        if (!success) {
            restoreConvertedBeanIcon(bean, state, stage + " rollbackIslandIcon");
            return false;
        }
        log(stage + " applied island focus icon light=" + light.source
                + " dark=" + dark.source);
        return true;
    }

    private void refreshOriginalBeanIconState(Object bean, OriginalBeanState state) {
        Object current = getField(bean, "icon");
        if (!state.patchedIcon || current != state.lastConvertedIcon) state.originalIcon = current;
        current = getField(bean, "iconDark");
        if (!state.patchedIconDark || current != state.lastConvertedIconDark) {
            state.originalIconDark = current;
        }
        current = getField(bean, "drawable");
        if (!state.patchedDrawable || current != state.lastConvertedDrawable) {
            state.originalDrawable = current;
        }
        current = getField(bean, "drawableDark");
        if (!state.patchedDrawableDark || current != state.lastConvertedDrawableDark) {
            state.originalDrawableDark = current;
        }
    }

    private boolean restoreConvertedBeanIcon(Object bean, OriginalBeanState state, String stage) {
        refreshOriginalBeanIconState(bean, state);
        boolean success = true;
        if (state.patchedIcon) {
            boolean restored = setObjectField(bean, "icon", state.originalIcon, stage + " icon");
            state.patchedIcon = !restored;
            if (restored) state.lastConvertedIcon = null;
            success &= restored;
        }
        if (state.patchedIconDark) {
            boolean restored = setObjectField(bean, "iconDark", state.originalIconDark,
                    stage + " iconDark");
            state.patchedIconDark = !restored;
            if (restored) state.lastConvertedIconDark = null;
            success &= restored;
        }
        if (state.patchedDrawable) {
            boolean restored = setObjectField(bean, "drawable", state.originalDrawable,
                    stage + " drawable");
            state.patchedDrawable = !restored;
            if (restored) state.lastConvertedDrawable = null;
            success &= restored;
        }
        if (state.patchedDrawableDark) {
            boolean restored = setObjectField(bean, "drawableDark", state.originalDrawableDark,
                    stage + " drawableDark");
            state.patchedDrawableDark = !restored;
            if (restored) state.lastConvertedDrawableDark = null;
            success &= restored;
        }
        return success;
    }

    private void restoreOriginalBean(Object bean, FocusData data, String stage) {
        OriginalBeanState state;
        synchronized (originalBeanStates) {
            state = originalBeanStates.get(bean);
        }
        if (state == null) return;
        clearPreMark(state.expanded, false);
        boolean contentRestored = setObjectField(bean, "content", state.originalContent,
                stage + " restoreContent");
        boolean iconRestored = restoreConvertedBeanIcon(bean, state, stage + " restoreIcon");
        boolean focusRestored = true;
        if (state.expanded != null && hasField(state.expanded, "mIsFocusNotification")) {
            try {
                XposedHelpers.setBooleanField(state.expanded, "mIsFocusNotification",
                        state.originalFocus);
            } catch (Throwable throwable) {
                focusRestored = false;
                error(stage + " restoreFocusField", throwable);
            }
        }
        if (data != null) {
            if (contentRestored) data.content = state.originalContent;
            if (focusRestored) data.isFocus = state.originalFocus;
        }
        if (contentRestored && iconRestored && focusRestored) {
            synchronized (originalBeanStates) {
                if (originalBeanStates.get(bean) == state) originalBeanStates.remove(bean);
            }
            convertedBeans.remove(bean);
            log(stage + " restored original focus content and icon");
        }
    }

    private static boolean hasField(Object target, String fieldName) {
        if (target == null) return false;
        try {
            return XposedHelpers.findFieldIfExists(target.getClass(), fieldName) != null;
        } catch (Throwable throwable) {
            error("probe field " + fieldName, throwable);
            return false;
        }
    }

    private static boolean setObjectField(Object target, String fieldName, Object value,
                                          String stage) {
        try {
            XposedHelpers.setObjectField(target, fieldName, value);
            return true;
        } catch (Throwable throwable) {
            error(stage, throwable);
            return false;
        }
    }

    private IslandText extractIslandContent(FocusData data) {
        if (data == null || TextUtils.isEmpty(data.islandParam)) return null;
        IslandPayloadParser.ParsedText parsed = currentSettings.islandTextMode
                == FocusRestoreSettings.ISLAND_TEXT_MODE_COMPACT
                ? IslandPayloadParser.parseCompact(data.islandParam, currentSettings.sideSeparator)
                : null;
        if (parsed == null) {
            parsed = IslandPayloadParser.parse(
                    data.islandParam, currentSettings.generalSeparator, currentSettings.sideSeparator);
        }
        if (parsed == null) {
            debug("island parse failed package=" + data.packageName + " mode="
                    + (currentSettings.islandTextMode == FocusRestoreSettings.ISLAND_TEXT_MODE_COMPACT
                    ? "compact-then-full" : "full")
                    + " " + IslandPayloadParser.describeStructure(data.islandParam));
            debug("island payload package=" + data.packageName + " "
                    + boundedPayload(data.islandParam));
            return null;
        }
        debug("island parse ok package=" + data.packageName + " source=" + parsed.source
                + " textLength=" + parsed.text.length() + " text=" + preview(parsed.text));
        if (firstDiagnosis("payload:" + data.packageName)) {
            debug("island payload package=" + data.packageName + " "
                    + boundedPayload(data.islandParam));
        }
        return new IslandText(parsed.text, parsed.source);
    }

    /**
     * The raw payload for verbose diagnostics, bounded so one line stays readable. Needed because a
     * wrong join cannot be corrected from the extracted text alone: the observed duplication
     * ({@code D8396·检票口·检票口 检票口}) depends on which fields of which island node held which value.
     */
    private static String boundedPayload(String payload) {
        if (payload == null) return "payload=null";
        String flat = payload.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= 2000 ? flat : flat.substring(0, 2000) + "...<truncated "
                + flat.length() + " chars>";
    }

    private boolean hasSelectableIslandIcon(FocusData data) {
        if (data == null || !currentSettings.showIslandIcon) return false;
        SelectedFocusIcon light = selectFocusIcon(data.notification, data.islandParam,
                data.packageName, false, false);
        return light != null && light.islandIcon;
    }

    private SelectedFocusIcon selectFocusIcon(Notification notification, String islandParam,
                                                String packageName, boolean dark,
                                                boolean allowFallback) {
        if (notification == null) return null;
        Bundle extras = notification.extras;
        Bundle pictures = extras == null ? null : extras.getBundle("miui.focus.pics");
        String directReference = extras == null ? null : extras.getString(
                dark ? "miui.focus.pic_ticker_dark" : "miui.focus.pic_ticker");
        Icon icon = iconFromBundle(pictures, directReference);
        if (icon != null) {
            return new SelectedFocusIcon(icon, false, false, "ticker:" + directReference);
        }
        if (dark && extras != null) {
            icon = iconFromBundle(pictures, extras.getString("miui.focus.pic_ticker"));
            if (icon != null) return new SelectedFocusIcon(icon, false, false, "tickerLight");
        }
        String payloadTicker = IslandPayloadParser.findTickerPictureReference(islandParam, dark);
        icon = iconFromBundle(pictures, payloadTicker);
        if (icon != null) {
            return new SelectedFocusIcon(icon, false, false, "tickerPayload:" + payloadTicker);
        }

        if (currentSettings.showIslandIcon) {
            String payloadReference = IslandPayloadParser.findPictureReference(islandParam, dark);
            icon = iconFromBundle(pictures, payloadReference);
            if (icon != null) return new SelectedFocusIcon(icon,
                    currentSettings.tintIslandIcon, true, "island:" + payloadReference);
            // 引用名拿不到时，借设备通知那条已经验证过的做法：按名字去应用自己的包里找 drawable。
            // 岛载荷下发的引用名形如 miui.focus.pic_weather，第三方应用通常没有这个资源名，
            // 所以这只是一种可能落空的兜底；成功与否由 logFocusIconResolution 的
            // islandDrawable 字段记录下来（能找到就说明该应用真的按这个名字放了资源）。
            Icon packageDrawable = islandIconFromPackage(packageName, payloadReference);
            if (packageDrawable != null) {
                return new SelectedFocusIcon(packageDrawable, currentSettings.tintIslandIcon,
                        true, "islandDrawable:" + packageName + "/" + payloadReference);
            }
        }
        logFocusIconResolution(pictures, islandParam, packageName, dark);

        if (!allowFallback) return null;
        if (currentSettings.useSmallIconFallback) {
            icon = notification.getSmallIcon();
            if (icon != null) return new SelectedFocusIcon(icon,
                    currentSettings.tintIslandIcon, false, "notificationSmallIcon");
        }
        icon = applicationIcon(packageName);
        return icon == null ? null : new SelectedFocusIcon(icon, false, false,
                "applicationIcon");
    }

    /**
     * 岛图标的兜底：把载荷引用名当资源名，去应用自己的包里找同名 drawable。
     *
     * <p>与设备通知 {@code DeviceNotificationFocusPoster#drawableFromPackage} 同一套思路
     * （`getResourcesForApplication` + `getIdentifier(name, "drawable", pkg)` + 位图化），
     * 目标包换成发通知的应用。设备通知那条路能成立是因为模型直接给的就是 ROM 组件包里的资源名；
     * 岛载荷给的是 {@code miui.focus.pic_*} 这类引用名，第三方应用一般不会用这个名字定义资源，
     * 所以这里只当兜底，命中与否都要能从日志看出来，不能当成"一定修好了"。
     */
    private Icon islandIconFromPackage(String packageName, String reference) {
        String resourceName = IslandPayloadParser.drawableNameFromReference(reference);
        if (resourceName == null || TextUtils.isEmpty(packageName)) return null;
        Context context = systemUiContext;
        if (context == null) return null;
        Drawable drawable = drawableFromOtherPackage(context, packageName, resourceName);
        return drawable == null ? null : drawableToIcon(drawable);
    }

    private static Drawable drawableFromOtherPackage(Context context, String packageName, String name) {
        try {
            Resources resources = context.getPackageManager().getResourcesForApplication(packageName);
            if (resources == null) return null;
            int id = resources.getIdentifier(name, "drawable", packageName);
            return id == 0 ? null : resources.getDrawable(id, null);
        } catch (Throwable throwable) {
            debug("island icon drawable lookup failed package=" + packageName
                    + " name=" + name + " reason=" + throwable.getClass().getSimpleName());
            return null;
        }
    }

    private Icon drawableToIcon(Drawable drawable) {
        if (drawable == null || systemUiContext == null) return null;
        try {
            int size = Math.max(1, Math.round(
                    32f * systemUiContext.getResources().getDisplayMetrics().density));
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(new Canvas(bitmap));
            return Icon.createWithBitmap(bitmap);
        } catch (Throwable throwable) {
            error("island icon rasterize", throwable);
            return null;
        }
    }

    /**
     * 诊断：岛图标取不到时，把"载荷要求的引用名"、"通知实际提供的 pics 键"和"按名字查应用资源的结果"
     * 一起打出来。
     *
     * <p>0.27.0 之前这条通路在 OS3 上一次都没有真正跑过：所有 OS3 真机日志里
     * {@code showIslandIcon} 都是 false，`applied island focus icon` 全部是
     * {@code applicationIcon}（唯一一次岛图标解析成功是 0.13.22 的 OS4 日志
     * `light=island:miui.focus.pic_isl…`）。让日志能区分"载荷没引用岛图"、
     * "引用了但通知的 {@code miui.focus.pics} 里没有这个键"、"键在但不是 Icon"、
     * "按引用名去应用包里也没查到"四种情况——它们需要完全不同的修法。
     */
    private void logFocusIconResolution(Bundle pictures, String islandParam, String packageName,
                                        boolean dark) {
        if (!firstDiagnosis("focusIcon|" + dark)) return;
        String tickerReference = IslandPayloadParser.findTickerPictureReference(islandParam, dark);
        String islandReference = IslandPayloadParser.findPictureReference(islandParam, dark);
        StringBuilder keys = new StringBuilder();
        if (pictures != null) {
            for (String key : pictures.keySet()) {
                if (keys.length() > 0) keys.append(',');
                keys.append(key);
            }
        }
        Object islandValue = pictures == null || islandReference == null
                ? null : pictures.get(islandReference);
        String resourceName = IslandPayloadParser.drawableNameFromReference(islandReference);
        boolean drawableFound = false;
        if (resourceName != null && systemUiContext != null && !TextUtils.isEmpty(packageName)) {
            try {
                Resources resources = systemUiContext.getPackageManager()
                        .getResourcesForApplication(packageName);
                drawableFound = resources != null
                        && resources.getIdentifier(resourceName, "drawable", packageName) != 0;
            } catch (Throwable ignored) {
                drawableFound = false;
            }
        }
        debug("focus icon resolution dark=" + dark
                + " showIslandIcon=" + currentSettings.showIslandIcon
                + " package=" + packageName
                + " islandRef=" + islandReference
                + " islandValue=" + (islandValue == null ? "absent" : islandValue.getClass().getSimpleName())
                + " islandDrawable=" + (resourceName == null ? "n/a"
                        : drawableFound ? "found:" + resourceName : "missing:" + resourceName)
                + " tickerRef=" + tickerReference
                + " picsKeys=[" + keys + "]");
    }

    private Icon applicationIcon(String packageName) {
        Context context = systemUiContext;
        if (context == null || TextUtils.isEmpty(packageName)) return null;
        try {
            Class<?> managerClass = FocusReflection.findClass(
                    "com.miui.systemui.graphics.AppIconsManager", classLoader);
            Class<?> interfaces = FocusReflection.findClass(
                    "com.miui.systemui.interfacesmanager.InterfacesImplManager", classLoader);
            if (managerClass != null && interfaces != null) {
                Object manager = XposedHelpers.callStaticMethod(interfaces, "getImpl", managerClass);
                int userId = 0;
                try {
                    userId = ((Number) XposedHelpers.callStaticMethod(
                            android.os.UserHandle.class, "myUserId")).intValue();
                } catch (Throwable ignored) { }
                Object value = XposedHelpers.callMethod(manager, "getAppIconBitmap", userId, packageName);
                if (value instanceof Bitmap && !((Bitmap) value).isRecycled()) {
                    log("application icon source=systemui-theme package=" + packageName);
                    return Icon.createWithBitmap((Bitmap) value);
                }
            }
        } catch (Throwable throwable) {
            log("application icon themed pipeline unavailable package=" + packageName
                    + " reason=" + throwable.getClass().getSimpleName());
        }
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            Drawable drawable = info.loadIcon(context.getPackageManager());
            if (drawable == null) return null;
            int size = Math.max(1, Math.round(32f * context.getResources().getDisplayMetrics().density));
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(new Canvas(bitmap));
            log("application icon source=package-manager package=" + packageName);
            return Icon.createWithBitmap(bitmap);
        } catch (PackageManager.NameNotFoundException exception) {
            log("application icon unavailable package=" + packageName);
            return null;
        } catch (Throwable throwable) {
            error("load application icon package=" + packageName, throwable);
            return null;
        }
    }

    private static Icon iconFromBundle(Bundle pictures, String reference) {
        if (pictures == null || TextUtils.isEmpty(reference)) return null;
        try {
            Parcelable value = pictures.getParcelable(reference);
            return value instanceof Icon ? (Icon) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private IslandText extractIslandContentLegacy(FocusData data) {
        if (data == null || TextUtils.isEmpty(data.islandParam)) return null;
        // A notification that already has focus data must use that data as-is.
        if (data.hasExplicitFocusData && !data.hasIslandParam) {
            log("island conversion skipped because explicit focus data already exists");
            return null;
        }
        try {
            JSONObject root = new JSONObject(data.islandParam);
            JSONObject v2 = root.optJSONObject("param_v2");
            if (v2 == null) v2 = root;

            // Older HyperOS focus payloads (notably SMS verification) use protocol 1.
            if (root.optInt("protocol", 3) == 1 || "verifyCode".equals(root.optString("scene"))) {
                String legacy = joinTexts(root, "protocol1", "title", "desc1", "desc2");
                if (!TextUtils.isEmpty(legacy)) {
                    log("island content source=protocol1:" + root.optString("scene", "legacy")
                            + " text=" + legacy);
                    return new IslandText(legacy, "protocol1:" + root.optString("scene", "legacy"));
                }
                return null;
            }

            JSONObject base = v2.optJSONObject("baseInfo");
            JSONObject highlight = v2.optJSONObject("highlightInfo");
            JSONObject highlightV3 = v2.optJSONObject("highlightInfoV3");
            JSONObject chat = v2.optJSONObject("chatInfo");
            JSONObject hint = v2.optJSONObject("hintInfo");

            String source = null;
            String result = joinTexts(base, "title", "subTitle", "specialTitle",
                    "extraTitle", "content", "subContent");
            if (base != null && !TextUtils.isEmpty(result)) {
                // Some HyperOS 3.0.5 builds drop BaseInfo.title while retaining
                // subTitle/content. The same primary title remains in the island
                // imageTextInfo payload, so restore it before displaying the focus text.
                String islandTitle = findPrimaryIslandTitle(v2.optJSONObject("param_island"));
                if (!TextUtils.isEmpty(islandTitle) && !result.startsWith(islandTitle)) {
                    result = joinText(islandTitle, result);
                    log("restored missing baseInfo title from param_island=" + islandTitle);
                }
                String islandExtra = findIslandText(v2.optJSONObject("param_island"));
                String hintExtra = joinTexts(hint, "hintInfo", "title", "content", "subContent");
                result = appendDistinctText(result, hintExtra);
                if (!TextUtils.isEmpty(hintExtra)) {
                    log("merged hintInfo content into baseInfo");
                }
                if (!TextUtils.isEmpty(islandExtra)) {
                    String merged = appendDistinctText(result, islandExtra);
                    if (!TextUtils.equals(result, merged)) {
                        result = merged;
                        log("merged additional param_island content into baseInfo");
                    }
                }
            }
            if (!TextUtils.isEmpty(result)) source = "baseInfo";
            if (TextUtils.isEmpty(result)) {
                result = joinTexts(highlight, "title", "content", "subContent");
                if (!TextUtils.isEmpty(result)) source = "highlightInfo";
            }
            if (TextUtils.isEmpty(result)) {
                result = joinTexts(highlightV3, "primaryText", "secondaryText", "highLightText",
                        "label");
                if (!TextUtils.isEmpty(result)) source = "highlightInfoV3";
            }
            if (TextUtils.isEmpty(result)) {
                result = joinTexts(chat, "title", "content");
                if (!TextUtils.isEmpty(result)) source = "chatInfo";
            }
            if (TextUtils.isEmpty(result)) {
                JSONObject iconText = v2.optJSONObject("iconTextInfo");
                result = joinCompact(firstText(iconText, "title"), firstText(iconText, "content"));
                result = joinCompact(result, firstText(iconText, "subContent"));
                if (!TextUtils.isEmpty(result)) source = "iconTextInfo";
            }
            if (TextUtils.isEmpty(result)) {
                result = joinTexts(v2.optJSONObject("animTextInfo"), "title", "content");
                if (!TextUtils.isEmpty(result)) source = "animTextInfo";
            }
            if (TextUtils.isEmpty(result)) {
                result = joinTexts(v2.optJSONObject("coverInfo"), "title", "content", "subContent");
                if (!TextUtils.isEmpty(result)) source = "coverInfo";
            }
            if (TextUtils.isEmpty(result)) {
                result = joinTexts(hint, "title", "subTitle", "content", "subContent");
                if (!TextUtils.isEmpty(result)) {
                    // aodTitle is the always-on-display label; merging it into focus text duplicated
                    // the title on real payloads. This legacy extractor is currently unused, but the
                    // trap is removed so wiring it back up cannot reintroduce that bug.
                    source = "hintInfo";
                }
            }
            if (TextUtils.isEmpty(result)) {
                JSONObject multiProgress = v2.optJSONObject("multiProgressInfo");
                result = progressText(multiProgress);
                if (!TextUtils.isEmpty(result)) source = "multiProgressInfo";
            }
            if (TextUtils.isEmpty(result)) {
                result = progressText(v2.optJSONObject("progressInfo"));
                if (!TextUtils.isEmpty(result)) source = "progressInfo";
            }
            if (TextUtils.isEmpty(result)) {
                result = joinTexts(v2.optJSONObject("stepInfo"), "title", "content", "subContent", "step");
                if (!TextUtils.isEmpty(result)) source = "stepInfo";
            }
            if (TextUtils.isEmpty(result)) {
                JSONObject island = v2.optJSONObject("param_island");
                result = findIslandText(island);
                if (!TextUtils.isEmpty(result)) source = "param_island";
            }
            if (TextUtils.isEmpty(result)) {
                result = cleanText(v2.optString("ticker", null));
                if (!TextUtils.isEmpty(result)) source = "ticker";
            }
            if (TextUtils.isEmpty(result) && v2 != root) {
                result = cleanText(root.optString("ticker", null));
                if (!TextUtils.isEmpty(result)) source = "custom.ticker";
            }
            if (TextUtils.isEmpty(result)) return null;
            log("island content source=" + source + " text=" + result);
            return new IslandText(result, source);
        } catch (Throwable t) {
            log("island param parse failed");
            return null;
        }
    }

    private String joinTexts(JSONObject object, String ignoredSource, String... keys) {
        if (object == null) return null;
        String result = null;
        for (String key : keys) result = joinText(result, firstText(object, key));
        return result;
    }

    private String progressText(JSONObject object) {
        if (object == null) return null;
        String result = joinText(firstText(object, "title", "content", "label"),
                percentageText(object));
        if (!TextUtils.isEmpty(result)) return result;
        JSONObject nested = object.optJSONObject("progressInfo");
        return nested == null ? null : joinText(firstText(nested, "title", "content", "label"),
                percentageText(nested));
    }

    private static String percentageText(JSONObject object) {
        if (object == null || !object.has("progress")) return null;
        Object value = object.opt("progress");
        if (value == null || value == JSONObject.NULL) return null;
        String text = cleanText(String.valueOf(value));
        return TextUtils.isEmpty(text) ? null : (text.endsWith("%") ? text : text + "%");
    }

    private static String sourceFor(JSONObject v2, String result) {
        if (v2.has("baseInfo")) return "baseInfo";
        if (v2.has("highlightInfo")) return "highlightInfo";
        if (v2.has("chatInfo")) return "chatInfo";
        if (v2.has("hintInfo")) return "hintInfo";
        if (v2.has("multiProgressInfo")) return "multiProgressInfo";
        if (v2.has("param_island")) return "param_island";
        return "ticker";
    }

    private String findIslandText(JSONObject island) {
        if (island == null) return null;
        String result = null;
        result = appendDistinctText(result, joinTexts(island, "param_island", "title", "content", "frontTitle"));

        JSONObject big = island.optJSONObject("bigIslandArea");
        JSONObject left = big == null ? null : big.optJSONObject("imageTextInfoLeft");
        JSONObject text = left == null ? null : firstObject(left, "textInfo", "miui.focus.paramtextInfo");
        String leftText = joinTexts(text, "imageTextInfoLeft", "frontTitle", "title", "content", "subContent");

        // BigIslandArea is explicitly a two-sided payload. The side separator is
        // reserved for the boundary between the left and right areas.
        JSONObject right = big == null ? null : big.optJSONObject("imageTextInfoRight");
        text = right == null ? null : firstObject(right, "textInfo", "miui.focus.paramtextInfo");
        String rightText = joinTexts(text, "imageTextInfoRight", "frontTitle", "title", "content", "subContent");
        String sideText = appendSideText(leftText, rightText);
        result = appendDistinctText(result, sideText);

        result = appendDistinctText(result,
                progressText(big == null ? null : firstObject(big,
                        "progressTextInfo", "fixedWidthDigitInfo", "sameWidthDigitInfo")));
        JSONObject small = island.optJSONObject("smallIslandArea");
        result = appendDistinctText(result,
                joinTexts(small, "smallIslandArea", "title", "content", "subContent"));
        return result;
    }

    private String appendSideText(String first, String second) {
        return appendDistinctText(first, second, currentSettings.sideSeparator);
    }

    private String appendDistinctText(String first, String second) {
        return appendDistinctText(first, second, currentSettings.generalSeparator);
    }

    private static String appendDistinctText(String first, String second, String separator) {
        first = cleanText(first);
        second = cleanText(second);
        if (TextUtils.isEmpty(second)) return first;
        if (TextUtils.isEmpty(first)) return second;
        if (first.equals(second) || first.contains(second)) return first;
        if (second.contains(first)) return second;
        return first + separator + second;
    }

    private static String findPrimaryIslandTitle(JSONObject island) {
        if (island == null) return null;
        JSONObject big = island.optJSONObject("bigIslandArea");
        JSONObject left = big == null ? null : big.optJSONObject("imageTextInfoLeft");
        JSONObject text = left == null ? null : firstObject(left, "textInfo", "miui.focus.paramtextInfo");
        return firstText(text, "title", "frontTitle", "content");
    }

    private static JSONObject firstObject(JSONObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            JSONObject value = object.optJSONObject(key);
            if (value != null) return value;
        }
        return null;
    }

    private static String firstText(JSONObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            String value = cleanText(object.optString(key, null));
            if (!TextUtils.isEmpty(value)) return value;
        }
        return null;
    }

    private String joinCompact(String first, String second) {
        first = cleanText(first);
        second = cleanText(second);
        if (TextUtils.isEmpty(first)) return second;
        if (TextUtils.isEmpty(second) || first.equals(second)) return first;
        return first + currentSettings.generalSeparator + second;
    }

    private String joinText(String first, String second) {
        first = cleanText(first);
        second = cleanText(second);
        if (TextUtils.isEmpty(first)) return second;
        if (TextUtils.isEmpty(second) || first.equals(second)) return first;
        return first + currentSettings.generalSeparator + second;
    }

    private static String cleanText(String value) {
        if (value == null) return null;
        value = value.trim();
        return value.length() == 0 ? null : value;
    }

    private boolean isFocusDisplayExpired(FocusData data) {
        return remainingDisplayMillis(data) == 0L;
    }

    /**
     * Milliseconds left in the user's display window, {@link Long#MAX_VALUE} when unlimited and 0
     * once it has passed. The window starts when FocusRestore actually shows the focus, so a
     * notification that already existed when the limit was configured is still shown once instead
     * of being filtered out forever. A new post time (new notification generation) starts a new
     * window; plain content updates do not extend the current one.
     */
    private long remainingDisplayMillis(FocusData data) {
        float seconds = currentSettings == null ? 0f : currentSettings.focusMaxDisplaySeconds;
        if (seconds <= 0f || data == null || TextUtils.isEmpty(data.key)) {
            return Long.MAX_VALUE;
        }
        // An exempt app keeps the duration the system itself gives the focus notification.
        if (isTimeoutExempt(data.packageName)) return Long.MAX_VALUE;
        long limit = Math.max(1L, (long) (seconds * 1000f));
        long postTime = generation(data);
        long[] window;
        synchronized (focusDisplayWindows) {
            window = focusDisplayWindows.get(data.key);
        }
        if (window == null || window[1] != postTime) return limit;
        long elapsed = SystemClock.elapsedRealtime() - window[0];
        return elapsed >= limit ? 0L : limit - elapsed;
    }

    /**
     * Whether this app's focus display ignores the configured maximum time. Exempting an app is
     * separate from the focus whitelist: the whitelist decides whether island content becomes a focus
     * notification at all, this decides how long the module lets it stay on the status bar.
     */
    private boolean isTimeoutExempt(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        HookSettings settings = currentSettings;
        return settings != null && settings.focusTimeoutExemptPackages.contains(packageName);
    }

    /**
     * The notification generation the display window belongs to. Zero when the expanded object is not
     * a StatusBarNotification (the ROM's own ExpandedNotification is not), which is the normal case —
     * requiring a post time there is what used to disable the limit entirely.
     */
    private static long generation(FocusData data) {
        return data == null || data.sbn == null ? 0L : data.sbn.getPostTime();
    }

    private void markFocusDisplayed(FocusData data) {
        float seconds = currentSettings == null ? 0f : currentSettings.focusMaxDisplaySeconds;
        if (seconds <= 0f || data == null || TextUtils.isEmpty(data.key)) return;
        if (isTimeoutExempt(data.packageName)) return;
        long postTime = generation(data);
        synchronized (focusDisplayWindows) {
            long[] existing = focusDisplayWindows.get(data.key);
            if (existing != null && existing[1] == postTime) return;
            if (focusDisplayWindows.size() > 64) focusDisplayWindows.clear();
            focusDisplayWindows.put(data.key, new long[]{SystemClock.elapsedRealtime(), postTime});
        }
    }

    private void forgetFocusDisplay(String key) {
        if (TextUtils.isEmpty(key)) return;
        synchronized (focusDisplayWindows) {
            focusDisplayWindows.remove(key);
        }
    }

    private long remainingDisplayMillis(Object entry) {
        return remainingDisplayMillis(inspectExpanded(notificationEntrySbn(entry)));
    }

    private String keyFromEntry(Object entry) {
        Object value = getField(entry, "key");
        if (value == null) value = getField(entry, "mKey");
        return stringValue(value);
    }

    private HyperOS4FocusController.DisplayItem createOS4DisplayItem(Object entry) {
        rememberNotificationEntry(entry);
        reloadSettings(false);
        Object expanded = notificationEntrySbn(entry);
        if (expanded == null) return null;
        FocusData data = inspectExpanded(expanded);
        if (isFocusDisplayExpired(data)) {
            log("OS4 display expired key=" + keyFromEntry(entry));
            return null;
        }
        if (data == null) return null;
        Object keyValue = getField(entry, "key");
        if (keyValue == null) keyValue = getField(entry, "mKey");
        String key = stringValue(keyValue);
        if (TextUtils.isEmpty(key)) return null;

        Notification notification = null;
        try {
            Object value = XposedHelpers.callMethod(expanded, "getNotification");
            if (value instanceof Notification) notification = (Notification) value;
        } catch (Throwable t) {
            error("OS4 getNotification key=" + key, t);
        }
        PendingIntent contentIntent = notification == null ? null : notification.contentIntent;
        boolean constructedDevice = notification != null && notification.extras != null
                && notification.extras.getBoolean(DeviceNotificationFocusPoster.EXTRA_MODULE_MARKER, false);
        if (constructedDevice) debug("device stage=os4-candidate key=" + key
                + " isOriginalFocus=" + data.isOriginalFocus + " ticker=" + preview(data.ticker)
                + " expired=" + isFocusDisplayExpired(data)
                + " " + focusExtrasInventory(notification.extras));

        boolean hasNativeStatusBarContent = OS4FocusPriorityPolicy.hasNativeStatusBarContent(
                data.barRv != null || data.barNightRv != null,
                !TextUtils.isEmpty(data.ticker), data.hasIslandParam);
        log("OS4 classification key=" + key + " package=" + data.packageName
                + " originalFocusField=" + data.originalFocusField
                + " explicitFocus=" + data.explicitFocus
                + " isOriginalFocus=" + data.isOriginalFocus
                + " hasBarRemoteViews=" + (data.barRv != null || data.barNightRv != null)
                + " ticker=" + data.ticker
                + " hasIslandParam=" + data.hasIslandParam
                + " islandParam=" + data.islandParam
                + " nativeStatusBarContent=" + hasNativeStatusBarContent
                + " forcePackage=" + currentSettings.islandForcePackages.contains(data.packageName));
        if (data.mediaContent != null) {
            activeMediaNotificationKeys.add(data.key);
        } else {
            activeMediaNotificationKeys.remove(data.key);
        }
        if (currentSettings.mediaFocusEnabled && data.mediaContent != null
                && activeMediaNotificationKeys.contains(data.key)) {
                                     MediaFocusContent media = data.mediaContent;
            SelectedFocusIcon light = selectFocusIcon(notification, null, data.packageName, false, true);
            SelectedFocusIcon dark = selectFocusIcon(notification, null, data.packageName, true, true);
            log("OS4 media candidate key=" + key + " package=" + data.packageName
                    + " playing=" + media.playing + " source=MediaStyle");
            return new HyperOS4FocusController.DisplayItem(entry, key, data.packageName,
                    media.text, "mediaStyle", null, null, contentIntent,
                    light == null ? null : light.icon, dark == null ? null : dark.icon,
                    light != null && light.tint, dark != null && dark.tint,
                    light != null && light.islandIcon, dark != null && dark.islandIcon,
                    90);
        }

        if (data.isOriginalFocus && hasNativeStatusBarContent) {
            boolean showOnStatusBar = false;
            try {
                Class<?> utils = FocusReflection.findClass(classLoader,
                        "com.android.systemui.statusbar.notification.utils.FocusUtils");
                Object result = XposedHelpers.callStaticMethod(utils, "showOnStatusBar", expanded);
                showOnStatusBar = Boolean.TRUE.equals(result);
            } catch (Throwable t) {
                error("OS4 native showOnStatusBar key=" + key, t);
            }
            if (constructedDevice) debug("device stage=os4-gate key=" + key
                    + " showOnStatusBar=" + showOnStatusBar);
            if (!showOnStatusBar) {
                log("OS4 native Focus rejected by showOnStatusBar key=" + key
                        + " " + data.summary());
                return null;
            }
            SelectedFocusIcon focusIcon = selectFocusIcon(notification, data.islandParam, data.packageName,
                    false, true);
            SelectedFocusIcon focusIconDark = selectFocusIcon(notification, data.islandParam, data.packageName,
                    true, true);
            if (constructedDevice) debug("device stage=os4-icon key=" + key
                    + " light=" + (focusIcon == null ? "none" : focusIcon.source)
                    + " dark=" + (focusIconDark == null ? "none" : focusIconDark.source));
            log("OS4 native focus icon key=" + key + " light="
                    + (focusIcon == null ? "none" : focusIcon.source) + " dark="
                    + (focusIconDark == null ? "none" : focusIconDark.source));
            // The ROM's own bar RemoteViews carry the application's wording and cover the text this
            // module parses, which is why the pill showed the notification's own content instead of
            // the island sides and why the focus content mode had no effect on OS4 at all. For a
            // whitelisted package the user has said this app's island content should be the focus
            // text, so the parse wins here exactly as it does on the OS3 pill.
            IslandText whitelisted = currentSettings.islandForcePackages.contains(data.packageName)
                    ? extractIslandContent(data) : null;
            if (whitelisted != null && !TextUtils.isEmpty(whitelisted.text)) {
                log("OS4 whitelist parse wins over native content key=" + key
                        + " source=" + whitelisted.source + " text=" + preview(whitelisted.text));
                return new HyperOS4FocusController.DisplayItem(entry, key, data.packageName,
                        whitelisted.text, "whitelistParsed:" + whitelisted.source, null, null,
                        contentIntent, focusIcon == null ? null : focusIcon.icon,
                        focusIconDark == null ? null : focusIconDark.icon,
                        focusIcon != null && focusIcon.tint,
                        focusIconDark != null && focusIconDark.tint,
                        focusIcon != null && focusIcon.islandIcon,
                        focusIconDark != null && focusIconDark.islandIcon,
                        OS4FocusPriorityPolicy.PRIORITY_ISLAND_WHITELIST);
            }
            return new HyperOS4FocusController.DisplayItem(entry, key, data.packageName,
                    cleanText(data.ticker), "nativeFocus", data.barRv, data.barNightRv,
                    contentIntent, focusIcon == null ? null : focusIcon.icon,
                    focusIconDark == null ? null : focusIconDark.icon,
                    focusIcon != null && focusIcon.tint,
                    focusIconDark != null && focusIconDark.tint,
                    focusIcon != null && focusIcon.islandIcon,
                    focusIconDark != null && focusIconDark.islandIcon,
                    OS4FocusPriorityPolicy.PRIORITY_NATIVE_FOCUS);
        }

        // OS4 may mark an island notification as Focus before it has any native
        // status-bar content. Classify without mutating mIsFocusNotification.
        if (data.hasIslandParam && !hasNativeStatusBarContent) {
            data.isOriginalFocus = false;
        }
        if (!shouldConvert(data)) return null;
        IslandText islandText = extractIslandContent(data);
        if (islandText == null || TextUtils.isEmpty(islandText.text)) return null;
        int priority;
        String source;
        if (currentSettings.islandForcePackages.contains(data.packageName)) {
            priority = OS4FocusPriorityPolicy.PRIORITY_ISLAND_WHITELIST;
            source = "islandWhitelist:" + islandText.source;
        } else if (isSmsVerificationCode(data)) {
            priority = OS4FocusPriorityPolicy.PRIORITY_SMS_VERIFICATION;
            source = "smsVerification:" + islandText.source;
        } else {
            priority = OS4FocusPriorityPolicy.PRIORITY_ISLAND;
            source = "island:" + islandText.source;
        }
        SelectedFocusIcon focusIcon = selectFocusIcon(notification, data.islandParam,
                data.packageName, false, true);
        SelectedFocusIcon focusIconDark = selectFocusIcon(notification, data.islandParam,
                data.packageName, true, true);
        log("OS4 island focus icon key=" + key + " light="
                + (focusIcon == null ? "none" : focusIcon.source) + " dark="
                + (focusIconDark == null ? "none" : focusIconDark.source));
        return new HyperOS4FocusController.DisplayItem(entry, key, data.packageName,
                islandText.text, source, null, null, contentIntent,
                focusIcon == null ? null : focusIcon.icon,
                focusIconDark == null ? null : focusIconDark.icon,
                focusIcon != null && focusIcon.tint,
                focusIconDark != null && focusIconDark.tint,
                focusIcon != null && focusIcon.islandIcon,
                focusIconDark != null && focusIconDark.islandIcon, priority);
    }

    private FocusData inspectBean(Object bean) {
        if (bean == null) return null;
        try {
            FocusData data = inspectExpanded(getField(bean, "sbn"));
            if (data == null) data = new FocusData();
            data.key = stringValue(getField(bean, "notifKey"));
            if (TextUtils.isEmpty(data.packageName)) data.packageName = packageFromKey(data.key);
            data.content = stringValue(getField(bean, "content"));
            data.contentRv = asRemoteViews(getField(bean, "contentRemoteViews"));
            data.contentNightRv = asRemoteViews(getField(bean, "contentNightRemoteViews"));
            return data;
        } catch (Throwable t) {
            error("inspectBean", t);
            return null;
        }
    }

    private FocusData inspectExpanded(Object expanded) {
        if (expanded == null) return null;
        try {
            FocusData data = new FocusData();
            if (expanded instanceof StatusBarNotification) data.sbn = (StatusBarNotification) expanded;
            data.packageName = notificationPackageName(expanded);
            // Every key-based decision (the display window, the converted-key set, the click guards)
            // indexes by this. inspectBean reads it from the bean, but the expanded object is also
            // inspected directly, and the ROM's ExpandedNotification is not a StatusBarNotification —
            // so without this the key stayed empty and those decisions were silently inert.
            if (TextUtils.isEmpty(data.key)) {
                Object key = invokeNoArg(expanded, "getKey");
                if (key == null) key = getField(expanded, "key");
                if (key == null) key = getField(expanded, "mKey");
                data.key = stringValue(key);
            }
            boolean preMarked = preMarkedIslands.contains(expanded);
            boolean originalFocusField = getBooleanField(expanded, "mIsFocusNotification", false);
            data.originalFocusField = originalFocusField;
            data.isFocus = originalFocusField;

            Notification notification = null;
            try {
                notification = (Notification) XposedHelpers.callMethod(expanded, "getNotification");
            } catch (Throwable ignored) {
                Object sbnNotification = invokeNoArg(expanded, "getNotification");
                if (sbnNotification instanceof Notification) notification = (Notification) sbnNotification;
            }

            data.notification = notification;
            data.mediaContent = MediaFocusContent.from(notification);
            if (data.mediaContent != null && !TextUtils.isEmpty(data.key)) {
                mediaNotificationKeys.add(data.key);
            }
            if (data.mediaContent != null && data.mediaContent.token != null) {
                MediaController controller = MediaFocusContent.controller(systemUiContext,
                        data.mediaContent.token);
                if (controller != null) {
                    data.mediaContent = data.mediaContent.withPlaybackState(controller);
                    if (firstDiagnosis("mediaController|" + data.key)) {
                        debug("media playback state read key=" + data.key
                                + " callback=not-registered; updates follow notification events");
                    }
                } else if (firstDiagnosis("mediaControllerUnavailable|" + data.key)) {
                    debug("media playback state unavailable key=" + data.key
                            + " callback=not-registered");
                }
            }
            if (notification == null || notification.extras == null) return data;
            Bundle extras = notification.extras;
            boolean explicitFocus = extras.getBoolean("miui.focus.isFocus", false);
            data.explicitFocus = explicitFocus;
            data.isFocus = data.isFocus || explicitFocus;
            // Shared key policy: some system apps publish the island schema under
            // miui.focus.param.custom instead of miui.focus.param.
            data.islandParam = FocusParamKeys.pick(extras::get);
            data.hasIslandParam = !TextUtils.isEmpty(data.islandParam);
            String diagnosisKey = data.sbn == null ? null : data.sbn.getKey();
            if (firstDiagnosis(diagnosisKey)) {
                debug("focus extras key=" + diagnosisKey + " package=" + data.packageName
                        + " " + FocusParamKeys.describe(extras::get) + " "
                        + focusExtrasInventory(extras));
            }
            data.ticker = extras.getString("miui.focus.ticker");
            data.mainRv = getRemoteViews(extras, "miui.focus.rv");
            data.mainNightRv = getRemoteViews(extras, "miui.focus.rvNight");
            data.barRv = getRemoteViews(extras, "miui.focus.rvBar");
            data.barNightRv = getRemoteViews(extras, "miui.focus.rvBarNight");
            data.hasMainRv = data.mainRv != null || data.mainNightRv != null;
            data.hasBarRv = data.barRv != null || data.barNightRv != null;
            boolean hasTicker = !TextUtils.isEmpty(data.ticker);
            data.hasExplicitFocusData = FocusPriorityPolicy.hasExplicitFocusData(
                    explicitFocus, data.hasMainRv, data.hasBarRv, hasTicker, data.hasIslandParam);
            data.isOriginalFocus = FocusPriorityPolicy.isOriginalFocus(
                    originalFocusField, explicitFocus, data.hasMainRv, data.hasBarRv,
                    hasTicker, data.hasIslandParam);
            if (preMarked) data.isOriginalFocus = false;
            return data;
        } catch (Throwable t) {
            error("inspectExpanded", t);
            return null;
        }
    }

    private static String notificationPackageName(Object expanded) {
        try {
            Object value = invokeNoArg(expanded, "getPackageName");
            if (value != null) return String.valueOf(value);
        } catch (Throwable ignored) {
        }
        try {
            Object sbn = getField(expanded, "mSbn");
            if (sbn == null) sbn = getField(expanded, "sbn");
            if (sbn != null) {
                Object value = invokeNoArg(sbn, "getPackageName");
                if (value != null) return String.valueOf(value);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String packageFromKey(String key) {
        if (TextUtils.isEmpty(key)) return null;
        String[] parts = key.split("\\|", 5);
        return parts.length > 1 && parts[1].length() > 0 ? parts[1] : null;
    }

    private static RemoteViews getRemoteViews(Bundle extras, String key) {
        try {
            Parcelable value = extras.getParcelable(key);
            return value instanceof RemoteViews ? (RemoteViews) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static RemoteViews asRemoteViews(Object value) {
        return value instanceof RemoteViews ? (RemoteViews) value : null;
    }

    private static Object getField(Object target, String name) {
        if (target == null) return null;
        try {
            return XposedHelpers.getObjectField(target, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void clearPreMark(Object expanded, boolean restoreOriginal) {
        if (expanded == null) return;
        Boolean original = preMarkedOriginalFocus.remove(expanded);
        preMarkedIslands.remove(expanded);
        if (restoreOriginal && original != null) {
            try {
                XposedHelpers.setBooleanField(expanded, "mIsFocusNotification", original);
            } catch (Throwable t) {
                error("restorePreMarkedFocus", t);
            }
        }
    }

    private static boolean getBooleanField(Object target, String name, boolean fallback) {
        if (target == null) return fallback;
        try {
            return XposedHelpers.getBooleanField(target, name);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Object invokeNoArg(Object target, String method) throws Exception {
        Method value = target.getClass().getMethod(method);
        value.setAccessible(true);
        return value.invoke(target);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void log(String value) {
        Log.i(TAG, value);
        XposedBridge.log(TAG + ": " + value);
    }

    /**
     * Verbose diagnostics for debug builds only, so release logging keeps its previous volume.
     * The {@code DIAG} marker keeps these greppable in a captured logcat or LSPosed log.
     */
    private static void debug(String value) {
        if (!VERBOSE_LOG) return;
        Log.i(TAG, "DIAG " + value);
        XposedBridge.log(TAG + ": DIAG " + value);
    }

    /**
     * Bounded single-line preview with an ASCII-escaped copy for non-ASCII text; see
     * {@link DebugText#preview(String)} for why the escaped form matters in captured logs.
     */
    private static String preview(String value) {
        return DebugText.preview(value);
    }

    /**
     * Every {@code miui.focus.*} extras key with its value kind, so a missing or mistyped key is
     * visible directly instead of being inferred from a later failure.
     */
    private static String focusExtrasInventory(Bundle extras) {
        if (extras == null) return "focusExtras=none";
        StringBuilder text = new StringBuilder("focusExtras=[");
        int count = 0;
        for (String name : extras.keySet()) {
            if (name == null || !name.startsWith("miui.focus.")) continue;
            if (count > 0) text.append(',');
            if (++count > 24) { text.append(",..."); break; }
            Object value = extras.get(name);
            text.append(name).append(':');
            if (value == null) text.append("null");
            else if (value instanceof String) text.append(((String) value).trim().length());
            else if (value instanceof Bundle) text.append("Bundle(").append(((Bundle) value).keySet().size()).append(')');
            else text.append(value.getClass().getSimpleName());
        }
        return text.append(']').toString();
    }

    /**
     * True only the first time a notification key is diagnosed. {@code inspectExpanded} runs on
     * every layout pass, so an unguarded inventory line would flood the log and hide the signal.
     */
    private boolean firstDiagnosis(String key) {
        if (key == null) return false;
        synchronized (diagnosedExtrasKeys) {
            if (!diagnosedExtrasKeys.add(key)) return false;
            while (diagnosedExtrasKeys.size() > MAX_DIAGNOSED_KEYS) {
                Iterator<String> oldest = diagnosedExtrasKeys.iterator();
                oldest.next();
                oldest.remove();
            }
            return true;
        }
    }

    private static void error(String stage, Throwable t) {
        Log.e(TAG, stage, t);
        XposedBridge.log(TAG + " ERROR " + stage + ": " + Log.getStackTraceString(t));
    }

    private static final class OriginalWidthState {
        int maxWidth;
        int layoutWidth;
        boolean hasLayoutParams;
        int lastAppliedWidth;

        OriginalWidthState(int maxWidth, int layoutWidth, boolean hasLayoutParams,
                           int lastAppliedWidth) {
            this.maxWidth = maxWidth;
            this.layoutWidth = layoutWidth;
            this.hasLayoutParams = hasLayoutParams;
            this.lastAppliedWidth = lastAppliedWidth;
        }
    }

    private static final class ParentWidthState {
        int originalWidth;
        int lastAppliedWidth;

        ParentWidthState(int originalWidth, int lastAppliedWidth) {
            this.originalWidth = originalWidth;
            this.lastAppliedWidth = lastAppliedWidth;
        }
    }

    private static final class OriginalBeanState {
        final Object expanded;
        final boolean originalFocus;
        String originalContent;
        String lastConvertedContent;
        Object originalIcon;
        Object originalIconDark;
        Object originalDrawable;
        Object originalDrawableDark;
        Object lastConvertedIcon;
        Object lastConvertedIconDark;
        Object lastConvertedDrawable;
        Object lastConvertedDrawableDark;
        boolean patchedIcon;
        boolean patchedIconDark;
        boolean patchedDrawable;
        boolean patchedDrawableDark;

        OriginalBeanState(Object expanded, boolean originalFocus, String originalContent,
                          Object originalIcon, Object originalIconDark,
                          Object originalDrawable, Object originalDrawableDark) {
            this.expanded = expanded;
            this.originalFocus = originalFocus;
            this.originalContent = originalContent;
            this.originalIcon = originalIcon;
            this.originalIconDark = originalIconDark;
            this.originalDrawable = originalDrawable;
            this.originalDrawableDark = originalDrawableDark;
        }
    }

    private static final class SelectedFocusIcon {
        final Icon icon;
        final boolean tint;
        final boolean islandIcon;
        final String source;

        SelectedFocusIcon(Icon icon, boolean tint, boolean islandIcon, String source) {
            this.icon = icon;
            this.tint = tint;
            this.islandIcon = islandIcon;
            this.source = source;
        }
    }

    private static final class IslandText {
        final String text;
        final String source;

        IslandText(String text, String source) {
            this.text = text;
            this.source = source;
        }
    }

    private static final class FocusData {
        boolean isFocus;
        boolean originalFocusField;
        boolean explicitFocus;
        boolean isOriginalFocus;
        boolean hasMainRv;
        boolean hasBarRv;
        boolean hasIslandParam;
        boolean hasExplicitFocusData;
        String islandParam;
        String key;
        String packageName;
        String ticker;
        String content;
        RemoteViews mainRv;
        RemoteViews mainNightRv;
        RemoteViews barRv;
        RemoteViews barNightRv;
        RemoteViews contentRv;
        RemoteViews contentNightRv;
        Notification notification;
        StatusBarNotification sbn;
        MediaFocusContent mediaContent;

        boolean hasDisplayContent() {
            return hasMainRv || hasBarRv || !TextUtils.isEmpty(ticker)
                    || !TextUtils.isEmpty(content) || contentRv != null;
        }

        String summary() {
            return "key=" + key
                    + " focus=" + isFocus
                    + " ticker=" + !TextUtils.isEmpty(ticker)
                    + " content=" + !TextUtils.isEmpty(content)
                    + " rv=" + (mainRv != null)
                    + " rvNight=" + (mainNightRv != null)
                    + " rvBar=" + (barRv != null)
                    + " rvBarNight=" + (barNightRv != null)
                    + " islandParam=" + hasIslandParam
                    + " contentRv=" + (contentRv != null)
                    + " contentNightRv=" + (contentNightRv != null);
        }
    }
}