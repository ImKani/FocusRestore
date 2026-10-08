/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** 观察正常分发，只有长按成立后才取消子视图的点击，保留原短按路径。 */
final class FocusLongPressGesture implements View.OnAttachStateChangeListener {
    interface ActionFactory { Runnable captureAction(); }
    interface FailureHandler { void error(String stage, Throwable failure); }

    private final View host;
    private final FailureHandler failures;
    private final ActionFactory factory;
    private final FocusLongPressState state = new FocusLongPressState();
    private final int slop;
    private Runnable action;
    private long downTime;
    private boolean sendingCancel;
    private final Runnable timeout = this::fire;

    FocusLongPressGesture(View host, ActionFactory factory, FailureHandler failures) {
        this.host = host;
        this.factory = factory;
        this.failures = failures;
        slop = ViewConfiguration.get(host.getContext()).getScaledTouchSlop();
        host.addOnAttachStateChangeListener(this);
    }

    boolean onEvent(MotionEvent event, boolean enabled, long timeoutMillis) {
        if (sendingCancel) return false;
        int type = event.getActionMasked();
        if (type == MotionEvent.ACTION_DOWN) {
            reset();
            if (enabled && event.getPointerCount() == 1) {
                try { action = factory.captureAction(); }
                catch (Throwable failure) { failures.error("long press capture", failure); }
                if (action != null) {
                    downTime = event.getDownTime();
                    state.begin(event.getX(), event.getY());
                    if (!host.postDelayed(timeout, Math.max(200L, Math.min(10000L, timeoutMillis)))) {
                        reset();
                    }
                }
            }
        } else if (!enabled || type == MotionEvent.ACTION_POINTER_DOWN) {
            cancelPending();
        } else if (type == MotionEvent.ACTION_MOVE) {
            state.move(event.getX(), event.getY(), slop);
            if (!state.pending()) cancelPending();
        }
        boolean consumed = state.consumed();
        if (type == MotionEvent.ACTION_UP || type == MotionEvent.ACTION_CANCEL) reset();
        return consumed;
    }

    boolean tracking() { return state.pending() || state.consumed(); }

    private void fire() {
        Runnable captured = action;
        if (!host.isAttachedToWindow() || !host.isShown() || captured == null) {
            reset();
            return;
        }
        if (!state.fire()) return;
        action = null;
        // 给已收到 DOWN 的 ROM/RemoteViews 子视图发 CANCEL，避免抬手打开横幅或执行其按钮。
        MotionEvent cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
        sendingCancel = true;
        try { host.dispatchTouchEvent(cancel); }
        catch (Throwable failure) { failures.error("long press cancel dispatch", failure); }
        finally {
            sendingCancel = false;
            cancel.recycle();
        }
        try { host.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); }
        catch (Throwable failure) { failures.error("long press haptic feedback", failure); }
        try { captured.run(); }
        catch (Throwable failure) { failures.error("long press action", failure); }
    }

    private void cancelPending() {
        host.removeCallbacks(timeout);
        state.cancelPending();
        action = null;
    }

    void invalidateTarget() { cancelPending(); }

    void reset() {
        cancelPending();
        state.reset();
    }

    @Override public void onViewAttachedToWindow(View view) { }
    @Override public void onViewDetachedFromWindow(View view) { reset(); }
}
