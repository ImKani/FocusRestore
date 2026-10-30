/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 为被模块屏蔽的岛补齐小爱使用的“未展开 / 收起完成”协议，不恢复原生岛视图。
 *
 * <p>接口事实：用户提供的小爱 8.3.30.4420 的 IslandStateManager.requestCollapse/isExpanded、
 * collapseCompletedReceiver；SystemUI 17.03.260226.r 的 NotificationProviderPublic.call，
 * 系统界面组件 18.3.2.22.0 的 DynamicIslandWindowViewRefactor.listenForExpandedState。
 * 可访问出处：作者本地 APK MCP http://192.168.31.164:8787/mcp。
 * 私有协议具体版权所有者与许可未确认；只独立实现已核实的 action、Bundle 字段与包名协议，
 * 不复制 APK 实现。只适用 OS4 已安装岛内容拦截且两层岛屏蔽开启的场景。
 *
 * <p>展开状态回调与 ISLAND_STATE_CHANGED/swipe_hidden 是两套协议；这里不伪造用户滑动，
 * 不结束小爱服务，不修改其窗口或 SystemUI 持久化展开缓存。
 */
final class SuppressedIslandLifecycle {
    private static final String XIAO_AI = "com.miui.voiceassist";
    private static final String COLLAPSE = "com.miui.action.ACTION_COLLAPSE_ISLAND";
    private static final String EXPANDED_CHANGED = "com.miui.action.ACTION_EXPANDED_STATE_CHANGED";
    private static final String EXPANDED = "islandExpanded";
    private static final String COMPLETION_MARKER = "focusrestore.suppressed_completion";

    interface Host {
        boolean isSuppressed();
        void debug(String message);
        void error(String stage, Throwable failure);
    }

    private final Host host;
    private int queryHooks;
    private boolean receiverReady;
    private volatile long requests;
    private volatile long replies;

    SuppressedIslandLifecycle(Host host) {
        this.host = host;
    }

    // SystemUI 进程生命周期注册一次；不绑定通知/View，通知移除不会排队复活旧对象。
    void install(ClassLoader loader, Context context, Handler mainHandler) {
        for (String name : new String[]{
                "com.android.systemui.statusbar.notification.NotificationProviderPublic",
                "com.android.systemui.statusbar.notification.NotificationProvider"}) {
            Class<?> provider = FocusReflection.findClass(loader, name);
            if (provider == null) {
                host.debug("island-lifecycle stage=provider-install class=" + name + " hooks=0 missing=true");
                continue;
            }
            try {
                int count = XposedBridge.hookAllMethods(provider, "call", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        // 先运行原 Provider，保留其他方法及其调用方检查，也不吞掉原异常。
                        if (param.hasThrowable() || !host.isSuppressed()
                                || param.args.length != 3
                                || !"getIslandExpanded".equals(param.args[0])
                                || !(param.args[2] instanceof Bundle)
                                || !(param.getResult() instanceof Bundle)) return;
                        try {
                            Bundle extras = (Bundle) param.args[2];
                            if (!SuppressedIslandPolicy.overridesQuery(host.isSuppressed(),
                                    (String) param.args[0], extras.getString("package"))) return;
                            Bundle original = (Bundle) param.getResult();
                            Bundle result = new Bundle(original);
                            result.putBoolean(EXPANDED, false);
                            param.setResult(result);
                            host.debug("island-lifecycle stage=query package=" + XIAO_AI
                                    + " originalExpanded=" + original.getBoolean(EXPANDED, false)
                                    + " effectiveExpanded=false provider=" + provider.getSimpleName());
                        } catch (Throwable failure) {
                            host.error("island-lifecycle query; keeping ROM result", failure);
                        }
                    }
                }).size();
                queryHooks += count;
                host.debug("island-lifecycle stage=provider-install class=" + name + " hooks=" + count);
            } catch (Throwable failure) {
                host.error("island-lifecycle provider-install " + name, failure);
            }
        }
        try {
            IntentFilter filter = new IntentFilter(COLLAPSE);
            if (BuildConfig.DEBUG) filter.addAction(EXPANDED_CHANGED);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null) return;
                    try {
                        if (EXPANDED_CHANGED.equals(intent.getAction())) {
                            host.debug("island-lifecycle stage=expanded-event expanded="
                                    + intent.getBooleanExtra(EXPANDED, false)
                                    + " moduleReply=" + intent.getBooleanExtra(COMPLETION_MARKER, false));
                            return;
                        }
                        if (!COLLAPSE.equals(intent.getAction())) return;
                        requests++;
                        boolean suppressed = host.isSuppressed();
                        host.debug("island-lifecycle stage=collapse-request request=" + requests
                                + " suppressed=" + suppressed);
                        if (!suppressed) return;
                        // 原生收起请求不携带调用包名。回复仅发给本次兼容的小爱，避免改变其他应用状态。
                        // onReceive 由主 Handler 调度；不截断原生接收器，不排队延迟补偿。
                        Intent completion = new Intent(EXPANDED_CHANGED).setPackage(XIAO_AI)
                                .putExtra(EXPANDED, false).putExtra(COMPLETION_MARKER, true);
                        receiverContext.sendBroadcast(completion);
                        replies++;
                        host.debug("island-lifecycle stage=collapse-reply request=" + requests
                                + " expanded=false target=" + XIAO_AI + " sent=true");
                    } catch (Throwable failure) {
                        host.error("island-lifecycle receive; native path unchanged", failure);
                    }
                }
            };
            // APK 发送请求时的 NOTIFICATION 是接收者权限，不能反过来要求小爱持有该权限。
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, null, mainHandler, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter, null, mainHandler);
            }
            receiverReady = true;
        } catch (Throwable failure) {
            host.error("island-lifecycle receiver-install; no completion bridge", failure);
        }
        diagnose("install");
    }

    void diagnose(String reason) {
        host.debug("island-lifecycle stage=snapshot reason=" + reason
                + " version=" + BuildConfig.VERSION_NAME + " code=" + BuildConfig.VERSION_CODE
                + " debug=" + BuildConfig.DEBUG + " pid=" + android.os.Process.myPid()
                + " suppressed=" + host.isSuppressed() + " queryHooks=" + queryHooks
                + " receiverReady=" + receiverReady + " requests=" + requests + " replies=" + replies);
    }
}
