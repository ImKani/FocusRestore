/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 保留 ROM 对外公布的设备能力，SystemUI 内部的两层岛屏蔽和视图屏蔽继续生效。
 * 来源：作者授权 APK MCP http://192.168.31.165:8787/mcp，只读 SystemUI
 * 17.03.260226.r/code202602260 的 DynamicFeatureConfig.<clinit>、
 * SystemUIApplicationImpl.onCreate、MiuiVendorServices$1.onUserChanged。
 * 原生 FEATURE_DYNAMIC_ISLAND 来自 getBoolean("feature.island.debug", true)，后两处把该字段
 * 写入 persist.sys.feature.island；小爱8.3.30.4420的lw0.n.isSupportIsland读取这个全局属性。
 * 私有接口具体版权所有者及许可未确认；仅依据接口与属性事实独立实现，不复制 APK 代码。
 */
final class IslandCapabilityPublication {
    interface Host {
        boolean localSuppression();
        void debug(String message);
        void error(String stage, Throwable failure);
    }

    private final Host host;
    private Class<?> properties;
    private Boolean nativeCapability;
    private boolean setterReady;
    private long writes;
    private String lastRequested = "none";
    private String lastEffective = "none";

    IslandCapabilityPublication(Host host) { this.host = host; }

    void install(ClassLoader loader) {
        try {
            properties = XposedHelpers.findClass("android.os.SystemProperties", loader);
            // 必须先于模块的 getBoolean 覆盖和字段禁用读取，不能拿已被模块改过的 false 当原生值。
            Class<?> config = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.DynamicFeatureConfig", loader);
            nativeCapability = XposedHelpers.getStaticBooleanField(config, "FEATURE_DYNAMIC_ISLAND");
            XposedHelpers.findAndHookMethod(properties, "set", String.class, String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!IslandCapabilityPolicy.DEVICE_CAPABILITY.equals(param.args[0])) return;
                    boolean publisher = IslandCapabilityPolicy.isPublisher(new Throwable().getStackTrace());
                    if (!publisher) return;
                    String requested = (String) param.args[1];
                    String effective = IslandCapabilityPolicy.publishedValue(host.localSuppression(), nativeCapability,
                            (String) param.args[0], requested, true);
                    param.setObjectExtra("focusrestore.island.requested", requested);
                    param.args[1] = effective;
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object requested = param.getObjectExtra("focusrestore.island.requested");
                    if (!(requested instanceof String)) return;
                    synchronized (IslandCapabilityPublication.this) {
                        if (!param.hasThrowable()) writes++;
                        lastRequested = (String) requested;
                        lastEffective = (String) param.args[1];
                    }
                    if (param.hasThrowable()) host.error("island-capability publish", param.getThrowable());
                    diagnose("publish");
                }
            });
            setterReady = true;
            diagnose("install");
        } catch (Throwable failure) {
            // 安装失败时保留原路径并报告，绝不硬编码全局能力为 true 或主动补写系统属性。
            host.error("island-capability install; leaving ROM publication unchanged", failure);
        }
    }

    synchronized void diagnose(String reason) {
        if (!BuildConfig.DEBUG) return;
        String persisted = "unavailable";
        if (properties != null) try {
            persisted = String.valueOf(XposedHelpers.callStaticMethod(properties, "get",
                    IslandCapabilityPolicy.DEVICE_CAPABILITY, "absent"));
        } catch (Throwable failure) {
            host.error("island-capability read published value", failure);
        }
        host.debug("island-capability stage=" + reason + " native=" + nativeCapability
                + " localSuppression=" + host.localSuppression() + " setterReady=" + setterReady
                + " writes=" + writes + " requested=" + lastRequested + " effective=" + lastEffective
                + " persisted=" + persisted);
    }
}
