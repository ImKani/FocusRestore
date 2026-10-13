/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 拖动换位使用真实手指位置，不受行显示位置的边界限制。 */
final class FocusIconDragGeometry {
    private FocusIconDragGeometry() { }

    static int targetSlot(float pointerY, float pointerOffset, int draggedHeight,
                          int currentSlot, float[] slotCenters) {
        // 首尾按手指进入半行判定，按住行上沿/下沿也无需越出列表或预留半行空白。
        if (pointerY < slotCenters[0]) return 0;
        if (pointerY > slotCenters[slotCenters.length - 1]) return slotCenters.length - 1;
        // 中间位置仍按拖动行中心换位，保留抓取位置，避免视觉行被边界钳制后无法换位。
        float center = pointerY - pointerOffset + draggedHeight / 2f;
        int slot = currentSlot;
        while (slot > 0 && center < slotCenters[slot - 1]) slot--;
        while (slot < slotCenters.length - 1 && center > slotCenters[slot + 1]) slot++;
        return slot;
    }
}
