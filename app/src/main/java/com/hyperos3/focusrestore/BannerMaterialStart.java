/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 首次显示前留出一次稳定遍历；只判断时序，不把Java就绪等同于GPU可见。 */
final class BannerMaterialStart {
    static final int MAX_DEFERRALS = 3;
    static final long MAX_WAIT_MS = 120L;
    private boolean opened;
    private long startedAt = -1L;
    private int deferrals;
    private int lastRevision = -1;
    private int lastWidth, lastHeight;
    private boolean lastReady;

    boolean allowDraw(boolean requested, boolean ready, int revision, int width, int height, long now) {
        if (opened) return true;
        if (!requested) { opened = true; return true; }
        if (startedAt < 0L) startedAt = now;
        boolean stable = ready && lastReady && revision == lastRevision
                && width == lastWidth && height == lastHeight && width > 0 && height > 0;
        // 系统状态迟迟不就绪时不能无限阻止窗口绘制；继续使用原有主题回退与后续监测。
        if (stable || deferrals >= MAX_DEFERRALS || now - startedAt >= MAX_WAIT_MS) {
            opened = true;
            return true;
        }
        lastReady = ready;
        lastRevision = revision;
        lastWidth = width;
        lastHeight = height;
        deferrals++;
        return false;
    }
}
