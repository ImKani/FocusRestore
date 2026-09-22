package com.hyperos3.focusrestore;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

public final class SettingsProvider extends ContentProvider {
    private static final String TAG = "HyperOS3FocusRestore";
    static final String AUTHORITY = "com.hyperos3.focusrestore.settings";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");
    static final String[] COLUMNS = SettingsContract.COLUMNS;
    static final String KEY_MARQUEE_DELAY_MS = FocusRestoreSettings.KEY_MARQUEE_DELAY_MS;
    static final int DEFAULT_MARQUEE_DELAY_MS = FocusRestoreSettings.DEFAULT_MARQUEE_DELAY_MS;
    private String lastDiagnostic;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (!URI.equals(uri) || getContext() == null) return null;
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
        String legacySeparator = preferences.getString(FocusRestoreSettings.KEY_ISLAND_SEPARATOR,
                FocusRestoreSettings.DEFAULT_ISLAND_SEPARATOR);
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        cursor.addRow(SettingsContract.toRow(settings, legacySeparator));
        return cursor;
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
