package com.hyperos3.focusrestore;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BannerBodyTapTest {
    private final BannerBodyTap tap = new BannerBodyTap(8f, 500L);
    private final Object first = new Object();

    @Test public void shortStationaryBodyTapFiresOnce() {
        tap.begin(first, 10f, 20f, 100L);
        assertTrue(tap.finish(first, 13f, 22f, 180L));
        assertFalse(tap.finish(first, 13f, 22f, 181L));
    }

    @Test public void dragReturningToStartDoesNotOpenNotification() {
        tap.begin(first, 10f, 20f, 100L);
        tap.move(40f, 20f);
        assertFalse(tap.finish(first, 10f, 20f, 180L));
    }

    @Test public void longPressDoesNotTurnIntoBodyClick() {
        tap.begin(first, 10f, 20f, 100L);
        assertFalse(tap.finish(first, 10f, 20f, 600L));
    }

    @Test public void ReplacementRenderCannotReceiveOldGesture() {
        tap.begin(first, 10f, 20f, 100L);
        assertFalse(tap.finish(new Object(), 10f, 20f, 180L));
    }

    @Test public void cancelUsedByUpdatesRemovalAndMultiTouchPreventsClick() {
        tap.begin(first, 10f, 20f, 100L);
        tap.cancel();
        assertFalse(tap.finish(first, 10f, 20f, 180L));
    }

    @Test public void childOwnedGestureWithoutBodyDownDoesNotOpenNotification() {
        assertFalse(tap.finish(first, 10f, 20f, 180L));
    }

    @Test public void malformedTimeIsRejectedAndLaterTapStillWorks() {
        tap.begin(first, 10f, 20f, 100L);
        assertFalse(tap.finish(first, 10f, 20f, 99L));
        tap.begin(first, 10f, 20f, 200L);
        assertTrue(tap.finish(first, 10f, 20f, 250L));
    }
}
