/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.app.KeyguardManager;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.notification.StatusBarNotification;
import android.widget.RemoteViews;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import de.robv.android.xposed.XposedHelpers;

/** 活动通知回调和捕获进程边界。 */
final class IslandCaptureSystemUi {
    private static final String TAG = "FocusRestoreCapture";
    private static final String APP = "com.hyperos3.focusrestore";
    private static final String CERT = "ab5d2f5fc4ccca5360966a99a61c356e6446cab8562f69a466c58a1da95c5afd";
    private final Context context;
    private final Handler main;
    private final UserManager users;
    private final PowerManager power;
    private final KeyguardManager keyguard;
    private final IslandCaptureRuntime runtime;
    private final Executor connector = Executors.newSingleThreadExecutor();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicBoolean reconnect = new AtomicBoolean();
    private volatile boolean observing;
    private volatile boolean observingProfiles;
    private final int mode;
    private volatile IslandCaptureRuntime.Availability sourceState = IslandCaptureRuntime.Availability.WAITING_SOURCE;
    private volatile Bridge bridge;
    // 只由连接工作线程更新，设置页反复轮询时不重复记同一阶段。
    private String lastAttachFailure;

    static IslandCaptureSystemUi create(Context context, Handler main, int mode) {
        try {
            return new IslandCaptureSystemUi(context, main, mode);
        } catch (RuntimeException | LinkageError failure) {
            android.util.Log.w(TAG, "mode=OS" + mode + " stage=INIT reason=UNAVAILABLE");
            return null;
        }
    }

    private IslandCaptureSystemUi(Context context, Handler main, int mode) {
        this.context = context;
        this.main = main;
        this.mode = mode;
        users = context.getSystemService(UserManager.class);
        power = context.getSystemService(PowerManager.class);
        keyguard = context.getSystemService(KeyguardManager.class);
        runtime = new IslandCaptureRuntime(this::eligible, this::confirmPreviewAccess, android.os.SystemClock::elapsedRealtime,
                pkg -> {
                    try { return context.getPackageManager().getPackageUid(pkg, 0); }
                    catch (PackageManager.NameNotFoundException unavailable) { return -1; }
                });
        String stage = "lock listener";
        try {
            // 锁屏监听不可用时保持关闭，避免解锁后误沿用上次会话。
            if (keyguard == null) {
                android.util.Log.w(TAG, "capture lock listener unavailable");
                return;
            }
            keyguard.addKeyguardLockedStateListener(Runnable::run, locked -> { if (locked) runtime.end(); });
            stage = "environment receiver";
            IntentFilter events = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            events.addAction("android.intent.action.USER_SWITCHED");
            events.addAction(Intent.ACTION_USER_BACKGROUND);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) { runtime.end(); }
            };
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, events, null, main, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(receiver, events, null, main);
            stage = "bridge observer";
            context.getContentResolver().registerContentObserver(IslandCaptureProvider.URI, false, new ContentObserver(main) {
                @Override public void onChange(boolean selfChange) { connect(); }
            });
            observing = true;
        } catch (RuntimeException | LinkageError failure) {
            android.util.Log.w(TAG, "capture " + stage + " unavailable");
        }
        if (observing) observeProfiles();
    }

    private void observeProfiles() {
        IntentFilter events = new IntentFilter(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        events.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        events.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        events.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { runtime.revokePreviewAccess(); }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, events, null, main, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(receiver, events, null, main);
            observingProfiles = true;
        } catch (RuntimeException | LinkageError failure) {
            android.util.Log.w(TAG, "capture preview profile observer unavailable");
        }
    }

    void pipelineRegistered(boolean supportsCaptureCallbacks) {
        synchronized (runtime) {
            runtime.end();
            sourceState = supportsCaptureCallbacks ? IslandCaptureRuntime.Availability.READY
                    : IslandCaptureRuntime.Availability.UNSUPPORTED_SOURCE;
        }
        if (!supportsCaptureCallbacks || mode == 3) {
            android.util.Log.i(TAG, "mode=OS" + mode + " stage=SOURCE reason=" + sourceState.name());
        }
    }

    void sourceUnsupported() { pipelineRegistered(false); }

    private boolean eligible() {
        return availability() == IslandCaptureRuntime.Availability.READY;
    }

    private IslandCaptureRuntime.Availability availability() {
        if (!observing || !observingProfiles || users == null || power == null || keyguard == null) {
            return IslandCaptureRuntime.Availability.OBSERVER_UNAVAILABLE;
        }
        try {
            if (!power.isInteractive() || keyguard.isKeyguardLocked() || keyguard.isDeviceLocked()
                    || !users.isUserUnlocked()) return IslandCaptureRuntime.Availability.LOCKED;
            if (!users.isUserForeground() || users.isProfile() || confirmedMainUser() < 0) {
                return IslandCaptureRuntime.Availability.USER_UNAVAILABLE;
            }
        } catch (RuntimeException | LinkageError failure) {
            return IslandCaptureRuntime.Availability.USER_UNAVAILABLE;
        }
        if (sourceState != IslandCaptureRuntime.Availability.READY) return sourceState;
        Bridge connection = bridge;
        return connection != null && connection.live() ? IslandCaptureRuntime.Availability.READY
                : IslandCaptureRuntime.Availability.CONNECTION_UNAVAILABLE;
    }

    private int confirmedMainUser() {
        // AOSP android-15.0.0_r1 UserManager.isMainUser() 的结果只适用于该 Context 对应的用户。
        try {
            Object mainUser = XposedHelpers.callMethod(users, "isMainUser");
            if (!Boolean.TRUE.equals(mainUser)) return -1;
            Object contextUser = XposedHelpers.callMethod(context, "getUserId");
            int user = userId(Process.myUserHandle());
            return user >= 0 && Integer.valueOf(user).equals(contextUser) ? user : -1;
        } catch (RuntimeException | LinkageError | XposedHelpers.InvocationTargetError unavailable) {
            return -1;
        }
    }

    private IslandCaptureRuntime.PreviewAccess confirmPreviewAccess() {
        Bridge connection = bridge;
        if (!observingProfiles || !eligible()) return null;
        int user = confirmedMainUser();
        if (user < 0 || bridge != connection || !eligible()) return null;
        return new IslandCaptureRuntime.PreviewAccess(connection, user);
    }

    private void connect() {
        if (!observing) return;
        reconnect.set(true);
        if (!connecting.compareAndSet(false, true)) return;
        connector.execute(() -> {
            try {
                while (reconnect.getAndSet(false)) attach();
            } finally {
                connecting.set(false);
                if (reconnect.get()) connect();
            }
        });
    }

    private void attach() {
        String stage = "app identity";
        try {
            if (!IslandRuleProvider.sameForegroundUser(context) || users.isProfile()) return;
            int uid = verifyApp();
            Bridge previous = bridge;
            if (previous != null && previous.uid == uid && previous.live()) return;
            Bridge candidate = new Bridge(uid);
            Bundle request = new Bundle();
            request.putBinder("bridge", candidate);
            stage = "registration";
            Bundle reply = context.getContentResolver().call(IslandCaptureProvider.URI, IslandCaptureProvider.ATTACH, null, request);
            stage = "app owner";
            IBinder owner = reply == null ? null : reply.getBinder("owner");
            if (owner == null || !owner.isBinderAlive() || verifyApp() != uid) {
                recordAttachFailure(stage);
                return;
            }
            candidate.owner = owner;
            owner.linkToDeath(() -> disconnect(candidate), 0);
            synchronized (this) {
                if (!owner.isBinderAlive()) {
                    recordAttachFailure(stage);
                    return;
                }
                runtime.end();
                bridge = candidate;
            }
            lastAttachFailure = null;
            if (BuildConfig.DEBUG) android.util.Log.i(TAG, "capture bridge attached");
        } catch (PackageManager.NameNotFoundException | IOException | RemoteException | RuntimeException | LinkageError failure) {
            recordAttachFailure(stage);
        }
    }

    private void recordAttachFailure(String stage) {
        // App 尚未就绪时由设置页再次请求握手，旧授权不会随连接迁移。
        if (stage.equals(lastAttachFailure)) return;
        lastAttachFailure = stage;
        android.util.Log.w(TAG, "capture " + stage + " unavailable");
    }

    private synchronized void disconnect(Bridge expected) {
        if (bridge != expected) return;
        runtime.end();
        bridge = null;
    }

    private int verifyApp() throws PackageManager.NameNotFoundException, IOException {
        PackageManager pm = context.getPackageManager();
        ProviderInfo provider = pm.resolveContentProvider(IslandCaptureProvider.AUTHORITY, 0);
        PackageInfo app = pm.getPackageInfo(APP, PackageManager.GET_SIGNING_CERTIFICATES);
        Signature[] signers = app.signingInfo == null ? null : app.signingInfo.getApkContentsSigners();
        if (provider == null || !APP.equals(provider.packageName) || !IslandCaptureProvider.class.getName().equals(provider.name)
                || !provider.exported || provider.directBootAware || provider.grantUriPermissions
                || provider.applicationInfo == null || app.applicationInfo == null
                || app.applicationInfo.uid != provider.applicationInfo.uid
                || !Process.myUserHandle().equals(UserHandle.getUserHandleForUid(app.applicationInfo.uid))
                || app.getLongVersionCode() != BuildConfig.VERSION_CODE || signers == null || signers.length != 1
                || !CERT.equals(IslandRuleRepository.digest(signers[0].toByteArray()))) {
            throw new IOException("CAPTURE_APP_IDENTITY");
        }
        String[] owners = pm.getPackagesForUid(app.applicationInfo.uid);
        if (owners == null || owners.length != 1 || !APP.equals(owners[0])) throw new IOException("CAPTURE_APP_UID");
        return app.applicationInfo.uid;
    }

    void onEntry(Object pipeline, Object entry, Supplier<Object> entrySbn, String displayText) {
        IslandCaptureRuntime.Grant grant = runtime.current();
        if (grant == null || Looper.myLooper() != main.getLooper() || pipeline == null || entry == null) return;
        try {
            Object source = entrySbn.get();
            if (!(source instanceof StatusBarNotification)) return;
            StatusBarNotification sbn = (StatusBarNotification) source;
            UserHandle user = sbn.getUser();
            if (!Process.myUserHandle().equals(user) || !runtime.canUse()) return;
            int notificationUser = userId(user);
            if (notificationUser < 0 || notificationUser != grant.user) return;
            String key = sbn.getKey();
            // 已核对样本的 getAllNotifs 返回主线程 live 集合。只复核这一 Entry 的存活和引用。
            runtime.offerActiveEntry(grant, sbn.getPackageName(), notificationUser, sbn.getUid(), key,
                    entry, sbn, () -> liveMember(pipeline, entry), entrySbn,
                    () -> true, () -> readScalars(sbn, displayText));
        } catch (RuntimeException | LinkageError failure) {
            // 不记录通知内容、key 或反射异常载荷。未知来源直接丢弃该次候选。
        }
    }

    private static Object liveMember(Object pipeline, Object entry) {
        Object all = XposedHelpers.callMethod(pipeline, "getAllNotifs");
        if (!(all instanceof Collection) || ((Collection<?>) all).size() > 2048) return null;
        for (Object member : (Collection<?>) all) if (member == entry) return entry;
        return null;
    }

    IslandCaptureRuntime.Grant activeGrant() { return runtime.current(); }

    void onOs3Entry(IslandCaptureRuntime.Grant grant, Object entry, Supplier<StatusBarNotification> entrySbn,
            Function<String, Object> liveEntry, BooleanSupplier notCanceled, String displayText) {
        if (mode != 3 || grant == null || entry == null || Looper.myLooper() != main.getLooper()) return;
        try {
            if (!runtime.canUse()) return;
            StatusBarNotification sbn = entrySbn.get();
            if (sbn == null || runtime.current() != grant) return;
            UserHandle user = sbn.getUser();
            if (!Process.myUserHandle().equals(user)) return;
            int notificationUser = userId(user);
            if (notificationUser != grant.user) return;
            String key = sbn.getKey();
            // 同 key 的旧 Entry、旧 SBN 及仍被延长生命周期的已取消项都不能进入正文读取。
            runtime.offerActiveEntry(grant, sbn.getPackageName(), notificationUser, sbn.getUid(), key,
                    entry, sbn, () -> liveEntry.apply(key), entrySbn, notCanceled, () -> readScalars(sbn, displayText));
        } catch (RuntimeException | LinkageError failure) {
            // 反射结果未知时跳过。异常可能包含参数，不写日志。
        }
    }

    private static int userId(UserHandle user) {
        Object result = XposedHelpers.callMethod(user, "getIdentifier");
        return result instanceof Integer ? (Integer) result : -1;
    }

    private static IslandCaptureInputAdapter.Input readScalars(StatusBarNotification sbn, String displayText) {
        Notification notification = sbn.getNotification();
        Bundle extras = notification == null ? null : notification.extras;
        return IslandCaptureInputAdapter.readFocus(key -> extras == null ? null : extras.get(key),
                Notification.EXTRA_TEXT, value -> value instanceof RemoteViews, displayText);
    }

    private final class Bridge extends Binder {
        final int uid;
        volatile IBinder owner;
        Bridge(int uid) { this.uid = uid; }
        boolean live() { return owner != null && owner.isBinderAlive(); }

        @Override protected boolean onTransact(int code, Parcel in, Parcel out, int flags) throws RemoteException {
            if (code < IslandCaptureIpc.STATUS || code > IslandCaptureIpc.CONSUME_SAVE) return false;
            if (Binder.getCallingUid() != uid || bridge != this || !live() || out == null || in.dataSize() > 1024) {
                throw new SecurityException("CAPTURE_CALLER_REJECTED");
            }
            in.enforceInterface(IslandCaptureIpc.DESCRIPTOR);
            long identity = Binder.clearCallingIdentity();
            try {
                if (code >= IslandCaptureIpc.VISIBLE_SNIPPETS) serveEditor(code, in, out);
                else if (code >= IslandCaptureIpc.PREVIEW_LIST) servePreview(code, in, out);
                else serve(code, in, out);
                return true;
            } catch (IOException failure) {
                throw new IllegalStateException("CAPTURE_OPERATION_UNAVAILABLE");
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private void serveEditor(int code, Parcel in, Parcel out) throws IOException {
            long generation = in.readLong();
            if (generation <= 0) throw new IOException("CAPTURE_EDITOR_REQUEST_INVALID");
            synchronized (runtime) {
                if (code == IslandCaptureIpc.VISIBLE_SNIPPETS) {
                    int count = in.readInt();
                    if (count < 1 || count > IslandCaptureEditor.MAX_VISIBLE_BATCH || in.dataAvail() != count * Long.BYTES) {
                        throw new IOException("CAPTURE_SNIPPET_REQUEST_INVALID");
                    }
                    long[] handles = new long[count];
                    for (int i = 0; i < count; i++) handles[i] = in.readLong();
                    java.util.List<String> snippets = runtime.visibleSnippets(this, generation, handles);
                    if (snippets == null) throw new IOException("CAPTURE_SNIPPET_UNAVAILABLE");
                    out.writeNoException();
                    out.writeInt(count);
                    for (String snippet : snippets) out.writeString(snippet);
                    verifyPreviewReply(out, IslandCaptureIpc.MAX_SNIPPET_REPLY_BYTES,
                            bridge == this && live() && runtime.previewAvailable(this, generation));
                    return;
                }
                long handle = in.readLong();
                long ticket = in.readLong();
                if (code == IslandCaptureIpc.CHECK_PREVIEW) {
                    if (in.dataAvail() != 0 || !runtime.previewUsable(this, generation, handle, ticket)) {
                        throw new IOException("CAPTURE_PREVIEW_UNAVAILABLE");
                    }
                    out.writeNoException();
                    return;
                }
                if (!IslandRuleProvider.trusted(context, Process.myUid(), IslandRuleAccess.SYSTEM_UI)) {
                    throw new IOException("CAPTURE_SAVE_SOURCE_UNVERIFIED");
                }
                long baseline = in.readLong();
                String digest = in.readString();
                if (digest == null || !digest.matches("[0-9a-f]{64}")) throw new IOException("CAPTURE_SAVE_DIGEST_INVALID");
                if (code == IslandCaptureIpc.CONSUME_SAVE) {
                    if (in.dataAvail() != 0 || !runtime.consumeSave(this, generation, handle, ticket, baseline, digest)) {
                        throw new IOException("CAPTURE_SAVE_REJECTED");
                    }
                    out.writeNoException();
                    return;
                }
                long createdAt = in.readLong();
                int count = in.readInt();
                if (count < 1 || count > IslandCaptureEditor.MAX_SELECTION || in.dataAvail() != count * Integer.BYTES) {
                    throw new IOException("CAPTURE_SAVE_PATHS_INVALID");
                }
                java.util.List<IslandRulePaths.Entry> paths = new java.util.ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int index = in.readInt();
                    if (index < 0 || index >= IslandCapturePreview.MAX_FIELDS) throw new IOException("CAPTURE_SAVE_PATH_INVALID");
                    paths.add(IslandRulePaths.all().get(index));
                }
                long id = runtime.issueSave(this, generation, handle, ticket, baseline, digest, createdAt, paths);
                if (id == 0) throw new IOException("CAPTURE_SAVE_REJECTED");
                out.writeNoException();
                out.writeLong(id);
                verifyPreviewReply(out, IslandCapturePreview.MAX_PAGE_BYTES,
                        runtime.previewUsable(this, generation, handle, ticket));
            }
        }

        private void servePreview(int code, Parcel in, Parcel out) throws IOException {
            int bytes = Long.BYTES;
            if (code != IslandCaptureIpc.PREVIEW_LIST) bytes += Long.BYTES;
            if (code >= IslandCaptureIpc.READ_PREVIEW_PAGE) bytes += Long.BYTES;
            if (code == IslandCaptureIpc.READ_PREVIEW_PAGE) bytes += Integer.BYTES;
            if (in.dataAvail() != bytes) throw new IOException("CAPTURE_PREVIEW_REQUEST_INVALID");
            long generation = in.readLong();
            long handle = code == IslandCaptureIpc.PREVIEW_LIST ? 0 : in.readLong();
            long ticket = code >= IslandCaptureIpc.READ_PREVIEW_PAGE ? in.readLong() : 0;
            int offset = code == IslandCaptureIpc.READ_PREVIEW_PAGE ? in.readInt() : 0;
            if (generation <= 0 || (code != IslandCaptureIpc.PREVIEW_LIST && handle <= 0)
                    || in.dataAvail() != 0) throw new IOException("CAPTURE_PREVIEW_REQUEST_INVALID");
            // 投影发布、淘汰和撤销使用同一把锁。封包后仍要重取实时门禁。
            synchronized (runtime) {
                switch (code) {
                    case IslandCaptureIpc.PREVIEW_LIST: {
                        java.util.List<IslandCapturePreview.Summary> summaries = runtime.previewList(this, generation);
                        if (summaries == null) throw new IOException("CAPTURE_PREVIEW_UNAVAILABLE");
                        out.writeNoException();
                        out.writeInt(summaries.size());
                        for (IslandCapturePreview.Summary item : summaries) {
                            out.writeLong(item.handle);
                            out.writeString(item.packageName);
                            out.writeLong(item.capturedAt);
                            out.writeString(item.sourceKey);
                            out.writeInt(item.fieldCount);
                            out.writeInt(item.uneditable == null ? -1 : item.uneditable.ordinal());
                        }
                        verifyPreviewReply(out, IslandCaptureIpc.MAX_PREVIEW_LIST_BYTES,
                                runtime.previewAvailable(this, generation));
                        return;
                    }
                    case IslandCaptureIpc.OPEN_PREVIEW: {
                        long id = runtime.openPreview(this, generation, handle);
                        if (id == 0) throw new IOException("CAPTURE_PREVIEW_UNAVAILABLE");
                        out.writeNoException();
                        out.writeLong(id);
                        verifyPreviewReply(out, IslandCapturePreview.MAX_PAGE_BYTES,
                                runtime.previewUsable(this, generation, handle, id));
                        return;
                    }
                    case IslandCaptureIpc.READ_PREVIEW_PAGE: {
                        IslandCapturePreview.Page page = runtime.previewPage(this, generation, handle, ticket, offset);
                        if (page == null) throw new IOException("CAPTURE_PREVIEW_UNAVAILABLE");
                        out.writeNoException();
                        IslandCaptureIpc.writePreviewPage(out, page);
                        verifyPreviewReply(out, IslandCapturePreview.MAX_PAGE_BYTES,
                                runtime.previewUsable(this, generation, handle, ticket));
                        return;
                    }
                    case IslandCaptureIpc.CLOSE_PREVIEW:
                        if (!runtime.closePreview(this, generation, handle, ticket)) {
                            throw new IOException("CAPTURE_PREVIEW_UNAVAILABLE");
                        }
                        out.writeNoException();
                        return;
                    default: throw new IOException("CAPTURE_PREVIEW_REQUEST_INVALID");
                }
            }
        }

        private void verifyPreviewReply(Parcel out, int byteLimit, boolean authorized) throws IOException {
            if (authorized && out.dataSize() <= byteLimit) return;
            out.setDataSize(0);
            out.setDataPosition(0);
            throw new IOException("CAPTURE_PREVIEW_UNAVAILABLE");
        }

        private void serve(int code, Parcel in, Parcel out) throws IOException {
            switch (code) {
                case IslandCaptureIpc.STATUS: {
                    IslandCaptureRuntime.Status status = runtime.status();
                    out.writeNoException();
                    out.writeInt(status.ready ? 1 : 0);
                    out.writeLong(status.epoch);
                    out.writeLong(status.generation);
                    out.writeString(status.target);
                    out.writeInt(mode);
                    IslandCaptureRuntime.Availability reason = availability();
                    out.writeInt((status.ready ? IslandCaptureRuntime.Availability.READY
                            : reason == IslandCaptureRuntime.Availability.READY
                                    ? IslandCaptureRuntime.Availability.CONNECTION_UNAVAILABLE : reason).ordinal());
                    return;
                }
                case IslandCaptureIpc.START: {
                    long epoch = in.readLong();
                    long generation = 0;
                    synchronized (IslandCaptureSystemUi.this) {
                        if (in.dataAvail() == 0 && bridge == this && live()) {
                            IslandCaptureRuntime.Grant grant = runtime.start(epoch, userId(Process.myUserHandle()));
                            if (grant != null) generation = grant.generation;
                        }
                    }
                    out.writeNoException();
                    out.writeLong(generation);
                    return;
                }
                case IslandCaptureIpc.END:
                    runtime.end(in.readLong());
                    out.writeNoException();
                    return;
                default:
                    throw new IOException("CAPTURE_OPERATION_UNAVAILABLE");
            }
        }
    }
}
