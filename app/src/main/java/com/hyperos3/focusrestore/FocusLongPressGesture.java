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
    private float downX;
    private float downY;
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
                    downX = event.getX();
                    downY = event.getY();
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
    boolean consumed() { return state.consumed(); }

    /**
     * 长按成立后的第一次抬手取走"吞掉"标记。
     *
     * <p>调用方必须用返回值消费这次 ACTION_UP：否则长按已执行，ROM/子视图仍会把同一次抬手当成
     * 短按，出现"长按和点击一起触发"。标记由 {@link FocusLongPressState#consumeRelease()} 一次性取走，
     * 不会影响下一次手势。
     */
    boolean consumeRelease() { return state.consumeRelease(); }

    private void fire() {
        Runnable captured = action;
        if (!host.isAttachedToWindow() || !host.isShown() || captured == null) {
            reset();
            return;
        }
        if (!state.fire()) return;
        action = null;
        // 给已收到 DOWN 的 ROM/RemoteViews 子视图发 CANCEL，避免抬手打开横幅或执行其按钮。
        // 坐标沿用本次按下的位置：部分子视图按坐标判定手势归属，原地 (0,0) 的取消可能被它们忽略。
        MotionEvent cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL, downX, downY, 0);
        sendingCancel = true;
        try { host.dispatchTouchEvent(cancel); }
        catch (Throwable failure) { failures.error("long press cancel dispatch", failure); }
        finally {
            sendingCancel = false;
            cancel.recycle();
        }
        try { host.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); }
        catch (Throwable failure) { failures.error("long press haptic feedback", failure); }
        // 记录长按成立时刻：调用方随后会吞掉这次抬手，日志可用于确认"抬手的点击没有发生"。
        FocusBannerController.onLongPressFired();
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
