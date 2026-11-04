/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import android.util.Log;

public final class SettingsProvider extends ContentProvider {
    private static final String TAG = "HyperOS3FocusRestore";
    static final String AUTHORITY = "com.hyperos3.focusrestore.settings";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");
    static final String[] COLUMNS = SettingsContract.COLUMNS;
    static final String KEY_MARQUEE_DELAY_MS = FocusRestoreSettings.KEY_MARQUEE_DELAY_MS;
    static final int DEFAULT_MARQUEE_DELAY_MS = FocusRestoreSettings.DEFAULT_MARQUEE_DELAY_MS;
    /** The only external reader: SystemUI hosts the hooks and reads this provider across processes. */
    private static final String SYSTEM_UI = "com.android.systemui";
    private String lastDiagnostic;
    private final RuntimeStatus runtimeStatus = new RuntimeStatus();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (!URI.equals(uri) || getContext() == null) return null;
        if (!isTrustedCaller()) {
            logDiagnostic("provider settings rejected callerUid=" + Binder.getCallingUid());
            return null;
        }
        Context context = getContext();
        SharedPreferences preferences = FocusRestoreSettings.hookPreferences(context);
        if (!FocusRestoreSettings.hasHookSettings(preferences)) {
            logDiagnostic("provider settings unavailable storage=deviceProtected ready=false "
                    + "columns=" + COLUMNS.length);
            return null;
        }
        FocusRestoreSettings settings = FocusRestoreSettings.fromPreferences(preferences);
        logDiagnostic("provider settings storage=deviceProtected ready=true columns="
                + COLUMNS.length + " " + settings.describe());
        String legacySeparator = FocusRestoreSettings.readLegacyIslandSeparator(preferences);
        String[] columns = SettingsContract.columnsForProjection(projection);
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(SettingsContract.toRow(settings, legacySeparator, columns));
        android.os.Bundle extras = new android.os.Bundle();
        extras.putLong("saved_generation", FocusRestoreSettings.generation(preferences));
        cursor.setExtras(extras);
        return cursor;
    }

    @Override public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        if (getContext() == null) return null;
        if (RuntimeStatus.GET.equals(method)) {
            if (!isTrustedCaller()) return null;
            android.os.Bundle status = runtimeStatus.read();
            if (status.isEmpty()) getContext().getContentResolver().notifyChange(RuntimeStatus.REQUEST_URI, null);
            return status;
        }
        if (!RuntimeStatus.REPORT.equals(method)) return super.call(method, arg, extras);
        // 写入加载状态不使用配置读取的特殊ROM身份查询失败放行策略。
        int uid = Binder.getCallingUid();
        boolean trusted = uid == Process.myUid() || uid == Process.SYSTEM_UID;
        if (!trusted) {
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (packages != null) for (String name : packages) trusted |= SYSTEM_UI.equals(name);
        }
        if (!trusted) throw new SecurityException("Only SystemUI may report its loaded hooks");
        try { runtimeStatus.report(getContext(), extras); }
        catch (android.os.RemoteException dead) { logDiagnostic("loaded status rejected: process ended"); }
        return new android.os.Bundle();
    }

    /**
     * The provider must stay exported because SystemUI reads it from another process, and a
     * signature permission cannot be used either (SystemUI is not signed with the module key), so
     * the caller identity is checked instead: our own process, the system uid, or SystemUI itself.
     *
     * <p>An inconclusive identity lookup deliberately allows the read: the payload contains only
     * this module's own display settings, and silently breaking the hooks on an unusual ROM would
     * be worse than the marginal disclosure. Ordinary third-party apps are rejected because their
     * uid is neither system nor SystemUI.
     */
    private boolean isTrustedCaller() {
        try {
            int uid = Binder.getCallingUid();
            if (uid == Process.myUid() || uid == Process.SYSTEM_UID) return true;
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (packages == null || packages.length == 0) return true;
            for (String name : packages) {
                if (SYSTEM_UI.equals(name)) return true;
            }
            return false;
        } catch (Throwable unsupported) {
            return true;
        }
    }

    private void logDiagnostic(String diagnostic) {
        if (diagnostic.equals(lastDiagnostic)) return;
        lastDiagnostic = diagnostic;
        Log.i(TAG, diagnostic);
    }

    @Override public String getType(Uri uri) {
        return URI.equals(uri) ? "vnd.android.cursor.item/vnd.hyperos3.settings" : null;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
