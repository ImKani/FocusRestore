/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 在状态栏根视图观察 DOWN，仅水平滑动接管，保留系统下拉通知栏分发。
 * 50dp 门槛依据用户只读 APK MCP http://192.168.31.21:8787/mcp 中小米插件
 * 17.1.4.71.0 / 18.2.2.2.0 的 island_swipe_threshold 与
 * DynamicIslandTouchInteractor.onTouchEvent；私有协议许可未确认，未复制 ROM 实现。
 */
final class FocusSwipeTouchHandler implements View.OnAttachStateChangeListener {
    interface Target {
        boolean current();
        void swipe(boolean towardsEnd);
    }
    interface Factory {
        Target capture(View root, Rect bounds);
    }
    interface Logger {
        void log(String message);
        void error(String stage, Throwable error);
    }
    interface WidthProvider { int widthPx(View root); }

    private final View root;
    private final Factory factory;
    private final Logger logger;
    private final WidthProvider widthProvider;
    private final FocusSwipeState state = new FocusSwipeState();
    private final Rect lastLocalBounds = new Rect();
    private boolean lastBoundsCustom;
    private Target target;
    private boolean sendingCancel;
    private boolean disallowRequested;

    private FocusSwipeTouchHandler(View root, Factory factory, WidthProvider widthProvider, Logger logger) {
        this.root = root;
        this.factory = factory;
        this.logger = logger;
        this.widthProvider = widthProvider;
        root.addOnAttachStateChangeListener(this);
    }

    static void install(ClassLoader loader, Factory factory, WidthProvider widthProvider, Logger logger) {
        try {
            Class<?> type = FocusReflection.findClass(loader,
                    "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView");
            Method dispatch = type.getMethod("dispatchTouchEvent", MotionEvent.class);
            XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!type.isInstance(param.thisObject)) return;
                    View root = (View) param.thisObject;
                    FocusSwipeTouchHandler handler = get(root);
                    if (handler == null) {
                        handler = new FocusSwipeTouchHandler(root, factory, widthProvider, logger);
                        XposedHelpers.setAdditionalInstanceField(root, "focusRestoreSwipe",
                                new WeakReference<>(handler));
                    }
                    try {
                        if (handler.onEvent((MotionEvent) param.args[0])) param.setResult(true);
                    } catch (Throwable error) {
                        handler.reset();
                        logger.error("focus swipe dispatch; returning to ROM", error);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!type.isInstance(param.thisObject)) return;
                    FocusSwipeTouchHandler handler = get((View) param.thisObject);
                    if (handler == null || handler.sendingCancel) return;
                    if (param.hasThrowable()) handler.reset();
                    else if (handler.state.tracking()) param.setResult(true);
                }
            });
            logger.log("focus horizontal swipe=hooked threshold=50dp scope=status-bar-focus");
        } catch (Throwable error) { logger.error("install focus swipe", error); }
    }

    private static FocusSwipeTouchHandler get(View root) {
        Object extra = XposedHelpers.getAdditionalInstanceField(root, "focusRestoreSwipe");
        Object value = extra instanceof WeakReference ? ((WeakReference<?>) extra).get() : null;
        return value instanceof FocusSwipeTouchHandler ? (FocusSwipeTouchHandler) value : null;
    }

    private boolean onEvent(MotionEvent event) {
        if (sendingCancel) return false;
        int action = event.getActionMasked();
        int slop = ViewConfiguration.get(root.getContext()).getScaledTouchSlop();
        if (action == MotionEvent.ACTION_DOWN) {
            reset();
            if (event.getPointerCount() != 1 || !root.isShown()) return false;
            Rect bounds = recoveryBounds();
            Target captured = factory.capture(root, bounds);
            if (captured == null || !captured.current()
                    || !bounds.contains((int) event.getRawX(), (int) event.getRawY())) return false;
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            lastLocalBounds.set(bounds);
            lastLocalBounds.offset(-location[0], -location[1]);
            lastBoundsCustom = widthProvider.widthPx(root) > 0;
            target = captured;
            state.begin(event.getRawX(), event.getRawY());
            return false;
        }
        if (target == null) return false;
        if (!target.current() || event.getPointerCount() != 1
                || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
            state.cancel();
        }
        boolean wasHorizontal = state.horizontal();
        boolean horizontal = action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP
                ? state.move(event.getRawX(), event.getRawY(), slop) : wasHorizontal;
        if (horizontal && !wasHorizontal) {
            // 取消已接收 DOWN 的通知/长按，防止 UP 同时打开横幅；不合成新的 DOWN。
            sendingCancel = true;
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            try { root.dispatchTouchEvent(cancel); }
            finally { sendingCancel = false; cancel.recycle(); }
            if (root.getParent() != null) {
                root.getParent().requestDisallowInterceptTouchEvent(true);
                disallowRequested = true;
            }
        }
        if (action == MotionEvent.ACTION_UP) {
            Target captured = target;
            float threshold = 50f * root.getResources().getDisplayMetrics().density;
            int direction = state.finish(event.getRawX(), event.getRawY(), slop, threshold);
            reset();
            if (direction != 0 && captured.current()) {
                boolean rtl = root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                captured.swipe(rtl ? direction < 0 : direction > 0);
            }
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_OUTSIDE) {
            reset();
        }
        return horizontal;
    }

    private Rect recoveryBounds() {
        Rect visible = new Rect();
        if (!root.getGlobalVisibleRect(visible)) return visible;
        int[] location = new int[2];
        root.getLocationOnScreen(location);
        int customWidth = widthProvider.widthPx(root);
        if (lastBoundsCustom && customWidth == 0) {
            // 收起期间关闭自定义不能残留扩展区域；实际焦点边界已不可用，回到旧版恢复兜底。
            lastLocalBounds.setEmpty();
            lastBoundsCustom = false;
        }
        if (!lastLocalBounds.isEmpty()) {
            Rect previous = new Rect(lastLocalBounds);
            previous.offset(location[0], location[1]);
            // 关闭自定义时保留原可见边界；开启后跟随当前方向的独立触控宽度。
            int width = FocusTouchWidth.recovery(customWidth, previous.width(), 0);
            if (root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) previous.left = previous.right - width;
            else previous.right = previous.left + width;
            if (previous.intersect(visible)) return previous;
        }
        // 状态栏重建后仍保留收起状态，恢复区取当前时间右侧，限制在当前根视图内。
        int id = root.getResources().getIdentifier("clock", "id", "com.android.systemui");
        View clock = id == 0 ? null : root.findViewById(id);
        Rect clockBounds = new Rect();
        if (clock == null || !clock.getGlobalVisibleRect(clockBounds)) return new Rect();
        int width = FocusTouchWidth.recovery(customWidth, 0,
                Math.round(96f * root.getResources().getDisplayMetrics().density));
        if (root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            visible.right = Math.min(visible.right, clockBounds.left);
            visible.left = Math.max(visible.left, visible.right - width);
        } else {
            visible.left = Math.max(visible.left, clockBounds.right);
            visible.right = Math.min(visible.right, visible.left + width);
        }
        return visible;
    }

    private void reset() {
        state.reset();
        target = null;
        // 仅撤回模块自己的请求；开关关闭或纵向手势时不改 ROM 的父级拦截状态。
        if (disallowRequested && root.getParent() != null) {
            root.getParent().requestDisallowInterceptTouchEvent(false);
        }
        disallowRequested = false;
    }

    @Override public void onViewAttachedToWindow(View view) { }
    @Override public void onViewDetachedFromWindow(View view) { reset(); }
}
