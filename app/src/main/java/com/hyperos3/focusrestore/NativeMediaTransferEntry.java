/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.content.Context;
import android.os.Looper;
import android.view.View;

import de.robv.android.xposed.XposedHelpers;

/**
 * 复用 ROM 的妙播入口，不创建面板、复制状态机或移动通知栏已有视图。
 *
 * 接口事实来源：用户 MT MCP http://192.168.31.21:8787/mcp 只读分析：
 * OS3 SystemUI 16.03.251211.r / versionCode 202501210；
 * OS4 SystemUI 17.03.260226.r / versionCode 202602260。
 * MiuiMediaViewControllerImpl.mediaTransferManager 是 dagger.Lazy；
 * MiuiMediaTransferManagerImpl.mModalController 是实际 IModalController 实例，不是 Lazy。
 * 其 mOnClickHandler（MiuiMediaTransferManagerImpl$2）处理 CTA、原生 modal、动画与关闭入口。
 * 具体作者、版权所有者及 ROM 私有实现许可证未确认；仅依据接口独立适配，未复制外部实现。
 */
final class NativeMediaTransferEntry {
    interface Logger {
        void log(String message);
        void error(String stage, Throwable failure);
    }

    private NativeMediaTransferEntry() { }

    /** true 仅表示已分发或原生 modal 正忙，不证明面板可见或路由已转移。 */
    static boolean open(Object mediaViewController, View anchor, Logger logger) {
        if (mediaViewController == null || anchor == null || !anchor.isAttachedToWindow()) {
            logger.log("native MiPlay unavailable reason=controller-or-attached-anchor");
            return false;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            logger.log("native MiPlay unavailable reason=not-main-thread");
            return false;
        }
        Object modal = null;
        boolean dispatchStarted = false;
        try {
            // 原生 listener 从锚点取 Application Context 读取 CTA；不能传只有应用资源的包 Context。
            // 未确认上下文时交给安卓输出回退，不伪造同意，也不显式重复启动设备互联 CTA。
            Context anchorContext = anchor.getContext();
            Context applicationContext = anchorContext == null ? null : anchorContext.getApplicationContext();
            if (applicationContext == null
                    || !"com.android.systemui".equals(applicationContext.getPackageName())) {
                logger.log("native MiPlay unavailable reason=anchor-application-context"
                        + " anchorPackage=" + (anchorContext == null ? "null" : anchorContext.getPackageName())
                        + " applicationPackage=" + (applicationContext == null
                        ? "null" : applicationContext.getPackageName()));
                return false;
            }
            Object lazy = XposedHelpers.getObjectField(mediaViewController, "mediaTransferManager");
            Object manager = lazy == null ? null : XposedHelpers.callMethod(lazy, "get");
            if (manager == null || !XposedHelpers.getBooleanField(manager, "mSupportMiPlayAudio")) {
                logger.log("native MiPlay unavailable reason=transfer-manager-or-audio-support");
                return false;
            }
            modal = XposedHelpers.getObjectField(manager, "mModalController");
            if (modal == null) {
                logger.log("native MiPlay unavailable reason=modal-controller");
                return false;
            }
            // 忙碌时不再启动其他面板，避免原生 modal 与安卓回退窗口重叠。
            if (XposedHelpers.getBooleanField(modal, "isModal")
                    || XposedHelpers.getBooleanField(modal, "isAnimating")) {
                logger.log("native MiPlay request handled reason=modal-busy; visibility unverified");
                return true;
            }
            if (XposedHelpers.getObjectField(modal, "modalWindowView") == null
                    || XposedHelpers.getObjectField(modal, "modalWindowManager") == null) {
                logger.log("native MiPlay unavailable reason=modal-window-not-ready");
                return false;
            }
            Object listener = XposedHelpers.getObjectField(manager, "mOnClickHandler");
            if (!(listener instanceof View.OnClickListener)) {
                logger.log("native MiPlay unavailable reason=click-listener");
                return false;
            }
            // 直接调用原生 listener，不调用 applyMediaTransferView，因此不加入 mViews 或注册回调。
            // CTA 未同意时 listener 自行打开 CTA 页；void 返回只代表分发，不应再叠加安卓回退。
            logger.log("native MiPlay anchor applicationPackage=" + applicationContext.getPackageName()
                    + " contextClass=" + anchorContext.getClass().getName());
            dispatchStarted = true;
            ((View.OnClickListener) listener).onClick(anchor);
        } catch (Throwable failure) {
            logger.error("native MiPlay listener dispatch", failure);
            // 原生 listener 可能先进入 modal 状态再抛异常，不能继续叠加安卓回退窗口。
            if (dispatchStarted && modal != null) {
                try {
                    if (XposedHelpers.getBooleanField(modal, "isModal")
                            || XposedHelpers.getBooleanField(modal, "isAnimating")
                            || XposedHelpers.getBooleanField(modal, "isMiPlayModal")) {
                        logger.log("native MiPlay partial dispatch reason=modal-state-active; visibility unverified");
                        return true;
                    }
                } catch (Throwable stateFailure) {
                    logger.error("native MiPlay partial dispatch state check", stateFailure);
                }
            }
            return false;
        }
        // 分发后诊断失败不能把已接受的请求变成 false，再额外启动安卓输出面板。
        logger.log("native MiPlay listener dispatched; CTA or modal visibility unverified");
        return true;
    }
}
