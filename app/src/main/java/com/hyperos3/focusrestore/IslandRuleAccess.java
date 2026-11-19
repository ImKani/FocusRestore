/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** Android 适配层提供独立观察到的 UID/用户/签名证据。缺失证据即拒绝。 */
final class IslandRuleAccess {
    static final String SYSTEM_UI = "com.android.systemui";
    static final String PLATFORM = "android";

    static boolean allows(int callerUid, int systemUiUid, boolean sameUser, boolean foregroundUnlocked,
            String verifiedCallingPackage, String[] uidPackages, boolean platformSigned) {
        return matchesSystemUi(callerUid, systemUiUid, sameUser, foregroundUnlocked,
                verifiedCallingPackage, platformSigned)
                && uidPackages != null && uidPackages.length == 1 && SYSTEM_UI.equals(uidPackages[0]);
    }

    static boolean allowsCaptureRegistration(int callerUid, int systemUiUid, boolean sameUser,
            boolean foregroundUnlocked, String verifiedCallingPackage, String[] uidPackages,
            boolean platformSigned, boolean platformSystemUid) {
        if (!matchesSystemUi(callerUid, systemUiUid, sameUser, foregroundUnlocked,
                verifiedCallingPackage, platformSigned) || uidPackages == null) return false;
        if (uidPackages.length == 1) return SYSTEM_UI.equals(uidPackages[0]);
        // 共享 UID 上包名仅约束调用归属。注册端点按 Android 系统 UID 整体授信。
        if (!platformSystemUid) return false;
        boolean systemUi = false;
        boolean platform = false;
        for (String name : uidPackages) {
            systemUi |= SYSTEM_UI.equals(name);
            platform |= PLATFORM.equals(name);
        }
        return systemUi && platform;
    }

    static boolean allowsProjectionRead(int callerUid, int systemUiUid, boolean sameUser,
            boolean foregroundUnlocked, String verifiedCallingPackage, String[] uidPackages,
            boolean platformSigned, boolean androidUidMatches) {
        return callerUid == 1000 && androidUidMatches && uidPackages != null && uidPackages.length > 1
                && allowsCaptureRegistration(callerUid, systemUiUid, sameUser, foregroundUnlocked,
                        verifiedCallingPackage, uidPackages, platformSigned, true);
    }

    private static boolean matchesSystemUi(int callerUid, int systemUiUid, boolean sameUser,
            boolean foregroundUnlocked, String verifiedCallingPackage, boolean platformSigned) {
        return callerUid >= 0 && callerUid == systemUiUid && sameUser && foregroundUnlocked && platformSigned
                && SYSTEM_UI.equals(verifiedCallingPackage);
    }

    private IslandRuleAccess() { }
}
