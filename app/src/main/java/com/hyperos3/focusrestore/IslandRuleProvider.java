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
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** 独立只读入口。Binder 只交换固定大小的头信息及管道描述符。 */
public final class IslandRuleProvider extends ContentProvider {
    static final String AUTHORITY = "com.hyperos3.focusrestore.islandrules";
    static final Uri HEAD = Uri.parse("content://" + AUTHORITY + "/head");
    static final String READ_HEAD = "read_head";
    private static final String TAG = "FocusRestoreRules";
    static final String SESSION = UUID.randomUUID().toString();
    private final Semaphore streams = new Semaphore(2);
    private final java.util.concurrent.ExecutorService writers = Executors.newFixedThreadPool(2);

    @Override public boolean onCreate() { return true; }

    static boolean sameForegroundUser(Context context) {
        UserManager users = context.getSystemService(UserManager.class);
        return users != null && users.isUserUnlocked() && users.isUserForeground();
    }

    static boolean trusted(Context context, int uid, String callingPackage) {
        if (context == null || uid < 0) return false;
        try {
            PackageManager packages = context.getPackageManager();
            int systemUiUid = packages.getPackageUid(IslandRuleAccess.SYSTEM_UI, 0);
            return IslandRuleAccess.allows(uid, systemUiUid,
                    Process.myUserHandle().equals(UserHandle.getUserHandleForUid(uid)),
                    sameForegroundUser(context), callingPackage, packages.getPackagesForUid(uid),
                    packages.checkSignatures(IslandRuleAccess.SYSTEM_UI, "android") == PackageManager.SIGNATURE_MATCH);
        } catch (PackageManager.NameNotFoundException | RuntimeException failure) {
            return false;
        }
    }

    private Context authorize() {
        Context context = getContext();
        if (!trusted(context, Binder.getCallingUid(), getCallingPackage())) {
            throw new SecurityException("RULE_CALLER_REJECTED");
        }
        return context;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Context context = authorize();
        if (!READ_HEAD.equals(method) || arg != null || extras != null) throw new IllegalArgumentException("RULE_METHOD");
        try {
            long revision = IslandRuleImportUi.repository(context).revision();
            Bundle head = new Bundle();
            head.putInt("protocol", IslandRuleBatch.VERSION);
            head.putString("session", SESSION);
            head.putLong("revision", revision);
            return head;
        } catch (IOException failure) {
            throw new IllegalStateException("RULE_REPOSITORY_UNAVAILABLE");
        }
    }

    static Uri batch(String session, long revision) {
        return HEAD.buildUpon().path("batch").appendPath(session).appendPath(Long.toString(revision)).build();
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context context = authorize();
        List<String> parts = uri.getPathSegments();
        if (!"r".equals(mode) || parts.size() != 3 || !"batch".equals(parts.get(0)) || !SESSION.equals(parts.get(1))) {
            throw new FileNotFoundException("RULE_BATCH_URI");
        }
        if (!streams.tryAcquire()) throw new FileNotFoundException("RULE_BATCH_BUSY");
        IslandRuleRepository.ReadView view = null;
        ParcelFileDescriptor[] pipe = null;
        try {
            long expected = Long.parseLong(parts.get(2));
            view = IslandRuleImportUi.repository(context).openRead();
            if (expected <= 0 || expected != view.revision || !batch(SESSION, expected).equals(uri)) {
                throw new IOException("RULE_BATCH_REVISION");
            }
            pipe = ParcelFileDescriptor.createReliablePipe();
            ParcelFileDescriptor output = pipe[1];
            IslandRuleRepository.ReadView pinned = view;
            long deadline = SystemClock.elapsedRealtime() + IslandRulePublisher.ATTEMPT_MS;
            writers.execute(() -> write(context, pinned, output, deadline));
            return pipe[0];
        } catch (IOException | RuntimeException failure) {
            if (pipe != null) {
                closeWithError(pipe[1]);
                try { pipe[0].close(); } catch (IOException ignored) { Log.w(TAG, "rule read pipe close failed"); }
            }
            if (view != null) release(view);
            streams.release();
            throw new FileNotFoundException("RULE_BATCH_UNAVAILABLE");
        }
    }

    private void write(Context context, IslandRuleRepository.ReadView view, ParcelFileDescriptor output, long deadline) {
        try {
            if (!sameForegroundUser(context)) throw new IOException("RULE_USER_CHANGED");
            FileDescriptor descriptor = output.getFileDescriptor();
            Os.fcntlInt(descriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK);
            IslandRuleBatch.write(new PipeOutput(descriptor, deadline), view);
            if (!sameForegroundUser(context)) throw new IOException("RULE_USER_CHANGED");
            output.close();
        } catch (IOException | ErrnoException | RuntimeException failure) {
            closeWithError(output);
        } finally {
            release(view);
            streams.release();
        }
    }

    private static final class PipeOutput extends OutputStream {
        private final FileDescriptor fd;
        private final long deadline;
        private final byte[] single = new byte[1];

        PipeOutput(FileDescriptor fd, long deadline) {
            this.fd = fd;
            this.deadline = deadline;
        }

        @Override public void write(int value) throws IOException {
            single[0] = (byte) value;
            write(single, 0, 1);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            while (length > 0) {
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) throw new IOException("RULE_PIPE_TIMEOUT");
                try {
                    int count = Os.write(fd, bytes, offset, length);
                    if (count <= 0) throw new IOException("RULE_PIPE_WRITE");
                    offset += count;
                    length -= count;
                } catch (ErrnoException failure) {
                    if (failure.errno == OsConstants.EINTR) continue;
                    if (failure.errno != OsConstants.EAGAIN) throw failure.rethrowAsIOException();
                    SystemClock.sleep(Math.min(left, 10));
                }
            }
        }
    }

    private static void release(IslandRuleRepository.ReadView view) {
        try { view.close(); } catch (IOException failure) { Log.w(TAG, "rule batch release failed"); }
    }

    private static void closeWithError(ParcelFileDescriptor output) {
        try { output.closeWithError("RULE_PIPE_ABORTED"); }
        catch (IOException failure) { Log.w(TAG, "rule pipe shutdown failed"); }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        throw new UnsupportedOperationException("RULE_READ_HEAD_ONLY");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
