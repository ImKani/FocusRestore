package com.hyperos3.focusrestore;

/** A body-only tap; native child controls never feed this tracker. */
final class BannerBodyTap {
    private final float slopSquared;
    private final long longPressMs;
    private Object generation;
    private float downX;
    private float downY;
    private long downTime;

    BannerBodyTap(float slop, long longPressMs) {
        this.slopSquared = slop * slop;
        this.longPressMs = longPressMs;
    }

    void begin(Object generation, float x, float y, long time) {
        this.generation = generation;
        downX = x;
        downY = y;
        downTime = time;
    }

    void move(float x, float y) {
        float dx = x - downX;
        float dy = y - downY;
        if (dx * dx + dy * dy > slopSquared) cancel();
    }

    boolean finish(Object current, float x, float y, long time) {
        move(x, y);
        long duration = time - downTime;
        boolean tap = generation != null && generation == current
                && duration >= 0 && duration < longPressMs;
        cancel();
        return tap;
    }

    void cancel() { generation = null; }
}
