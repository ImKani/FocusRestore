/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.*;

public class OS3ClockVisibilityTest {
    private static final int VISIBLE = 0, INVISIBLE = 4, GONE = 8;

    /** 模拟 ROM 独立的 policy/multitask 状态，以及 setPolicyVisibility 同值早退。 */
    private static final class Host implements OS3ClockVisibility.Host {
        boolean nativeShown = true;
        int policy = VISIBLE, multitask = VISIBLE;
        int clock = VISIBLE, seat = VISIBLE, line = VISIBLE;
        float alpha = 1f;
        float translation = 0f;
        boolean hideAnimationPending;
        boolean failReconcile, failHide;
        int restores;

        @Override public boolean nativeClockShown() { return nativeShown; }
        @Override public void hideForFocus() {
            hideAnimationPending = false;
            clock = GONE;
            if (failHide) throw new IllegalStateException("partial hide");
            seat = line = GONE;
        }
        @Override public void showNativeClock() {
            restores++;
            hideAnimationPending = false;
            setPolicy(VISIBLE);
            seat = line = VISIBLE;
            alpha = 1f;
            translation = 0f;
        }
        @Override public void hideNativeClock() {
            restores++;
            hideAnimationPending = false;
            setPolicy(INVISIBLE);
            seat = line = INVISIBLE;
            alpha = 0f;
            translation = 0f;
        }
        private void setPolicy(int visibility) {
            if (policy == visibility) return;
            policy = visibility;
            composeVisibility();
        }
        private void composeVisibility() {
            clock = policy == GONE || multitask == GONE ? GONE
                    : policy == INVISIBLE || multitask == INVISIBLE ? INVISIBLE : VISIBLE;
        }
        @Override public void reconcileClockVisibility() {
            if (failReconcile) throw new IllegalStateException("interface temporarily unavailable");
            composeVisibility();
        }
    }

    private static void assertVisible(Host host) {
        assertEquals(VISIBLE, host.clock);
        assertEquals(VISIBLE, host.seat);
        assertEquals(VISIBLE, host.line);
        assertEquals(1f, host.alpha, 0f);
        assertEquals(0f, host.translation, 0f);
    }

    @Test public void normalToPausedMediaRestoresWithoutVisibilityToggle() {
        OS3ClockVisibility state = new OS3ClockVisibility();
        Host host = new Host();
        assertTrue(state.update(true, host));
        host.alpha = 0f;
        host.translation = -30f;
        host.hideAnimationPending = true;
        assertTrue(state.update(false, host));
        assertVisible(host);
        assertFalse(host.hideAnimationPending);
    }

    @Test public void repeatedShowingDoesNotSaveGoneAsRestoreValue() {
        OS3ClockVisibility state = new OS3ClockVisibility();
        Host host = new Host();
        state.update(true, host);
        for (int i = 0; i < 10; i++) assertFalse(state.update(true, host));
        state.update(false, host);
        assertVisible(host);
    }

    @Test public void unchangedNativePolicyStillReconcilesDirectViewHide() {
        OS3ClockVisibility state = new OS3ClockVisibility();
        Host host = new Host();
        state.update(true, host);
        assertEquals(VISIBLE, host.policy);
        assertEquals(GONE, host.clock);
        state.update(false, host);
        assertVisible(host);
    }

    @Test public void focusExitRespectsLatestLockscreenOrDisableDecision() {
        OS3ClockVisibility state = new OS3ClockVisibility();
        Host host = new Host();
        state.update(true, host);
        host.nativeShown = false;
        state.update(false, host);
        assertEquals(INVISIBLE, host.clock);
        assertEquals(INVISIBLE, host.seat);
        assertEquals(0f, host.alpha, 0f);
    }

    @Test public void restoreKeepsIndependentMultitaskPolicy() {
        for (int visibility : new int[]{INVISIBLE, GONE}) {
            OS3ClockVisibility state = new OS3ClockVisibility();
            Host host = new Host();
            state.update(true, host);
            host.multitask = visibility;
            state.update(false, host);
            assertEquals(visibility, host.clock);
            assertEquals(VISIBLE, host.policy);
        }
    }

    @Test public void allTypeAndSettingTransitionsCanReleaseAndReapplyOverride() {
        for (int mask = 0; mask < 8; mask++) {
            OS3ClockVisibility state = new OS3ClockVisibility();
            Host host = new Host();
            // 普通、媒体、设备来回切换，最后无焦点；三个隐藏开关的全部组合。
            for (int kind : new int[]{0, 1, 2, 1, 0, 2, -1}) {
                boolean hide = kind >= 0 && (mask & (1 << kind)) != 0;
                state.update(hide, host);
                if (hide) {
                    assertEquals(GONE, host.clock);
                    assertEquals(GONE, host.seat);
                    assertEquals(GONE, host.line);
                } else assertVisible(host);
            }
        }
    }

    @Test public void nativeShowWhileFocusRemainsHiddenCanBeGuardedAgain() {
        OS3ClockVisibility state = new OS3ClockVisibility();
        Host host = new Host();
        state.update(true, host);
        host.showNativeClock();
        state.update(true, host);
        assertEquals(GONE, host.clock);
        state.update(false, host);
        assertVisible(host);
    }

    @Test public void fragmentRecreationDoesNotInheritOldOverride() {
        OS3ClockVisibility old = new OS3ClockVisibility();
        Host oldHost = new Host();
        old.update(true, oldHost);
        OS3ClockVisibility replacement = new OS3ClockVisibility();
        Host newHost = new Host();
        replacement.update(false, newHost);
        assertEquals(0, newHost.restores);
        assertVisible(newHost);
        assertEquals(GONE, oldHost.clock);
    }

    @Test public void failedRestoreRetainsOverrideForRetry() {
        OS3ClockVisibility state = new OS3ClockVisibility();
        Host host = new Host();
        state.update(true, host);
        host.failReconcile = true;
        try { state.update(false, host); fail("expected interface failure"); }
        catch (IllegalStateException expected) { /* 下一次事件可重试。 */ }
        host.failReconcile = false;
        assertTrue(state.update(false, host));
        assertVisible(host);
        assertFalse(state.update(false, host));
        assertEquals(2, host.restores);
    }

    @Test public void partialHideCanStillBeReleased() {
        OS3ClockVisibility state = new OS3ClockVisibility();
        Host host = new Host();
        host.failHide = true;
        try { state.update(true, host); fail("expected partial hide failure"); }
        catch (IllegalStateException expected) { /* 已接管的视图仍需恢复。 */ }
        host.failHide = false;
        state.update(false, host);
        assertVisible(host);
    }
}
