/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import de.robv.android.xposed.XC_MethodHook;

/** 复用队列，保留原生点击和delegate；操作时重新捕获当前目标。 */
final class FocusQueueAccessibility {
    interface Factory { Target capture(View view); }
    interface Target { boolean current(); void swipe(boolean previous); void automatic(); String description(); }
    private static final int PREVIOUS=View.generateViewId(), NEXT=View.generateViewId(), AUTO=View.generateViewId();
    static void install(HookInstaller installer,Factory factory) throws Throwable {
        installer.exact(View.class,"onInitializeAccessibilityNodeInfo",AccessibilityNodeInfo.class,new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                Target target=factory.capture((View)param.thisObject);
                if (target == null || !target.current()) return;
                AccessibilityNodeInfo node=(AccessibilityNodeInfo)param.args[0];
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(PREVIOUS,"上一个焦点"));
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(NEXT,"下一个焦点"));
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(AUTO,"恢复自动选择焦点"));
                node.setStateDescription(target.description());
            }
        });
        installer.exact(View.class,"performAccessibilityAction",int.class,Bundle.class,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                int action=(Integer)param.args[0];
                if (action != PREVIOUS && action != NEXT && action != AUTO) return;
                Target target=factory.capture((View)param.thisObject);
                if (target == null || !target.current()) { param.setResult(false); return; }
                if (action == AUTO) target.automatic(); else target.swipe(action == PREVIOUS);
                param.setResult(true);
            }
        });
    }
}
