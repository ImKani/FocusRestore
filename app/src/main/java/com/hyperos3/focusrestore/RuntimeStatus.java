/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

/** 仅进程内保存有存活令牌的加载报告；无磁盘缓存、通知内容或设备操作。 */
final class RuntimeStatus {
    static final String REPORT="report_loaded_status", GET="get_loaded_status";
    static final Uri URI=Uri.parse("content://"+SettingsProvider.AUTHORITY+"/loaded_status");
    static final Uri REQUEST_URI=Uri.parse("content://"+SettingsProvider.AUTHORITY+"/request_loaded_status");
    private IBinder token;
    private Bundle snapshot;
    synchronized void report(Context context,Bundle incoming) throws android.os.RemoteException {
        if (incoming == null) return;
        IBinder next=incoming.getBinder("process_token");
        if (next == null || !next.isBinderAlive()) return;
        Bundle clean=new Bundle();
        for (String key : new String[]{"version_code","pid","mode","handles","failures"}) clean.putInt(key,incoming.getInt(key));
        clean.putBoolean("ready",incoming.getBoolean("ready"));
        clean.putBoolean("privacy_ready",incoming.getBoolean("privacy_ready"));
        clean.putBoolean("chips_ready",incoming.getBoolean("chips_ready"));
        clean.putLong("config_generation",incoming.getLong("config_generation"));
        if (token != next) {
            next.linkToDeath(() -> {
                synchronized (RuntimeStatus.this) {
                    if (token != next) return;
                    token=null; snapshot=null;
                }
                context.getContentResolver().notifyChange(URI,null);
            },0);
        }
        token=next; snapshot=clean;
        context.getContentResolver().notifyChange(URI,null);
    }
    synchronized Bundle read() {
        return token != null && token.isBinderAlive() && snapshot != null ? new Bundle(snapshot) : new Bundle();
    }
}
