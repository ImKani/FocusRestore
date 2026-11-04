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
        String reason;
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
            if (manager == null) reason=FocusVisibilityPolicy.blocked(power.isInteractive(),locked,false,false,false,false,false,false);
            else {
                int user=sbn.getUserId();
                boolean current=Boolean.TRUE.equals(XposedHelpers.callMethod(manager,"isCurrentProfile",user));
                // USER_ALL按ROM当前用户合成资料锁定状态，不能把-1交给isUserRunning。
                int resolvedUser=user == -1 ? XposedHelpers.getIntField(manager,"mCurrentUserId") : user;
                boolean available=current && Boolean.TRUE.equals(XposedHelpers.callMethod(manager,"isProfileAvailable",resolvedUser));
                boolean profilePublic=Boolean.TRUE.equals(XposedHelpers.callMethod(manager,"isLockscreenPublicMode",resolvedUser));
                boolean hidden=false, protectedContent=false;
                if (os4 && power.isInteractive() && !locked && current && available && !profilePublic) {
                    if (keyguardProvider == null || sensitiveController == null || secretFilter == null) return reportReason(key,"native-privacy-policy-unavailable");
                    hidden=Boolean.TRUE.equals(XposedHelpers.callMethod(keyguardProvider,"shouldHideNotification",entry));
                    protectedContent=Boolean.TRUE.equals(XposedHelpers.callMethod(sensitiveController,"shouldProtectNotification",entry))
                            || Boolean.TRUE.equals(XposedHelpers.callMethod(secretFilter,"shouldFilterOut",entry,0L));
                }
                reason=FocusVisibilityPolicy.blocked(power.isInteractive(),locked,true,current,available,profilePublic,hidden,protectedContent);
            }
            errorLogged=false;
        } catch (Throwable error) {
            if (!errorLogged) { errorLogged=true; logger.error("visibility evaluation",error); }
            reason="privacy-check-failed";
        }
        return reportReason(key,reason);
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
