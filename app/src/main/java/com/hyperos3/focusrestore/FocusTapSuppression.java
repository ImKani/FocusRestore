package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Bounded DOWN snapshots, including misses; never suppresses unrelated later gestures. */
final class FocusTapSuppression {
    private static final long RETAIN_MS = 2000L;
    private static final int MAX_GESTURES = 8;
    private final List<Gesture> gestures = new ArrayList<>();

    boolean hasSnapshot(long downTime, int deviceId, int displayId, long now) {
        prune(now);
        return find(downTime, deviceId, displayId) != null;
    }

    void record(long downTime, int deviceId, int displayId, boolean hit, long now) {
        prune(now);
        // The same event may reach both PhoneStatusBarView and QuickSettingsController.
        if (find(downTime, deviceId, displayId) != null) return;
        if (gestures.size() == MAX_GESTURES) gestures.remove(0);
        gestures.add(new Gesture(downTime, deviceId, displayId, hit, now));
    }

    boolean blocks(long downTime, int deviceId, int displayId, long now) {
        prune(now);
        Gesture gesture = find(downTime, deviceId, displayId);
        return gesture != null && gesture.hit;
    }

    private Gesture find(long downTime, int deviceId, int displayId) {
        for (Gesture gesture : gestures) {
            if (gesture.downTime == downTime && gesture.deviceId == deviceId
                    && gesture.displayId == displayId) return gesture;
        }
        return null;
    }

    private void prune(long now) {
        Iterator<Gesture> iterator = gestures.iterator();
        while (iterator.hasNext()) {
            long age = now - iterator.next().recordedAt;
            if (age < 0 || age > RETAIN_MS) iterator.remove();
        }
    }

    private static final class Gesture {
        final long downTime;
        final int deviceId;
        final int displayId;
        final boolean hit;
        final long recordedAt;
        Gesture(long downTime, int deviceId, int displayId, boolean hit, long recordedAt) {
            this.downTime = downTime;
            this.deviceId = deviceId;
            this.displayId = displayId;
            this.hit = hit;
            this.recordedAt = recordedAt;
        }
    }
}
