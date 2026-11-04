/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 原生判定先于模块排序；未知状态不降级为完整正文。 */
final class FocusVisibilityPolicy {
    static String blocked(boolean interactive, boolean locked, boolean userKnown,
                          boolean currentProfile, boolean profileAvailable,
                          boolean profilePublic, boolean nativeHidden, boolean protectedContent) {
        if (!interactive) return "screen-not-interactive";
        if (locked) return "device-locked";
        if (!userKnown) return "user-policy-unavailable";
        if (!currentProfile) return "other-user";
        if (!profileAvailable) return "profile-unavailable";
        if (profilePublic) return "profile-locked";
        if (nativeHidden) return "native-hidden";
        if (protectedContent) return "screen-share-protected";
        return null;
    }
}
