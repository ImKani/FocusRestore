/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;
import java.util.LinkedHashMap;
import java.util.Map;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 来源：作者提供的OS3 16.03.251211.r、OS4 17.03.260226.r SystemUI，
 * MT只读MCP http://192.168.31.20:8787/mcp，2026-11-04。
 * NotificationLockscreenUserManagerImpl、KeyguardNotificationVisibilityProviderImpl、
 * SensitiveContentCoordinatorImpl与SensitiveNotificationProtectionControllerImpl。
 * 小米/Android上游具体版权所有者和私有实现许可未确认；只依接口事实独立接入，不复制实现。
 */
final class RomFocusVisibility {
    interface Logger { void error(String stage, Throwable error); void log(String message); }
    private final Context context;
    private final ClassLoader loader;
    private final Handler handler;
    private final boolean os4;
    private final Runnable invalidated;
    private final Logger logger;
    private final HookInstaller installer = new HookInstaller();
    private final Map<String, String> lastReasons = new LinkedHashMap<>();
    private volatile Object userManager;
    private volatile Object keyguardProvider;
    private volatile Object sensitiveController;
    private volatile Object secretFilter;
    private boolean queued;
    private boolean installed;
    private boolean errorLogged;

    RomFocusVisibility(Context context, ClassLoader loader, Handler handler, boolean os4,
                       Runnable invalidated, Logger logger) {
        this.context=context; this.loader=loader; this.handler=handler; this.os4=os4;
        this.invalidated=invalidated; this.logger=logger;
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    void install() {
        if (installed) return;
        capture("com.android.systemui.statusbar.NotificationLockscreenUserManagerImpl", owner -> userManager=owner);
        // 模块接入晚于构造时，从下一次原生查询补获实例；同实例查询不重复触发重算。
        captureOnCall("com.android.systemui.statusbar.NotificationLockscreenUserManagerImpl", "isCurrentProfile",
                owner -> { if (userManager != owner) { userManager=owner; changed(); } });
        observe("com.android.systemui.statusbar.NotificationLockscreenUserManagerImpl", "notifyNotificationStateChanged");
        observe("com.android.systemui.statusbar.NotificationLockscreenUserManagerImpl", "updateCurrentProfilesCache");
        if (os4) {
            capture("com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProviderImpl", owner -> {
                keyguardProvider=owner;
                Object manager=field(owner,"lockscreenUserManager");
                if (manager != null) userManager=manager;
            });
            capture("com.android.systemui.statusbar.notification.collection.coordinator.SensitiveContentCoordinatorImpl", owner -> {
                sensitiveController=field(owner,"sensitiveNotificationProtectionController");
                secretFilter=field(owner,"screenshareSecretFilter");
            });
            captureOnCall("com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProviderImpl", "shouldHideNotification", owner -> {
                if (keyguardProvider != owner) {
                    keyguardProvider=owner;
                    Object manager=field(owner,"lockscreenUserManager");
                    if (manager != null) userManager=manager;
                    changed();
                }
            });
            captureOnCall("com.android.systemui.statusbar.notification.collection.coordinator.SensitiveContentCoordinatorImpl", "onBeforeRenderList$1", owner -> {
                Object protection=field(owner,"sensitiveNotificationProtectionController"), filter=field(owner,"screenshareSecretFilter");
                if (protection != sensitiveController || filter != secretFilter) {
                    sensitiveController=protection; secretFilter=filter; changed();
                }
            });
            observe("com.android.systemui.statusbar.notification.collection.coordinator.SensitiveContentCoordinatorImpl", "onDynamicPrivacyChanged$1");
            observe("com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProviderImpl", "access$notifyStateChanged");
            observe("com.android.systemui.statusbar.policy.SensitiveNotificationProtectionControllerImpl", "updateProjectionStateAndNotifyListeners");
        }
        IntentFilter filter=new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT); filter.addAction("android.intent.action.USER_SWITCHED");
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE); filter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED);
        BroadcastReceiver receiver=new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { changed(); }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(receiver, filter, null, handler);
            installed=true; // 进程级监听随SystemUI进程结束，不持有任何View/Entry。
        } catch (Throwable error) { logger.error("visibility receiver installation", error); }
    }

    private interface Owner { void accept(Object owner); }
    boolean ready() { return userManager != null && (!os4 || (keyguardProvider != null && sensitiveController != null && secretFilter != null)); }
    int installedHandles() { return installer.count(); }
    int failedHooks() { return installer.failedCount(); }
    private void captureOnCall(String name, String method, Owner receiver) {
        try {
            installer.allMethods(FocusReflection.findClass(loader,name),method,new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) receiver.accept(param.thisObject);
                }
            });
        } catch (Throwable error) { logger.error("visibility instance " + name + "#" + method,error); }
    }
    private void capture(String name, Owner receiver) {
        try {
            installer.constructors(FocusReflection.findClass(loader,name), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    receiver.accept(param.thisObject); changed();
                }
            });
        } catch (Throwable error) { logger.error("visibility capture " + name,error); }
    }

    private void observe(String name, String method) {
        try {
            installer.allMethods(FocusReflection.findClass(loader,name),method,new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) changed();
                }
            });
        } catch (Throwable error) { logger.error("visibility observe " + name + "#" + method,error); }
    }

    private synchronized void changed() {
        if (queued) return;
        queued=true;
        if (!handler.post(() -> {
            synchronized (RomFocusVisibility.this) { queued=false; }
            invalidated.run();
        })) queued=false;
    }

    String blocked(Object entry) {
        String key="unknown";
        try {
            Object expanded=field(entry,"mSbn");
            if (!(expanded instanceof StatusBarNotification)) return reportReason(key,"notification-unavailable");
            StatusBarNotification sbn=(StatusBarNotification)expanded;
            key=sbn.getKey();
            KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
            PowerManager power=context.getSystemService(PowerManager.class);
            if (keyguard == null || power == null) return reportReason(key,"privacy-services-unavailable");
            boolean locked=keyguard.isKeyguardLocked() || keyguard.isDeviceLocked();
            Object manager=userManager;
            Object visibility=keyguardProvider;
            Object protection=sensitiveController;
            Object filter=secretFilter;
            return blocked(key, locked, new Checks() {
                @Override public boolean interactive() { return power.isInteractive(); }
                @Override public boolean hasUserManager() { return manager != null; }
                @Override public int notificationUser() { return sbn.getUserId(); }
                @Override public Object currentUser() {
                    if (os4) return XposedHelpers.getObjectField(manager,"mCurrentUserId");
                    return XposedHelpers.getIntField(manager,"mCurrentUserId");
                }
                @Override public Object profile(String method, int user) {
                    return XposedHelpers.callMethod(manager,method,user);
                }
                @Override public boolean hasNativePolicy() {
                    return visibility != null && protection != null && filter != null;
                }
                @Override public Object shouldHide() {
                    return XposedHelpers.callMethod(visibility,"shouldHideNotification",entry);
                }
                @Override public Object shouldProtect() {
                    return XposedHelpers.callMethod(protection,"shouldProtectNotification",entry);
                }
                @Override public Object shouldFilterOut() {
                    return XposedHelpers.callMethod(filter,"shouldFilterOut",entry,0L);
                }
            });
        } catch (Throwable error) {
            return reportReason(key,evaluationFailed(error));
        }
    }

    interface Checks {
        boolean interactive();
        boolean hasUserManager();
        int notificationUser();
        Object currentUser();
        Object profile(String method, int user);
        boolean hasNativePolicy();
        Object shouldHide();
        Object shouldProtect();
        Object shouldFilterOut();
    }

    String blocked(String key, boolean locked, Checks checks) {
        String reason;
        try {
            reason = os4 ? blockedOs4(locked, checks) : blockedOs3(locked, checks);
            errorLogged=false;
        } catch (Throwable error) {
            reason=evaluationFailed(error);
        }
        return reportReason(key,reason);
    }

    private static String blockedOs3(boolean locked, Checks checks) {
        if (!checks.hasUserManager()) {
            return FocusVisibilityPolicy.blocked(checks.interactive(),locked,false,false,false,false,false,false);
        }
        int user = checks.notificationUser();
        boolean current = Boolean.TRUE.equals(checks.profile("isCurrentProfile", user));
        int resolvedUser = user == -1 ? (Integer) checks.currentUser() : user;
        boolean available = current && Boolean.TRUE.equals(checks.profile("isProfileAvailable", resolvedUser));
        boolean profilePublic = Boolean.TRUE.equals(checks.profile("isLockscreenPublicMode", resolvedUser));
        return FocusVisibilityPolicy.blocked(checks.interactive(),locked,true,current,available,profilePublic,false,false);
    }

    private static String blockedOs4(boolean locked, Checks checks) {
        // 本次交互状态决定整次判定，不能跳过原生检查后再采样出允许结果。
        if (!checks.interactive()) return "screen-not-interactive";
        if (locked) return "device-locked";
        if (!checks.hasUserManager()) return "user-policy-unavailable";

        int user = checks.notificationUser();
        if (user < -1) return "notification-user-unavailable";
        if (!profileFlag(checks,"isCurrentProfile",user)) return "other-user";
        // USER_ALL 仍交给 ROM 判定是否为当前资料，后续查询必须使用已解析的当前用户。
        int resolvedUser = user;
        if (user == -1) {
            Object currentUser = checks.currentUser();
            if (!(currentUser instanceof Integer) || (Integer) currentUser < 0) {
                return "notification-user-unavailable";
            }
            resolvedUser = (Integer) currentUser;
        }
        if (!profileFlag(checks,"isProfileAvailable",resolvedUser)) return "profile-unavailable";
        if (profileFlag(checks,"isLockscreenPublicMode",resolvedUser)) return "profile-locked";
        if (!checks.hasNativePolicy()) return "native-privacy-policy-unavailable";
        if (requiredBoolean(checks.shouldHide(),"shouldHideNotification")) return "native-hidden";
        if (requiredBoolean(checks.shouldProtect(),"shouldProtectNotification")
                || requiredBoolean(checks.shouldFilterOut(),"shouldFilterOut")) {
            return "screen-share-protected";
        }
        return null;
    }

    private static boolean profileFlag(Checks checks, String method, int user) {
        return requiredBoolean(checks.profile(method,user),method);
    }

    private static boolean requiredBoolean(Object value, String method) {
        if (!(value instanceof Boolean)) {
            throw new IllegalStateException("OS4 privacy boolean unavailable: " + method);
        }
        return (Boolean) value;
    }

    private String evaluationFailed(Throwable error) {
        if (!errorLogged) { errorLogged=true; logger.error("visibility evaluation",error); }
        return "privacy-check-failed";
    }

    private String reportReason(String key,String reason) {
        synchronized (lastReasons) {
            String previous=lastReasons.put(key,reason == null ? "allowed" : reason);
            if (lastReasons.size()>128) lastReasons.remove(lastReasons.keySet().iterator().next());
            if (reason != null && !reason.equals(previous)) logger.log("focus rejected key=" + key + " reason=" + reason);
        }
        return reason;
    }

    private static Object field(Object owner,String name) {
        if (owner == null) return null;
        try { return XposedHelpers.getObjectField(owner,name); }
        catch (Throwable ignored) { return null; } // 缺少必要对象由blocked显式拒绝。
    }
}
