/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;

/** Reads one complete settings snapshot from the module provider. */
final class HookSettingsReader {
    private HookSettingsReader() {
    }

    static HookSettings read(Context context) {
        if (context == null) return null;
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            // 不指定列顺序：新版按功能分组，旧 Provider 仍可返回旧顺序，由字段名识别。
            cursor = resolver.query(SettingsProvider.URI, null, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) return null;
            return HookSettings.fromCursor(cursor);
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}