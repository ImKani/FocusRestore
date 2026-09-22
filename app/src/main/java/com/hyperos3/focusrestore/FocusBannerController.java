package com.hyperos3.focusrestore;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

/** Owns an independent SystemUI window containing only an independently created native template. */
public final class FocusBannerController {
    public interface Logger {
        void log(String message);
        void error(String stage, Throwable error);
    }

    interface NotificationOpener {
        boolean open(String key, StatusBarNotification expected);
    }

    private static final long VISIBLE_MS = 10_000L;
    private static final long MAX_VISIBLE_MS = 20_000L;
    private static final long INTERACTION_MS = 4_000L;
    private static final long MONITOR_MS = 250L;
    private static final long UPDATE_MS = 80L;
    private static final long NATIVE_RETRY_MS = 250L;
    private static final long NATIVE_GRACE_MS = 1_500L;
    private static final String USER_SWITCHED = "android.intent.action.USER_SWITCHED";
    private static final String USER_STOPPED = "android.intent.action.USER_STOPPED";
    private static final String COLLAPSE_ISLAND = "com.miui.action.ACTION_COLLAPSE_ISLAND";

    private final Logger logger;
    private final NativeFocusTemplateRenderer renderer;
    private final NotificationOpener notificationOpener;
    private volatile Handler main;
    private volatile boolean nativeOperation;
    private int nativeOperationDepth;
    private WeakReference<View> anchorRef = new WeakReference<>(null);
    private BannerFrame banner;
    private WindowManager windowManager;
    private WindowManager.LayoutParams windowParams;
    private Context windowContext;
    private Context receiverContext;
    private DisplayManager displayManager;
    private KeyguardManager keyguard;
    private PowerManager power;
    private String currentKey;
    private StatusBarNotification currentSbn;
    private int displayId = -1;
    private int displayRotation;
    private long softDeadline;
    private long hardDeadline;
    private long lastRenderAttempt;
    private long nativeGraceDeadline;
    private boolean receiverRegistered;
    private boolean callbacksRegistered;
    private boolean displayListenerRegistered;
    private boolean windowAdded;
    private boolean removalPending;
    private boolean removingWindow;
    private int removalAttempts;
    private boolean updateScheduled;
    private long logPeriod;
    private int logCount;

    private final Runnable monitor = this::monitorWindow;
    private final Runnable removeRetry = this::removeWindow;
    private final Runnable applyUpdate = this::applyPendingUpdate;
    private final Runnable retryNative = () -> scheduleUpdate(0L);
    private final Runnable expireNativeGrace = () -> {
        if (banner != null && !removalPending && nativeGraceDeadline != 0L) {
            event(currentKey, "native not ready: previous frame grace expired");
            dismissMain("native-refresh-not-ready-timeout");
        }
    };
    private final View.OnAttachStateChangeListener anchorListener =
            new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { }
                @Override public void onViewDetachedFromWindow(View view) {
                    if (anchorRef.get() == view) dismiss("anchor-detached");
                }
            };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action) || USER_SWITCHED.equals(action)
                    || USER_STOPPED.equals(action) || COLLAPSE_ISLAND.equals(action)
                    || Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_CONFIGURATION_CHANGED.equals(action)
                    || Intent.ACTION_SHUTDOWN.equals(action)) {
                dismiss("broadcast:" + action);
            }
        }
    };
    private final ComponentCallbacks callbacks = new ComponentCallbacks() {
        @Override public void onConfigurationChanged(Configuration configuration) {
            dismiss("configuration-changed");
        }
        @Override public void onLowMemory() { dismiss("low-memory"); }
    };
    private final DisplayManager.DisplayListener displayListener =
            new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) { }
                @Override public void onDisplayRemoved(int id) {
                    if (id == displayId) dismiss("display-removed");
                }
                @Override public void onDisplayChanged(int id) {
                    runMain("display-changed", () -> {
                        if (id != displayId || displayManager == null) return;
                        Display display = displayManager.getDisplay(id);
                        if (display == null || display.getState() != Display.STATE_ON
                                || display.getRotation() != displayRotation) {
                            dismissMain("display-state-or-rotation-changed");
                        }
                    });
                }
            };

    public FocusBannerController(Logger logger, NativeFocusTemplateRenderer renderer,
                                 NotificationOpener notificationOpener) {
        if (logger == null || renderer == null || notificationOpener == null) {
            throw new IllegalArgumentException("logger/renderer/opener == null");
        }
        this.logger = logger;
        this.renderer = renderer;
        this.notificationOpener = notificationOpener;
    }

    private Handler mainHandler() {
        Handler result = main;
        if (result == null) {
            synchronized (this) {
                result = main;
                if (result == null) main = result = new Handler(Looper.getMainLooper());
            }
        }
        return result;
    }

    /** True means add/update was accepted, not that a frame has reached the display. */
    public boolean show(View anchor, String key, StatusBarNotification sbn) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            event(key, "show rejected: caller is not main thread; no deferred window created");
            return false;
        }
        if (anchor == null || TextUtils.isEmpty(key) || sbn == null || sbn.getNotification() == null) {
            event(key, "show rejected: missing anchor/key/notification");
            return false;
        }
        try {
            Display display = anchor.getDisplay();
            if (!anchor.isAttachedToWindow() || display == null) {
                event(key, "show rejected: anchor detached or has no display");
                return false;
            }
            if (banner != null && sameKey(key) && displayId == display.getDisplayId() && !removalPending) {
                String blocked = displayBlockedReason();
                if (blocked != null) {
                    dismissMain(blocked);
                    event(key, "show rejected: " + blocked);
                    return false;
                }
                clearPendingUpdate();
                setAnchor(anchor);
                currentSbn = sbn;
                refreshNative();
                if (banner == null || removalPending) return false;
                extendDeadline(VISIBLE_MS);
                event(key, "show accepted: existing native window; frame confirmation is separate");
                return true;
            }
            if (banner != null) dismissMain("notification-switch");
            if (banner != null) {
                event(key, "show rejected: previous window removal still pending");
                return false;
            }

            currentKey = key;
            currentSbn = sbn;
            displayId = display.getDisplayId();
            displayRotation = display.getRotation();
            Context anchorContext = anchor.getContext();
            windowContext = anchorContext.createDisplayContext(display);
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    windowContext = windowContext.createWindowContext(
                            WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG, null);
                } catch (Throwable error) {
                    fail("createWindowContext; using anchor display context", error);
                }
            }
            windowManager = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
            keyguard = (KeyguardManager) anchorContext.getSystemService(Context.KEYGUARD_SERVICE);
            power = (PowerManager) anchorContext.getSystemService(Context.POWER_SERVICE);
            displayManager = (DisplayManager) anchorContext.getSystemService(Context.DISPLAY_SERVICE);
            String blocked = displayBlockedReason();
            if (blocked != null || windowManager == null || displayManager == null) {
                event(key, "show rejected: " + (blocked == null ? "window/display service missing" : blocked));
                clearWindowState();
                return false;
            }
            setAnchor(anchor);
            Prepared prepared;
            try {
                prepared = prepareNative(anchor, display);
            } catch (Throwable error) {
                fail(key, "native not ready: first open failed; next click may retry", error);
                event(key, "show rejected: native not ready; no host created");
                dismissMain("native-not-ready");
                return false;
            }
            try {
                banner = new BannerFrame(windowContext);
                banner.install(prepared);
            } catch (Throwable error) {
                // A candidate not yet adopted by the host must still release its session.
                if (banner == null || banner.render != prepared.render) closeRender(prepared.render, key);
                throw error;
            }
            Geometry geometry = prepared.geometry;
            windowParams = new WindowManager.LayoutParams(geometry.width,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            windowParams.token = new Binder();
            windowParams.gravity = Gravity.TOP | Gravity.LEFT;
            windowParams.x = geometry.left;
            windowParams.y = geometry.top;
            windowParams.setTitle("FocusRestore independent native focus banner");
            if (Build.VERSION.SDK_INT >= 30) {
                windowParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                windowParams.setFitInsetsTypes(0);
            } else if (Build.VERSION.SDK_INT >= 28) {
                windowParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            long now = SystemClock.uptimeMillis();
            softDeadline = now + VISIBLE_MS;
            hardDeadline = now + MAX_VISIBLE_MS;
            registerLifecycle(anchorContext);
            blocked = displayBlockedReason();
            if (blocked != null) {
                dismissMain(blocked);
                event(key, "show rejected immediately before add: " + blocked);
                return false;
            }
            windowManager.addView(banner, windowParams);
            windowAdded = true;
            mainHandler().postDelayed(monitor, MONITOR_MS);
            event(key, "show accepted: addView returned type=2009 display=" + displayId
                    + " source=" + safe(prepared.render.source, 240)
                    + " width=" + geometry.width + " measuredHeight=" + prepared.measuredHeight
                    + " nativeMinHeight=" + prepared.render.minHeightPx + " maxHeight=" + geometry.maxHeight
                    + " x=" + geometry.left + " y=" + geometry.top
                    + "; awaiting native Ready/onLayout/draw; visibility unverified");
            return true;
        } catch (Throwable error) {
            fail(key, "show rejected", error);
            dismissMain("show-failed");
            return false;
        }
    }

    public void onNotificationChanged(String key, StatusBarNotification sbn) {
        runMain("notification-changed", () -> {
            if (banner == null || removalPending || !sameKey(key)) return;
            if (sbn == null || sbn.getNotification() == null) {
                dismissMain("notification-became-null");
                return;
            }
            currentSbn = sbn;
            scheduleUpdate(UPDATE_MS);
        });
    }

    /** The source observer must exclude templates owned by this renderer. */
    public void onNativeSourceChanged() {
        // Native factory/attach/close hooks can synchronously report our own template.
        if (nativeOperation) return;
        runMain("native-source-changed", () -> {
            if (nativeOperation || banner == null || removalPending || currentSbn == null) return;
            if (renderer.isCurrent(banner.render, currentSbn)) return;
            scheduleUpdate(UPDATE_MS);
        });
    }

    public void onNotificationRemoved(String key) {
        runMain("notification-removed", () -> {
            if (sameKey(key)) dismissMain("notification-removed");
        });
    }

    public void dismiss(String reason) {
        runMain("dismiss", () -> dismissMain(reason == null ? "requested" : reason));
    }

    private void scheduleUpdate(long delay) {
        if (banner == null || removalPending || currentSbn == null || updateScheduled) return;
        // Once scheduled, subsequent notifications/source events only replace currentSbn.
        // They never move the drain deadline, including while a stale frame is retained.
        long now = SystemClock.uptimeMillis();
        long due = Math.max(now + delay, lastRenderAttempt + UPDATE_MS);
        banner.cancelNativeGesture();
        updateScheduled = mainHandler().postAtTime(applyUpdate, due);
        if (!updateScheduled) dismissMain("update-handler-unavailable");
    }

    private void applyPendingUpdate() {
        updateScheduled = false;
        if (banner == null || removalPending || currentSbn == null) return;
        try {
            String blocked = displayBlockedReason();
            if (blocked != null) { dismissMain(blocked); return; }
            refreshNative();
        } catch (Throwable error) {
            fail("native update", error);
            dismissMain("update-failed");
        }
    }

    private void refreshNative() {
        View anchor = anchorRef.get();
        Display display = anchor == null ? null : anchor.getDisplay();
        if (anchor == null || !anchor.isAttachedToWindow() || display == null
                || display.getDisplayId() != displayId) {
            dismissMain("anchor-unavailable");
            return;
        }
        Prepared prepared;
        try {
            prepared = prepareNative(anchor, display);
        } catch (Throwable error) {
            retainPreviousFrame(error);
            return;
        }
        try {
            banner.install(prepared);
            windowParams.width = prepared.geometry.width;
            windowParams.x = prepared.geometry.left;
            windowParams.y = prepared.geometry.top;
            windowManager.updateViewLayout(banner, windowParams);
            clearNativeRetry();
        } catch (Throwable error) {
            if (banner == null || banner.render != prepared.render) closeRender(prepared.render, currentKey);
            fail("install native update", error);
            dismissMain("native-install-failed");
        }
    }

    private Prepared prepareNative(View anchor, Display display) throws Throwable {
        lastRenderAttempt = SystemClock.uptimeMillis();
        NativeFocusTemplateRenderer.Render candidate = null;
        beginNativeOperation();
        try {
            candidate = renderer.create(currentKey, currentSbn);
            if (candidate == null || candidate.view == null || candidate.context == null) {
                throw new IllegalStateException("native source/template not ready");
            }
            if (banner != null && (candidate == banner.render
                    || (banner.render != null && candidate.view == banner.render.view))) {
                // Never dispose a session whose view is still being displayed.
                candidate = null;
                throw new IllegalStateException("renderer reused the currently displayed native session");
            }
            if (candidate.view.getParent() != null || candidate.view.isAttachedToWindow()) {
                throw new IllegalStateException("native template is not an independent unattached view");
            }
            if (candidate.widthPx <= 0 || candidate.minHeightPx < 0) {
                throw new IllegalStateException("native template has invalid dimensions");
            }
            Geometry geometry = geometry(anchor, display, candidate.widthPx);
            if (geometry.width <= 0 || geometry.maxHeight <= 0) {
                throw new IllegalStateException("no safe display area for native template");
            }
            candidate.view.measure(View.MeasureSpec.makeMeasureSpec(geometry.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(geometry.maxHeight, View.MeasureSpec.AT_MOST));
            int measuredHeight = Math.min(geometry.maxHeight, candidate.view.getMeasuredHeight());
            if (measuredHeight <= 0) throw new IllegalStateException("native template measured empty");
            return new Prepared(candidate, geometry, measuredHeight);
        } catch (Throwable error) {
            if (candidate != null) closeRender(candidate, currentKey);
            throw error;
        } finally {
            endNativeOperation();
        }
    }

    private void retainPreviousFrame(Throwable error) {
        if (banner == null || banner.render == null || removalPending) {
            fail("native not ready: no previous frame", error);
            dismissMain("native-not-ready");
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (nativeGraceDeadline == 0L) {
            nativeGraceDeadline = Math.min(hardDeadline, now + NATIVE_GRACE_MS);
            fail("native refresh not ready: retaining same-key previous frame briefly", error);
            event(currentKey, "native not ready: retaining source=" + safe(banner.render.source, 240)
                    + " for at most " + Math.max(0L, nativeGraceDeadline - now) + "ms");
            mainHandler().postAtTime(expireNativeGrace, nativeGraceDeadline);
        }
        if (now >= nativeGraceDeadline) {
            dismissMain("native-refresh-not-ready-timeout");
            return;
        }
        mainHandler().removeCallbacks(retryNative);
        mainHandler().postAtTime(retryNative, Math.min(nativeGraceDeadline, now + NATIVE_RETRY_MS));
    }

    private void clearNativeRetry() {
        mainHandler().removeCallbacks(retryNative);
        mainHandler().removeCallbacks(expireNativeGrace);
        nativeGraceDeadline = 0L;
    }

    private void beginNativeOperation() {
        nativeOperationDepth++;
        nativeOperation = true;
    }

    private void endNativeOperation() {
        nativeOperationDepth--;
        nativeOperation = nativeOperationDepth != 0;
    }

    private void closeRender(NativeFocusTemplateRenderer.Render render, String key) {
        if (render == null) return;
        beginNativeOperation();
        try { render.close(); }
        catch (Throwable error) { fail(key, "close native session", error); }
        finally { endNativeOperation(); }
    }

    private void runMain(String stage, Runnable action) {
        Runnable guarded = () -> {
            try { action.run(); }
            catch (Throwable error) {
                fail(stage, error);
                dismissMain(stage + "-failed");
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) guarded.run();
        else if (!mainHandler().post(guarded)) event(null, stage + " rejected: main handler unavailable");
    }

    private boolean sameKey(String key) { return key != null && key.equals(currentKey); }

    private void setAnchor(View anchor) {
        View previous = anchorRef.get();
        if (previous == anchor) return;
        if (previous != null) previous.removeOnAttachStateChangeListener(anchorListener);
        anchorRef = new WeakReference<>(anchor);
        anchor.addOnAttachStateChangeListener(anchorListener);
    }

    private void registerLifecycle(Context context) {
        receiverContext = context.getApplicationContext();
        if (receiverContext == null) receiverContext = context;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(USER_SWITCHED);
        filter.addAction(USER_STOPPED);
        filter.addAction(COLLAPSE_ISLAND);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        if (Build.VERSION.SDK_INT >= 33) {
            receiverContext.registerReceiver(receiver, filter, null, mainHandler(), Context.RECEIVER_NOT_EXPORTED);
        } else {
            receiverContext.registerReceiver(receiver, filter, null, mainHandler());
        }
        receiverRegistered = true;
        windowContext.registerComponentCallbacks(callbacks);
        callbacksRegistered = true;
        displayManager.registerDisplayListener(displayListener, mainHandler());
        displayListenerRegistered = true;
    }

    private String displayBlockedReason() {
        if (keyguard == null || power == null) return "privacy services unavailable (fail closed)";
        try {
            if (!power.isInteractive()) return "screen-not-interactive";
            if (keyguard.isKeyguardLocked() || keyguard.isDeviceLocked()) return "keyguard-locked";
            Display display = displayManager == null ? null : displayManager.getDisplay(displayId);
            if (display == null || display.getState() != Display.STATE_ON) return "display-not-on";
            if (display.getRotation() != displayRotation) return "display-rotation-changed";
        } catch (Throwable error) {
            fail("privacy check", error);
            return "privacy-check-failed";
        }
        return null;
    }

    private void monitorWindow() {
        if (banner == null || removalPending) return;
        try {
            String blocked = displayBlockedReason();
            View anchor = anchorRef.get();
            if (blocked != null || anchor == null || !anchor.isAttachedToWindow() || !anchor.isShown()) {
                dismissMain(blocked == null ? "anchor-unavailable" : blocked);
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now >= softDeadline || now >= hardDeadline) {
                dismissMain(now >= hardDeadline ? "hard-timeout" : "auto-timeout");
                return;
            }
            mainHandler().postDelayed(monitor, Math.min(MONITOR_MS, Math.min(softDeadline, hardDeadline) - now));
        } catch (Throwable error) {
            fail("lifecycle monitor", error);
            dismissMain("monitor-failed");
        }
    }

    private void extendDeadline(long duration) {
        if (banner != null && !removalPending) {
            softDeadline = Math.min(hardDeadline, Math.max(softDeadline,
                    SystemClock.uptimeMillis() + duration));
        }
    }

    private void clearPendingUpdate() {
        mainHandler().removeCallbacks(applyUpdate);
        updateScheduled = false;
    }

    private void dismissMain(String reason) {
        mainHandler().removeCallbacks(monitor);
        clearPendingUpdate();
        clearNativeRetry();
        View anchor = anchorRef.get();
        if (anchor != null) anchor.removeOnAttachStateChangeListener(anchorListener);
        anchorRef.clear();
        if (receiverRegistered) {
            receiverRegistered = false;
            try { receiverContext.unregisterReceiver(receiver); }
            catch (Throwable error) { fail("unregister receiver", error); }
        }
        if (callbacksRegistered) {
            callbacksRegistered = false;
            try { windowContext.unregisterComponentCallbacks(callbacks); }
            catch (Throwable error) { fail("unregister configuration callback", error); }
        }
        if (displayListenerRegistered) {
            displayListenerRegistered = false;
            try { displayManager.unregisterDisplayListener(displayListener); }
            catch (Throwable error) { fail("unregister display listener", error); }
        }
        if (banner == null) {
            clearWindowState();
            return;
        }
        if (!removalPending) {
            event(currentKey, "dismiss reason=" + safe(reason, 180));
            removalPending = true;
            try { banner.setVisibility(View.GONE); }
            catch (Throwable error) { fail("hide native host", error); }
        }
        // Do not close native resources until their root has actually left the host.
        removeWindow();
    }

    private void removeWindow() {
        if (banner == null || removingWindow) return;
        removingWindow = true;
        mainHandler().removeCallbacks(removeRetry);
        try {
            if (windowAdded || banner.getParent() != null || banner.isAttachedToWindow()) {
                windowManager.removeViewImmediate(banner);
                windowAdded = false;
            }
            if (banner.getParent() != null || banner.isAttachedToWindow()) {
                throw new IllegalStateException("window removal returned before detaching native host");
            }
            banner.releaseContent();
            clearWindowState();
        } catch (Throwable error) {
            fail("remove native window attempt=" + (++removalAttempts), error);
            if (banner != null && !banner.isAttachedToWindow() && banner.getParent() == null) {
                // removeViewImmediate may throw after detaching; never remove this root twice.
                windowAdded = false;
                try {
                    banner.releaseContent();
                    clearWindowState();
                    return;
                } catch (Throwable releaseError) { fail("detach native content after window removal", releaseError); }
            }
            if (banner != null && windowManager != null && windowParams != null && windowAdded) {
                try {
                    windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    windowManager.updateViewLayout(banner, windowParams);
                } catch (Throwable updateError) { fail("make failed-removal window untouchable", updateError); }
            }
            if (removalAttempts < 3) mainHandler().postDelayed(removeRetry, 150L * removalAttempts);
            else event(currentKey, "window/content removal pending; retries exhausted, new adds blocked");
        } finally {
            removingWindow = false;
        }
    }

    private void clearWindowState() {
        mainHandler().removeCallbacks(removeRetry);
        banner = null;
        windowManager = null;
        windowParams = null;
        windowContext = null;
        receiverContext = null;
        displayManager = null;
        keyguard = null;
        power = null;
        currentKey = null;
        currentSbn = null;
        displayId = -1;
        windowAdded = false;
        removalPending = false;
        removalAttempts = 0;
        lastRenderAttempt = 0L;
    }

    private Geometry geometry(View anchor, Display display, int nativeWidth) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        Rect bounds = new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
        int left = 0;
        int right = 0;
        int top = 0;
        int bottom = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowMetrics windowMetrics = windowManager.getMaximumWindowMetrics();
            bounds.set(windowMetrics.getBounds());
            Insets insets = windowMetrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            left = insets.left;
            right = insets.right;
            top = insets.top;
            bottom = insets.bottom;
        }
        WindowInsets rootInsets = anchor.getRootWindowInsets();
        if (rootInsets != null) {
            left = Math.max(left, rootInsets.getStableInsetLeft());
            right = Math.max(right, rootInsets.getStableInsetRight());
            top = Math.max(top, rootInsets.getStableInsetTop());
            bottom = Math.max(bottom, rootInsets.getStableInsetBottom());
            if (Build.VERSION.SDK_INT >= 28 && rootInsets.getDisplayCutout() != null) {
                left = Math.max(left, rootInsets.getDisplayCutout().getSafeInsetLeft());
                right = Math.max(right, rootInsets.getDisplayCutout().getSafeInsetRight());
                top = Math.max(top, rootInsets.getDisplayCutout().getSafeInsetTop());
                bottom = Math.max(bottom, rootInsets.getDisplayCutout().getSafeInsetBottom());
            }
        }
        int statusId = windowContext.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (statusId != 0) top = Math.max(top, windowContext.getResources().getDimensionPixelSize(statusId));
        int margin = dp(windowContext, 12);
        int usableWidth = Math.max(0, bounds.width() - left - right - 2 * margin);
        Geometry result = new Geometry();
        result.width = Math.min(nativeWidth, usableWidth);
        result.left = bounds.left + left + margin + (usableWidth - result.width) / 2;
        result.top = bounds.top + top + dp(windowContext, 6);
        result.maxHeight = Math.max(0, bounds.bottom - bottom - margin - result.top);
        return result;
    }

    /** Transparent exception boundary; all visible content and actions belong to the native root. */
    private final class BannerFrame extends FrameLayout {
        private final String notificationKey;
        private NativeFocusTemplateRenderer.Render render;
        private int maxHeight;
        private boolean renderAttached;
        private int lastLayoutWidth = -1;
        private int lastLayoutHeight = -1;
        private int layoutLogCount;
        private final BannerBodyTap bodyTap;
        private boolean bodyClickInProgress;
        private boolean drawnLogged;
        private boolean renderingFailed;
        private final Runnable attachedCallback = this::notifyNativeAttached;

        BannerFrame(Context context) {
            super(context);
            notificationKey = currentKey;
            setClipChildren(true);
            setClipToPadding(true);
            bodyTap = new BannerBodyTap(ViewConfiguration.get(context).getScaledTouchSlop(),
                    ViewConfiguration.getLongPressTimeout());
            // Native descendants keep first refusal of every gesture. Only unhandled body taps
            // reach this FrameLayout; no listener is installed on a native action or its parent.
            setOnClickListener(ignored -> openNotificationBody());
            setFocusable(true);
        }

        private void openNotificationBody() {
            if (bodyClickInProgress || banner != this || removalPending || renderingFailed
                    || updateScheduled || nativeGraceDeadline != 0L
                    || !renderer.isCurrent(render, currentSbn)) return;
            String blocked = displayBlockedReason();
            if (blocked != null) { dismiss(blocked); return; }
            NativeFocusTemplateRenderer.Render clicked = render;
            bodyClickInProgress = true;
            try {
                boolean dispatched = notificationOpener.open(notificationKey, currentSbn);
                event(notificationKey, "body click native-row dispatched=" + dispatched);
                if (dispatched && banner == this && render == clicked) dismiss("body-click");
            } catch (Throwable error) {
                fail(notificationKey, "body click native-row dispatch", error);
            } finally {
                bodyClickInProgress = false;
            }
        }

        void install(Prepared prepared) {
            beginNativeOperation();
            try {
                releaseContent();
                render = prepared.render;
                maxHeight = prepared.geometry.maxHeight;
                setMinimumHeight(0);
                renderAttached = false;
                lastLayoutWidth = -1;
                lastLayoutHeight = -1;
                layoutLogCount = 0;
                drawnLogged = false;
                renderingFailed = false;
                addView(render.view, new FrameLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));
                if (isAttachedToWindow()) mainHandler().post(attachedCallback);
                requestLayout();
                invalidate();
            } finally {
                endNativeOperation();
            }
        }

        void releaseContent() {
            cancelNativeGesture();
            mainHandler().removeCallbacks(attachedCallback);
            NativeFocusTemplateRenderer.Render previous = render;
            if (previous == null) return;
            if (previous.view.getParent() == this) removeView(previous.view);
            if (previous.view.getParent() != null || previous.view.isAttachedToWindow()) {
                throw new IllegalStateException("native root still attached; session close deferred");
            }
            render = null;
            renderAttached = false;
            closeRender(previous, notificationKey);
        }

        private void notifyNativeAttached() {
            if (banner != this || removalPending || renderingFailed || renderAttached || render == null
                    || !isAttachedToWindow() || !render.view.isAttachedToWindow()) return;
            NativeFocusTemplateRenderer.Render attached = render;
            beginNativeOperation();
            try {
                attached.onAttached();
                renderAttached = true;
                event(notificationKey, "native Ready source=" + safe(attached.source, 240)
                        + " attached=true width=" + attached.widthPx + " minHeight=" + attached.minHeightPx);
            } catch (Throwable error) {
                renderingFailure("native onAttached", error);
            } finally {
                endNativeOperation();
            }
        }

        private void renderingFailure(String stage, Throwable error) {
            if (renderingFailed) return;
            renderingFailed = true;
            fail(notificationKey, stage + " failed", error);
            NativeFocusTemplateRenderer.Render failedRender = render;
            mainHandler().post(() -> {
                if (banner == this && renderingFailed && render == failedRender) {
                    dismissMain(stage + "-failed");
                }
            });
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int cap = maxHeight;
            if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                cap = Math.min(cap, MeasureSpec.getSize(heightSpec));
            }
            try {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(Math.max(1, cap), MeasureSpec.AT_MOST));
                if (getMeasuredHeight() <= 0) throw new IllegalStateException("native host measured empty");
            } catch (Throwable error) {
                setMeasuredDimension(MeasureSpec.getSize(widthSpec), 1);
                renderingFailure("measure", error);
            }
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            if (renderingFailed) return;
            try {
                super.onLayout(changed, left, top, right, bottom);
                if (layoutLogCount < 6 && (lastLayoutWidth != getWidth() || lastLayoutHeight != getHeight())
                        && getWidth() > 0 && getHeight() > 0 && banner == this) {
                    lastLayoutWidth = getWidth();
                    lastLayoutHeight = getHeight();
                    layoutLogCount++;
                    int[] screen = new int[2];
                    getLocationOnScreen(screen);
                    event(notificationKey, "native onLayout width=" + getWidth() + " height=" + getHeight()
                            + " screenX=" + screen[0] + " screenY=" + screen[1]
                            + " display=" + displayId + " attached=" + isAttachedToWindow()
                            + " layoutPass=" + layoutLogCount + " " + (render == null ? "" : render.layoutSummary()));
                }
            } catch (Throwable error) { renderingFailure("layout", error); }
        }

        @Override public void draw(Canvas canvas) {
            if (!canDraw()) return;
            try { super.draw(canvas); }
            catch (Throwable error) { renderingFailure("draw", error); }
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            // A background-free FrameLayout can be drawn through dispatchDraw directly.
            if (!canDraw()) return;
            try {
                super.dispatchDraw(canvas);
                if (!drawnLogged && getWidth() > 0 && getHeight() > 0 && render != null) {
                    drawnLogged = true;
                    event(notificationKey, "native draw source=" + safe(render.source, 240)
                            + " width=" + getWidth() + " height=" + getHeight()
                            + " shown=" + isShown() + "; not proof of unobscured pixels");
                }
            } catch (Throwable error) { renderingFailure("draw", error); }
        }

        private boolean canDraw() {
            if (banner != this || removalPending || renderingFailed) return false;
            String blocked = displayBlockedReason();
            if (blocked == null) return true;
            mainHandler().post(() -> { if (banner == this) dismissMain(blocked); });
            return false;
        }

        void cancelNativeGesture() {
            bodyTap.cancel();
            long now = SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
            try { super.dispatchTouchEvent(cancel); }
            catch (Throwable error) { renderingFailure("cancel stale native touch", error); }
            finally { cancel.recycle(); }
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (banner != this || removalPending || renderingFailed) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                dismiss("outside-touch");
                return true;
            }
            String blocked = displayBlockedReason();
            if (blocked != null) { dismiss(blocked); return true; }
            // A superseded frame may remain visible briefly while native binding catches up,
            // but its old PendingIntents must never accept a new gesture.
            if (updateScheduled || nativeGraceDeadline != 0L || !renderer.isCurrent(render, currentSbn)) {
                cancelNativeGesture();
                return true;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                extendDeadline(INTERACTION_MS);
            }
            try { return super.dispatchTouchEvent(event); }
            catch (Throwable error) {
                renderingFailure("native touch", error);
                return true;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            // ViewGroup.dispatchTouchEvent reaches here only if no native child owns the gesture.
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    bodyTap.begin(render, event.getX(), event.getY(), event.getEventTime());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    bodyTap.move(event.getX(), event.getY());
                    break;
                case MotionEvent.ACTION_UP:
                    if (event.getX() >= 0 && event.getX() < getWidth()
                            && event.getY() >= 0 && event.getY() < getHeight()
                            && bodyTap.finish(render, event.getX(), event.getY(), event.getEventTime())) {
                        // Synchronous delivery avoids Android's posted click reaching a newer frame.
                        performClick();
                    }
                    bodyTap.cancel();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_OUTSIDE:
                    bodyTap.cancel();
                    break;
            }
            return true;
        }

        @Override public boolean performClick() {
            // The listener validates current identity for touch and accessibility invocations.
            return super.performClick();
        }

        @Override protected void onAttachedToWindow() {
            try {
                super.onAttachedToWindow();
                // Child dispatchAttach completes after this callback; invoke native setup later.
                mainHandler().post(attachedCallback);
            } catch (Throwable error) { renderingFailure("host attach", error); }
        }

        @Override protected void onConfigurationChanged(Configuration configuration) {
            try { super.onConfigurationChanged(configuration); }
            catch (Throwable error) { fail(notificationKey, "host configuration callback", error); }
            if (banner == this) dismiss("view-configuration-changed");
        }

        @Override protected void onDetachedFromWindow() {
            try { super.onDetachedFromWindow(); }
            catch (Throwable error) { fail(notificationKey, "host detach callback", error); }
            mainHandler().removeCallbacks(attachedCallback);
            if (banner == this && !removalPending) mainHandler().post(() -> {
                if (banner == this && !removalPending) dismissMain("window-detached");
            });
        }
    }

    private static int dp(Context context, int value) {
        return Math.max(1, Math.round(value * context.getResources().getDisplayMetrics().density));
    }

    private static String safe(String value, int max) {
        if (value == null) return "";
        String text = value.trim();
        if (text.length() > max) {
            int end = max;
            if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            text = text.substring(0, end);
        }
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    private void event(String key, String message) {
        synchronized (this) {
            long now = SystemClock.uptimeMillis();
            if (now - logPeriod >= 10_000L) { logPeriod = now; logCount = 0; }
            if (++logCount > 48) return;
        }
        try { logger.log("FocusBanner key=" + safe(key, 512) + " " + message); }
        catch (Throwable loggingError) { Log.e("FocusRestore", "FocusBanner logger failed", loggingError); }
    }

    private void fail(String stage, Throwable error) { fail(currentKey, stage, error); }

    private void fail(String key, String stage, Throwable error) {
        try { logger.error("FocusBanner key=" + safe(key, 512) + " " + stage, error); }
        catch (Throwable loggingError) { Log.e("FocusRestore", "FocusBanner error logger failed", loggingError); }
    }

    private static final class Geometry { int width; int left; int top; int maxHeight; }

    private static final class Prepared {
        final NativeFocusTemplateRenderer.Render render;
        final Geometry geometry;
        final int measuredHeight;

        Prepared(NativeFocusTemplateRenderer.Render render, Geometry geometry, int measuredHeight) {
            this.render = render;
            this.geometry = geometry;
            this.measuredHeight = measuredHeight;
        }
    }
}
