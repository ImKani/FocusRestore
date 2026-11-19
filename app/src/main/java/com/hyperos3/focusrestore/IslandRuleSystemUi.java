/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.content.BroadcastReceiver;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** SystemUI 主进程 OS4 安装周期内唯一的规则快照来源。 */
final class IslandRuleSystemUi {
    interface SnapshotListener { void onSnapshot(IslandRuleSetLoader.Snapshot snapshot); }

    private static final String APP = "com.hyperos3.focusrestore";
    // 作者发布证书，来源为工作区签名合同。拒绝未知或不一致的安装包。
    private static final String AUTHOR_CERT = "ab5d2f5fc4ccca5360966a99a61c356e6446cab8562f69a466c58a1da95c5afd";
    private static final long RECONCILE_MS = 15_000;
    private static final String TAG = "FocusRestoreRules";
    private final Context context;
    private final Handler main;
    private final IslandRulePublisher publisher = new IslandRulePublisher(SystemClock::elapsedRealtime);
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final ExecutorService reporting = Executors.newSingleThreadExecutor();
    private final Semaphore slots = new Semaphore(2);
    private final Binder statusToken = new Binder();
    private final Runnable reconcile = this::refresh;
    private boolean reportRunning;
    private boolean reportDirty;
    private boolean observing;
    private boolean observingProjection;
    private Attempt active;
    private String lastFailure;
    private volatile SnapshotListener snapshotListener;

    IslandRuleSystemUi(Context context, Handler main) {
        this.context = context;
        this.main = main;
        main.post(this::start);
    }

    IslandRuleSetLoader.Snapshot current() { return publisher.current(); }

    void setSnapshotListener(SnapshotListener listener) {
        snapshotListener = listener;
        notifySnapshot();
    }

    private void notifySnapshot() {
        SnapshotListener listener = snapshotListener;
        if (listener == null) return;
        main.post(() -> {
            SnapshotListener currentListener = snapshotListener;
            if (currentListener != null) currentListener.onSnapshot(publisher.current());
        });
    }

    private final ContentObserver observer = new ContentObserver(null) {
        @Override public void onChange(boolean selfChange) { sourceChanged("provider"); }
        @Override public void onChange(boolean selfChange, Uri uri) {
            sourceChanged(IslandRuleProjectionProvider.URI.equals(uri) ? "projection-notified"
                    : IslandRuleProvider.HEAD.equals(uri) ? "head-notified" : "provider");
        }
    };

    private final BroadcastReceiver userEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            sourceChanged("package".equals(intent.getScheme()) ? "package-change" : "user-change");
        }
    };

    private void start() {
        IntentFilter users = new IntentFilter();
        users.addAction(Intent.ACTION_USER_UNLOCKED);
        users.addAction(Intent.ACTION_USER_FOREGROUND);
        users.addAction(Intent.ACTION_USER_BACKGROUND);
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        packages.addDataSchemeSpecificPart(APP, android.os.PatternMatcher.PATTERN_LITERAL);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(userEvents, users, Context.RECEIVER_NOT_EXPORTED);
                context.registerReceiver(userEvents, packages, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(userEvents, users);
                context.registerReceiver(userEvents, packages);
            }
        } catch (RuntimeException failure) { diagnostic("EVENTS_UNAVAILABLE"); }
        refresh();
    }

    private void sourceChanged(String reason) {
        publisher.invalidate();
        if (BuildConfig.DEBUG) Log.i("HyperOS3FocusRestore", "DIAG rule snapshot invalidated reason=" + reason);
        notifySnapshot();
        // 通知只触发核对，不携带或推进目标修订。多个事件合并到主线程。
        main.removeCallbacks(reconcile);
        main.post(reconcile);
    }

    private void refresh() {
        main.removeCallbacks(reconcile);
        main.postDelayed(reconcile, RECONCILE_MS);
        if (active != null) {
            main.removeCallbacks(active.timeout);
            active.cancel();
            active = null;
        }
        IslandRulePublisher.Task task = publisher.begin();
        requestStatus();
        if (!slots.tryAcquire()) {
            publisher.fail(task);
            diagnostic("READERS_BUSY");
            return;
        }
        Attempt attempt = new Attempt(task);
        active = attempt;
        attempt.timeout = () -> {
            if (active != attempt) return;
            active = null;
            publisher.fail(task);
            attempt.cancel();
            notifySnapshot();
            requestStatus();
            diagnostic("RECONCILE_TIMEOUT");
        };
        main.postDelayed(attempt.timeout, IslandRulePublisher.ATTEMPT_MS);
        workers.execute(() -> {
            String failure = null;
            try { load(attempt); }
            catch (IOException | android.os.RemoteException | RuntimeException error) {
                publisher.fail(task);
                // Provider 空答可能是未授权、读取繁忙或存储不可用；仅记录本地阶段和异常类型。
                failure = attempt.phase + "/" + error.getClass().getSimpleName();
            } finally {
                slots.release();
                String result = failure;
                main.post(() -> {
                    if (active != attempt) return;
                    main.removeCallbacks(attempt.timeout);
                    active = null;
                    if (result != null) diagnostic(result);
                    else lastFailure = null;
                    notifySnapshot();
                    requestStatus();
                });
            }
        });
    }

    private void load(Attempt attempt) throws IOException, android.os.RemoteException {
        if (IslandRuleProjectionProvider.available(context)) loadProjection(attempt);
        else loadStrict(attempt);
    }

    private void loadStrict(Attempt attempt) throws IOException, android.os.RemoteException {
        attempt.phase = "RULE_STRICT_SOURCE_CHECK";
        IslandRuleImportValidator.Environment environment = verifySource();
        attempt.phase = "RULE_HEAD_UNAVAILABLE";
        try (ContentProviderClient provider = context.getContentResolver().acquireUnstableContentProviderClient(IslandRuleProvider.HEAD)) {
            if (provider == null) throw new IOException("RULE_PROVIDER_UNAVAILABLE");
            observe();
            IslandRulePublisher.Head target = head(provider);
            if (!publisher.target(attempt.task, target)) throw new IOException("RULE_HEAD_UNAVAILABLE");
            attempt.phase = "RULE_BATCH_REJECTED";
            IslandRuleBatch batch;
            ParcelFileDescriptor fd = provider.openFile(IslandRuleProvider.batch(target.session, target.revision), "r");
            if (fd == null) throw new IOException("RULE_DESCRIPTOR_UNAVAILABLE");
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                attempt.attach(fd);
                batch = IslandRuleBatch.read(input, target.revision, environment);
                fd.checkError();
            } finally { attempt.detach(); }
            IslandRuleSetLoader.Snapshot candidate = publisher.load(attempt.task, batch.revision, batch.sources);
            attempt.phase = "RULE_FINAL_CHECK_REJECTED";
            IslandRulePublisher.Head confirmed = head(provider);
            if (!environment.sameAs(verifySource())) throw new IOException("RULE_ENVIRONMENT_CHANGED");
            if (!publisher.finish(attempt.task, confirmed, candidate)) throw new IOException("RULE_PUBLISH_REJECTED");
        }
    }

    private void loadProjection(Attempt attempt) throws IOException, android.os.RemoteException {
        attempt.phase = "RULE_PROJECTION_SOURCE_CHECK";
        IslandRuleImportValidator.Environment environment = verifyProjectionSource();
        observeProjection();
        attempt.phase = "RULE_PROJECTION_READ";
        byte[] first = readProjection();
        if (first == null) {
            attempt.phase = "RULE_PROJECTION_NO_PAYLOAD";
            throw new IOException(attempt.phase);
        }
        attempt.phase = "RULE_PROJECTION_DECODE";
        List<IslandRuleProjection.Row> rows = IslandRuleProjection.decode(first);
        String session = IslandRuleRepository.digest(first);
        IslandRulePublisher.Head target = new IslandRulePublisher.Head(session, 1L);
        if (!publisher.target(attempt.task, target)) throw new IOException("RULE_PROJECTION_STALE");
        attempt.phase = "RULE_PROJECTION_REJECTED";
        List<IslandRuleSetLoader.Source> sources = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            IslandRuleProjection.Row row = rows.get(index);
            List<IslandRulePaths.Entry> paths = new ArrayList<>();
            for (String path : row.paths) {
                IslandRulePaths.Entry registered = IslandRulePaths.find(path);
                if (!IslandRulePaths.isRuntimeAllowed(registered)) throw new IOException("RULE_PROJECTION_PATH");
                paths.add(registered);
            }
            IslandCaptureEditor.Candidate candidate = IslandCaptureEditor.candidate(
                    row.packageName, paths, System.currentTimeMillis());
            if (candidate == null) throw new IOException("RULE_PROJECTION_CANDIDATE");
            final byte[] rule = candidate.bytes();
            sources.add(new IslandRuleSetLoader.Source("projection-" + index, 1L, true,
                    IslandRuleRepository.MAX_ACTIVE - index,
                    () -> new java.io.ByteArrayInputStream(rule)));
        }
        IslandRuleSetLoader.Snapshot candidate = publisher.load(attempt.task, 1L, sources);
        attempt.phase = "RULE_PROJECTION_FINAL_CHECK";
        byte[] confirmedBytes = readProjection();
        if (confirmedBytes == null || !Arrays.equals(first, confirmedBytes)
                || !environment.sameAs(verifyProjectionSource())) {
            throw new IOException("RULE_PROJECTION_CHANGED");
        }
        IslandRulePublisher.Head confirmed = new IslandRulePublisher.Head(
                IslandRuleRepository.digest(confirmedBytes), 1L);
        if (!publisher.finish(attempt.task, confirmed, candidate)) throw new IOException("RULE_PUBLISH_REJECTED");
        Log.d(TAG, "rule projection loaded rows=" + rows.size()
                + " digest=" + IslandRuleRepository.digest(first));
    }

    private byte[] readProjection() throws IOException, android.os.RemoteException {
        try (ContentProviderClient provider = context.getContentResolver()
                .acquireUnstableContentProviderClient(IslandRuleProjectionProvider.URI)) {
            if (provider == null) throw new IOException("RULE_PROJECTION_PROVIDER_UNAVAILABLE");
            Bundle value = provider.call(IslandRuleProjectionProvider.READ, null, null);
            byte[] payload = value == null ? null : value.getByteArray(IslandRuleProjectionProvider.PAYLOAD);
            if (payload == null) return null;
            if (payload.length > 128 * 1024) throw new IOException("RULE_PROJECTION_TOO_LARGE");
            return payload.clone();
        }
    }

    private IslandRuleImportValidator.Environment verifyProjectionSource() throws IOException {
        if (!IslandRuleProvider.sameForegroundUser(context)) throw new IOException("RULE_USER_UNAVAILABLE");
        PackageManager packages = context.getPackageManager();
        try {
            ProviderInfo info = packages.resolveContentProvider(IslandRuleProjectionProvider.AUTHORITY, 0);
            if (info == null || !APP.equals(info.packageName)
                    || !IslandRuleProjectionProvider.class.getName().equals(info.name)
                    || !info.exported || info.directBootAware || info.grantUriPermissions || info.applicationInfo == null
                    || !Process.myUserHandle().equals(UserHandle.getUserHandleForUid(info.applicationInfo.uid))) {
                throw new IOException("RULE_PROJECTION_PROVIDER_IDENTITY");
            }
            PackageInfo source = packages.getPackageInfo(APP, PackageManager.GET_SIGNING_CERTIFICATES);
            android.content.pm.Signature[] signers = source.signingInfo == null ? null : source.signingInfo.getApkContentsSigners();
            if (source.applicationInfo == null || source.applicationInfo.uid != info.applicationInfo.uid
                    || source.getLongVersionCode() != BuildConfig.VERSION_CODE || signers == null || signers.length != 1
                    || !AUTHOR_CERT.equals(IslandRuleRepository.digest(signers[0].toByteArray()))) {
                throw new IOException("RULE_PROJECTION_CERT_OR_VERSION");
            }
            PackageInfo systemUi = packages.getPackageInfo(IslandRuleAccess.SYSTEM_UI, 0);
            if (systemUi.applicationInfo == null || systemUi.applicationInfo.uid != Process.myUid()
                    || systemUi.versionName == null || systemUi.versionName.isEmpty()
                    || systemUi.getLongVersionCode() < 0) throw new IOException("RULE_SYSTEM_UI_VERSION");
            return new IslandRuleImportValidator.Environment(systemUi.versionName, systemUi.getLongVersionCode());
        } catch (PackageManager.NameNotFoundException failure) {
            throw new IOException("RULE_PROJECTION_PACKAGE_UNAVAILABLE");
        }
    }

    private synchronized void observe() {
        if (observing) return;
        context.getContentResolver().registerContentObserver(IslandRuleProvider.HEAD, false, observer);
        observing = true;
    }

    private synchronized void observeProjection() {
        if (observingProjection) return;
        context.getContentResolver().registerContentObserver(IslandRuleProjectionProvider.URI, false, observer);
        observingProjection = true;
    }

    private static IslandRulePublisher.Head head(ContentProviderClient provider) throws IOException, android.os.RemoteException {
        Bundle value = provider.call(IslandRuleProvider.READ_HEAD, null, null);
        if (value == null || value.getInt("protocol") != IslandRuleBatch.VERSION) throw new IOException("RULE_PROTOCOL_VERSION");
        String session = value.getString("session");
        if (session == null || session.length() != 36 || !UUID.fromString(session).toString().equals(session)) {
            throw new IOException("RULE_PROVIDER_SESSION");
        }
        return new IslandRulePublisher.Head(session, value.getLong("revision", -1));
    }

    private IslandRuleImportValidator.Environment verifySource() throws IOException {
        if (!IslandRuleProvider.sameForegroundUser(context)) throw new IOException("RULE_USER_UNAVAILABLE");
        PackageManager packages = context.getPackageManager();
        try {
            ProviderInfo info = packages.resolveContentProvider(IslandRuleProvider.AUTHORITY, 0);
            if (info == null || !APP.equals(info.packageName) || !IslandRuleProvider.class.getName().equals(info.name)
                    || !info.exported || info.directBootAware || info.grantUriPermissions || info.applicationInfo == null
                    || !Process.myUserHandle().equals(UserHandle.getUserHandleForUid(info.applicationInfo.uid))) {
                throw new IOException("RULE_PROVIDER_IDENTITY");
            }
            PackageInfo source = packages.getPackageInfo(APP, PackageManager.GET_SIGNING_CERTIFICATES);
            android.content.pm.Signature[] signers = source.signingInfo == null ? null : source.signingInfo.getApkContentsSigners();
            if (source.applicationInfo == null || source.applicationInfo.uid != info.applicationInfo.uid
                    || source.getLongVersionCode() != BuildConfig.VERSION_CODE || signers == null || signers.length != 1
                    || !AUTHOR_CERT.equals(IslandRuleRepository.digest(signers[0].toByteArray()))) {
                throw new IOException("RULE_PROVIDER_CERT_OR_VERSION");
            }
            PackageInfo systemUi = packages.getPackageInfo(IslandRuleAccess.SYSTEM_UI, 0);
            if (systemUi.applicationInfo == null || systemUi.applicationInfo.uid != Process.myUid()
                    || systemUi.versionName == null || systemUi.versionName.isEmpty() || systemUi.getLongVersionCode() < 0) {
                throw new IOException("RULE_SYSTEM_UI_VERSION");
            }
            return new IslandRuleImportValidator.Environment(systemUi.versionName, systemUi.getLongVersionCode());
        } catch (PackageManager.NameNotFoundException failure) {
            throw new IOException("RULE_PACKAGE_UNAVAILABLE");
        }
    }

    private void requestStatus() {
        if (reportRunning) { reportDirty = true; return; }
        reportRunning = true;
        reporting.execute(() -> {
            try {
                Bundle value = IslandRuleAppliedStatus.encode(statusToken, publisher.status());
                context.getContentResolver().call(SettingsProvider.URI, IslandRuleAppliedStatus.METHOD, null, value);
            } catch (RuntimeException failure) {
                Log.d(TAG, "rule status unavailable");
            } finally {
                main.post(() -> {
                    reportRunning = false;
                    if (reportDirty) { reportDirty = false; requestStatus(); }
                });
            }
        });
    }

    private void diagnostic(String failure) {
        if (failure.equals(lastFailure)) return;
        if (BuildConfig.DEBUG) Log.w("HyperOS3FocusRestore", "DIAG rule snapshot read failed reason=" + failure);
        else Log.w(TAG, "rule snapshot unavailable: " + failure);
        lastFailure = failure;
    }

    private static final class Attempt {
        final IslandRulePublisher.Task task;
        Runnable timeout;
        String phase = "RULE_SOURCE_UNVERIFIED";
        private ParcelFileDescriptor fd;
        private boolean cancelled;

        Attempt(IslandRulePublisher.Task task) { this.task = task; }
        synchronized void attach(ParcelFileDescriptor opened) throws IOException {
            if (cancelled) throw new IOException("RULE_ATTEMPT_CANCELLED");
            fd = opened;
        }
        synchronized void detach() { fd = null; }
        synchronized void cancel() {
            cancelled = true;
            if (fd != null) {
                try { fd.close(); } catch (IOException failure) { Log.w(TAG, "rule reader close failed"); }
                fd = null;
            }
        }
    }
}
