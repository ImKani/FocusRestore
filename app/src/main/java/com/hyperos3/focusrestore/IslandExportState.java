/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 调用方在每步生成前提供的不可变状态；未知授权始终受限。 */
public final class IslandExportState {
    public enum LockState { LOCKED, UNLOCKED, UNKNOWN }
    public enum ExportAuthorization { AUTHORIZED, UNVERIFIED, DENIED }
    public enum FullConfirmation { CONFIRMED, NOT_CONFIRMED }

    public final LockState lockState;
    public final ExportAuthorization exportAuth;
    public final FullConfirmation fullConfirm;
    public final long sessionGeneration;

    public IslandExportState(LockState lockState, ExportAuthorization exportAuth,
            FullConfirmation fullConfirm, long sessionGeneration) {
        this.lockState = lockState == null ? LockState.UNKNOWN : lockState;
        this.exportAuth = exportAuth == null ? ExportAuthorization.UNVERIFIED : exportAuth;
        this.fullConfirm = fullConfirm == null ? FullConfirmation.NOT_CONFIRMED : fullConfirm;
        this.sessionGeneration = sessionGeneration;
    }
}
