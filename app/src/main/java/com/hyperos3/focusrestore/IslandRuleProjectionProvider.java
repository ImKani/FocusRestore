/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Semaphore;

/** 平台共享 UID 的最小只读投影。UID 归属校验不区分 UID 内的进程。 */
public final class IslandRuleProjectionProvider extends ContentProvider {
    static final String AUTHORITY = "com.hyperos3.focusrestore.islandruleprojection";
    static final Uri URI = Uri.parse("content://" + AUTHORITY);
    static final String READ = "read_projection";
    static final String PAYLOAD = "projection";
    private static final String TAG = "FocusRestoreRules";
    private static IslandRuleProjectionConsent consent;
    private final Semaphore reads = new Semaphore(1);

    @Override public boolean onCreate() { return true; }

    static boolean available(Context context) {
        return trusted(context, Process.SYSTEM_UID, IslandRuleAccess.SYSTEM_UI);
    }

    static boolean trusted(Context context, int uid, String attribution) {
        if (context == null || uid != Process.SYSTEM_UID || !IslandRuleAccess.SYSTEM_UI.equals(attribution)) {
            return false;
        }
        try {
            UserManager users = context.getSystemService(UserManager.class);
            if (users == null || users.isProfile() || !users.isUserForeground() || !users.isUserUnlocked()) return false;
            PackageManager packages = context.getPackageManager();
            return IslandRuleAccess.allowsProjectionRead(uid,
                    packages.getPackageUid(IslandRuleAccess.SYSTEM_UI, 0),
                    Process.myUserHandle().equals(UserHandle.getUserHandleForUid(uid)), true,
                    attribution, packages.getPackagesForUid(uid),
                    packages.checkSignatures(IslandRuleAccess.SYSTEM_UI, IslandRuleAccess.PLATFORM)
                            == PackageManager.SIGNATURE_MATCH,
                    packages.getPackageUid(IslandRuleAccess.PLATFORM, 0) == uid);
        } catch (PackageManager.NameNotFoundException | RuntimeException incompleteEvidence) {
            return false;
        }
    }

    static synchronized IslandRuleProjectionConsent consent(Context app) throws IOException {
        IslandRuleImportUi.requireUnlocked(app);
        if (app.isDeviceProtectedStorage()) throw new IOException("RULE_PROJECTION_CE_REQUIRED");
        if (consent == null) {
            File parent = app.getNoBackupFilesDir();
            if (parent == null) throw new IOException("RULE_PROJECTION_CE_REQUIRED");
            IslandRuleImportUi.syncDirectory(parent.toPath().getParent());
            consent = new IslandRuleProjectionConsent(parent.toPath().resolve("island-projection-consent"),
                    IslandRuleImportUi::syncDirectory);
        }
        return consent;
    }

    static void notifyChanged(ContentResolver resolver) {
        try { resolver.notifyChange(URI, null, 0); }
        catch (RuntimeException failure) { Log.w(TAG, "rule projection notification failed"); }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        String attribution = getCallingPackage();
        Context context = getContext();
        if (!trusted(context, uid, attribution)) throw new SecurityException("RULE_PROJECTION_CALLER");
        if (!READ.equals(method) || arg != null || extras != null) throw new IllegalArgumentException("RULE_PROJECTION_METHOD");
        if (!reads.tryAcquire()) return Bundle.EMPTY;
        try {
            byte[] projection = consent(context).read(IslandRuleImportUi.repository(context));
            if (projection == null || !trusted(context, uid, attribution)) return Bundle.EMPTY;
            Bundle response = new Bundle();
            response.putByteArray(PAYLOAD, projection);
            return response;
        } catch (IOException | RuntimeException failure) {
            Log.w(TAG, "rule projection unavailable: " + failure.getClass().getSimpleName());
            return Bundle.EMPTY;
        } finally {
            reads.release();
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        throw new UnsupportedOperationException("RULE_PROJECTION_CALL_ONLY");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
