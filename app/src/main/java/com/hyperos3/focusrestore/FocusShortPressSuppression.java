/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/**
 * 长按成立后、同一次手势内的短按抑制。
 *
 * <p>0.37.6 真机日志（OS3，pid 21240）把成因定量了：长按成立后真正的短按来自本次手势的
 * {@code ACTION_UP}，而抬手可能发生在长按成立之后 300ms 到 7.9 秒（用户长按弹出横幅后仍按着不松手）。
 * 因此按固定时间窗（原 600ms）判定必然漏掉，而放宽时间窗又会吞掉用户随后的正常短按。
 *
 * <p>这里改成按手势作用域：长按成立时置位，第一次 {@code ACTION_UP}/{@code ACTION_CANCEL} 消费掉
 * 该标记并立即复位。既不漏掉同一次手势的短按，也不会影响下一次手势。
 */
final class FocusShortPressSuppression {
    private boolean armed;

    /** 长按成立，本次手势后续的短按入口都要失效。 */
    void arm() { armed = true; }

    /** 是否存在待消费的抑制标记；调用方按返回值决定是否拦掉这次 ROM 短按。 */
    boolean consume() {
        if (!armed) return false;
        armed = false;
        return true;
    }

    /** 是否仍有待消费的抑制标记；仅供诊断读取，不改变状态。 */
    boolean armed() { return armed; }

    /** 手势结束（抬手/取消）时复位，避免标记泄漏到下一次手势。 */
    void clear() { armed = false; }
}
