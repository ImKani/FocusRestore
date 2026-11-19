/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;

/** App 只接收通过身份检查的捕获控制通道。 */
public final class IslandCaptureProvider extends ContentProvider {
    static final String AUTHORITY = "com.hyperos3.focusrestore.islandcapture";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/bridge");
    static final String ATTACH = "attach_capture_v1";
    private static final Binder OWNER = new Binder();
    private static final Object REGISTRATION_LOCK = new Object();
    private static volatile BridgeRegistration registration;

    private enum CaptureIdentity { REJECTED, EXCLUSIVE, SHARED_PLATFORM }

    static final class BridgeRegistration {
        final IBinder bridge;
        final int callerUid;
        final boolean saveSource;
        final boolean sharedPlatformUid;

        private BridgeRegistration(IBinder bridge, int callerUid, boolean saveSource, boolean sharedPlatformUid) {
            this.bridge = bridge;
            this.callerUid = callerUid;
            this.saveSource = saveSource;
            this.sharedPlatformUid = sharedPlatformUid;
        }
    }

    static boolean trustedSaveSource(Context app, BridgeRegistration expected) {
        return expected != null && expected.saveSource && currentRegistration(expected.bridge) == expected
                && IslandRuleProvider.trusted(app, expected.callerUid, IslandRuleAccess.SYSTEM_UI);
    }

    static boolean trustedLocalApprovalSource(Context app, BridgeRegistration expected) {
        // 捕获身份只证明可用的预览来源，不授予规则 IPC 身份。
        return expected != null && !expected.saveSource && expected.sharedPlatformUid
                && currentRegistration(expected.bridge) == expected
                && captureIdentity(app, expected.callerUid, IslandRuleAccess.SYSTEM_UI) == CaptureIdentity.SHARED_PLATFORM;
    }

    static IBinder bridge(Context context) {
        BridgeRegistration current = registration;
        if (current != null && current.bridge.isBinderAlive()) return current.bridge;
        requestBridge(context);
        return null;
    }

    static BridgeRegistration currentRegistration(IBinder expected) {
        BridgeRegistration current = registration;
        return current != null && current.bridge == expected && expected.isBinderAlive() ? current : null;
    }

    static void publishForRegistration(BridgeRegistration expected, IslandRuleRepository.ManifestPublication publication)
            throws java.io.IOException {
        // 注册换代与原子替换按此锁排序，身份查询和预览 IPC 在锁外完成。
        synchronized (REGISTRATION_LOCK) {
            if (currentRegistration(expected.bridge) != expected) throw new java.io.IOException("CAPTURE_SAVE_REVOKED");
            publication.publish();
        }
    }

    static void requestBridge(Context context) {
        context.getContentResolver().notifyChange(URI, null, 0);
    }

    @Override public boolean onCreate() { return true; }

    private static CaptureIdentity captureIdentity(Context context, int callerUid, String callingPackage) {
        if (context == null || callerUid < 0 || !IslandRuleAccess.SYSTEM_UI.equals(callingPackage)) {
            return CaptureIdentity.REJECTED;
        }
        if (!Process.myUserHandle().equals(UserHandle.getUserHandleForUid(callerUid))) return CaptureIdentity.REJECTED;
        try {
            UserManager users = context.getSystemService(UserManager.class);
            if (users == null || users.isProfile() || !users.isUserUnlocked() || !users.isUserForeground()) {
                return CaptureIdentity.REJECTED;
            }
            PackageManager packages = context.getPackageManager();
            int systemUiUid = packages.getPackageUid(IslandRuleAccess.SYSTEM_UI, 0);
            if (callerUid != systemUiUid) return CaptureIdentity.REJECTED;
            String[] uidPackages = packages.getPackagesForUid(callerUid);
            boolean platformSigned = packages.checkSignatures(IslandRuleAccess.SYSTEM_UI, IslandRuleAccess.PLATFORM)
                    == PackageManager.SIGNATURE_MATCH;
            boolean platformSystemUid = uidPackages != null && uidPackages.length > 1
                    && callerUid == Process.SYSTEM_UID
                    && packages.getPackageUid(IslandRuleAccess.PLATFORM, 0) == callerUid;
            if (!IslandRuleAccess.allowsCaptureRegistration(callerUid, systemUiUid, true, true,
                    callingPackage, uidPackages, platformSigned, platformSystemUid)) return CaptureIdentity.REJECTED;
            return platformSystemUid ? CaptureIdentity.SHARED_PLATFORM : CaptureIdentity.EXCLUSIVE;
        } catch (PackageManager.NameNotFoundException | RuntimeException failure) {
            return CaptureIdentity.REJECTED;
        }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        int callerUid = Binder.getCallingUid();
        String callingPackage = getCallingPackage();
        CaptureIdentity identity = captureIdentity(getContext(), callerUid, callingPackage);
        if (identity == CaptureIdentity.REJECTED) {
            throw new SecurityException("CAPTURE_BRIDGE_CALLER");
        }
        if (!ATTACH.equals(method) || arg != null || extras == null || extras.size() != 1) {
            throw new IllegalArgumentException("CAPTURE_BRIDGE_METHOD");
        }
        IBinder offered = extras.getBinder("bridge");
        if (offered == null || !offered.isBinderAlive()) throw new IllegalArgumentException("CAPTURE_BRIDGE_MISSING");
        BridgeRegistration accepted = new BridgeRegistration(offered, callerUid,
                IslandRuleProvider.trusted(getContext(), callerUid, callingPackage),
                identity == CaptureIdentity.SHARED_PLATFORM);
        synchronized (REGISTRATION_LOCK) {
            registration = accepted;
        }
        Bundle reply = new Bundle();
        reply.putBinder("owner", OWNER);
        return reply;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String order) {
        throw new UnsupportedOperationException("CAPTURE_BRIDGE_METHOD");
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("CAPTURE_BRIDGE_METHOD"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("CAPTURE_BRIDGE_METHOD");
    }
    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("CAPTURE_BRIDGE_METHOD");
    }
}
