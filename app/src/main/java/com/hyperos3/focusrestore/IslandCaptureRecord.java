/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** C2-3 内存记录；不包含导出、comparison 或可执行通知对象。 */
public final class IslandCaptureRecord {
    public enum State { CAPTURED, LOCKED_PLACEHOLDER, REJECTED, READ_ERROR }
    public enum Reason {
        LOCKED, LOCK_STATE_UNKNOWN, DISABLED, NOT_ALLOWLISTED, AUTH_UNVERIFIED,
        QUEUE_FULL, OVERSIZE, SESSION_STALE, COMPLETED_BUFFER_FULL,
        READ_FAILED, TYPE_MISMATCH, MISSING_FIELD
    }

    public final String id;
    public final long capturedAt;
    public final long sequence;
    public final State state;
    public final Reason reason;
    public final String packageName;
    public final Integer userId;
    public final String notificationKey;
    public final String bodyText;
    public final String rawParamJSON;
    public final String extrasJson;
    final String sourceKey;
    // 内部配额计量，不属于 §3.2 公开记录字段，也不进入任何导出格式。
    final long bodyBytes;

    // 仅捕获器可构造：失败及占位不得顺带保留身份或正文。
    IslandCaptureRecord(long generation, long sequence, long capturedAt, State state,
            Reason reason, String packageName, Integer userId, String notificationKey,
            String bodyText, String rawParamJSON, String extrasJson, long bodyBytes) {
        this(generation, sequence, capturedAt, state, reason, packageName, userId, notificationKey,
                bodyText, rawParamJSON, extrasJson, bodyBytes, null);
    }

    IslandCaptureRecord(long generation, long sequence, long capturedAt, State state,
            Reason reason, String packageName, Integer userId, String notificationKey,
            String bodyText, String rawParamJSON, String extrasJson, long bodyBytes, String sourceKey) {
        this.id = "capture-" + generation + "-" + sequence;
        this.capturedAt = capturedAt;
        this.sequence = sequence;
        this.state = state;
        boolean captured = state == State.CAPTURED;
        this.reason = captured ? null : reason;
        this.packageName = captured ? packageName : null;
        this.userId = captured ? userId : null;
        this.notificationKey = captured ? notificationKey : null;
        this.bodyText = captured ? bodyText : null;
        this.rawParamJSON = captured ? rawParamJSON : null;
        this.extrasJson = captured ? extrasJson : null;
        this.sourceKey = captured ? sourceKey : null;
        this.bodyBytes = captured ? bodyBytes : 0;
    }
}
