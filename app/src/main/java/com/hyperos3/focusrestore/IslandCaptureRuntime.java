/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.ToIntFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 唯一会话所有者。Android 对象在同步读取结束前释放，不进入任何队列。 */
final class IslandCaptureRuntime {
    static final int MAX_PACKAGE_LENGTH = 255;
    static final int MAX_KEY_LENGTH = 4096;
    static final class Grant {
        final long generation;
        final int user;
        Grant(long generation, int user) {
            this.generation = generation;
            this.user = user;
        }
    }
    enum Availability {
        READY, WAITING_SOURCE, UNSUPPORTED_SOURCE, LOCKED, USER_UNAVAILABLE, OBSERVER_UNAVAILABLE, CONNECTION_UNAVAILABLE
    }

    static final class Status {
        final int mode;
        final Availability availability;
        final boolean ready;
        final long epoch;
        final long generation;
        final String target;
        Status(boolean ready, long epoch, long generation, String target) {
            this(0, ready ? Availability.READY : Availability.CONNECTION_UNAVAILABLE,
                    epoch, generation, target);
        }
        Status(int mode, Availability availability, long epoch, long generation, String target) {
            this.mode = mode;
            this.availability = availability;
            this.ready = availability == Availability.READY;
            this.epoch = epoch;
            this.generation = generation;
            this.target = target;
        }
    }

    private static final long PREVIEW_TTL_MS = 60_000;

    static final class PreviewAccess {
        final Object connection;
        final int mainUser;
        PreviewAccess(Object connection, int mainUser) {
            this.connection = connection;
            this.mainUser = mainUser;
        }
    }

    private static final class PreviewTicket {
        final long id;
        final Object connection;
        final IslandCapturePreview record;
        final long expiresAt;
        PreviewTicket(long id, Object connection, IslandCapturePreview record, long expiresAt) {
            this.id = id;
            this.connection = connection;
            this.record = record;
            this.expiresAt = expiresAt;
        }
    }

    private final IslandCaptureSession session = new IslandCaptureSession();
    private final BooleanSupplier environment;
    private final Supplier<PreviewAccess> previewAccess;
    private final LongSupplier elapsedMillis;
    private final ArrayDeque<IslandCapturePreview> previews = new ArrayDeque<>();
    private Object previewConnection;
    private long previewEpoch;
    private long nextPreviewTicket;
    private PreviewTicket previewTicket;
    private SaveTicket saveTicket;
    private long nextSaveTicket;

    private static final class SaveTicket {
        final long id;
        final PreviewTicket source;
        final String digest;
        final long baseline;
        SaveTicket(long id, PreviewTicket source, String digest, long baseline) {
            this.id = id;
            this.source = source;
            this.digest = digest;
            this.baseline = baseline;
        }
    }
    private volatile Grant grant;
    private long generation;

    private final ToIntFunction<String> packageUid;
    private long retainedBytes;

    IslandCaptureRuntime(BooleanSupplier environment, Supplier<PreviewAccess> previewAccess,
            LongSupplier elapsedMillis, ToIntFunction<String> packageUid) {
        this.environment = environment;
        this.previewAccess = previewAccess;
        this.elapsedMillis = elapsedMillis;
        this.packageUid = packageUid;
    }

    synchronized boolean canUse() {
        boolean ready = environment.getAsBoolean();
        if (!ready && grant != null) end();
        return ready;
    }

    synchronized Grant start(long expectedEpoch, int user) {
        if (expectedEpoch != generation || grant != null || user < 0 || !canUse()) return null;
        PreviewAccess access = readPreviewAccess();
        if (access == null || access.mainUser != user || access.connection == null) return null;
        session.setLockState(IslandCaptureSession.LockState.UNLOCKED);
        session.enableLiveHistory();
        previewConnection = access.connection;
        grant = new Grant(++generation, user);
        return grant;
    }

    synchronized void end() {
        generation++;
        grant = null;
        previewConnection = null;
        invalidatePreviews();
        session.invalidateSession();
    }

    synchronized boolean end(long expected) {
        if (grant == null || grant.generation != expected) return false;
        end();
        return true;
    }

    Grant current() { return grant; }

    // SBN 由同步回调持有。结束/新会话和实际标量读取在同一门禁内排序。
    synchronized boolean offer(Grant expected, String pkg, int user, int uid, String key,
            Supplier<IslandCaptureInputAdapter.Input> readScalars) {
        if (expected == null || grant != expected || !validPackage(pkg) || user != expected.user
                || uid < 0 || key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH
                || !previewAllowed(previewConnection, expected.generation)) return false;
        try { if (packageUid.applyAsInt(pkg) != uid) return false; }
        catch (RuntimeException unavailable) { return false; }
        IslandCaptureInputAdapter.Input input = readScalars.get();
        Object connection = previewConnection;
        if (input == null || !previewAllowed(connection, expected.generation)) return false;
        long epoch = previewEpoch;
        // 回调闭包只携带定长摘要和枚举，不重复保留完整输入。
        String snippet = input.snippet;
        IslandCaptureInputAdapter.Uneditable reason = input.reason;
        return session.offer(pkg, user, key, IslandCaptureSession.IdentityAuthorization.CONFIRMED,
                IslandCaptureInputAdapter.notificationSource(input),
                record -> addPreview(expected, connection, epoch, record, reason, snippet)) == IslandCaptureSession.Submission.QUEUED;
    }

    synchronized boolean offerActiveEntry(Grant expected, String pkg, int user, int uid, String key,
            Object entry, Object sbn, Supplier<?> liveEntry, Supplier<?> liveSbn,
            BooleanSupplier notCanceled, Supplier<IslandCaptureInputAdapter.Input> readScalars) {
        BooleanSupplier current = () -> entry != null && sbn != null && liveEntry.get() == entry
                && liveSbn.get() == sbn && notCanceled.getAsBoolean();
        return offer(expected, pkg, user, uid, key, () -> {
            if (!current.getAsBoolean()) return null;
            IslandCaptureInputAdapter.Input input = readScalars.get();
            return current.getAsBoolean() ? input : null;
        });
    }

    synchronized void revokePreviewAccess() {
        if (grant != null) end();
    }

    private void invalidatePreviews() {
        previewEpoch++;
        previews.clear();
        retainedBytes = 0;
        clearPreviewTicket();
    }

    private void clearPreviewTicket() {
        previewTicket = null;
        saveTicket = null;
    }

    private PreviewAccess readPreviewAccess() {
        try { return previewAccess.get(); }
        catch (RuntimeException | LinkageError unavailable) { return null; }
    }

    private boolean previewAllowed(Object connection, long expected) {
        Grant active = grant;
        if (active == null || active.generation != expected || connection == null
                || previewConnection != connection || !canUse()) return false;
        PreviewAccess access = readPreviewAccess();
        if (access == null || access.connection != connection || access.mainUser != active.user) {
            if (grant == active && previewConnection == connection) end();
            return false;
        }
        return grant == active && previewConnection == connection;
    }

    private void addPreview(Grant expected, Object connection, long epoch, IslandCaptureRecord record,
            IslandCaptureInputAdapter.Uneditable reason, String snippet) {
        synchronized (this) {
            if (previewEpoch != epoch || !previewAllowed(connection, expected.generation)) return;
        }
        IslandCapturePreview candidate;
        try { candidate = IslandCapturePreview.project(record, expected.generation, reason, snippet); }
        catch (RuntimeException | LinkageError failure) {
            synchronized (this) { if (previewEpoch == epoch) invalidatePreviews(); }
            return;
        }
        if (candidate == null) return;
        synchronized (this) {
            if (!previewAllowed(connection, expected.generation) || previewEpoch != epoch) return;
            if (candidate.retainedBytes > IslandCaptureSession.MAX_BUFFER_BYTES) return;
            while (previews.size() >= IslandCapturePreview.MAX_RECORDS
                    || candidate.retainedBytes > IslandCaptureSession.MAX_BUFFER_BYTES - retainedBytes) {
                IslandCapturePreview removed = previews.removeFirst();
                retainedBytes -= removed.retainedBytes;
                if (previewTicket != null && previewTicket.record == removed) clearPreviewTicket();
            }
            retainedBytes += candidate.retainedBytes;
            previews.addLast(candidate);
        }
    }

    synchronized boolean previewAvailable(Object connection, long expected) {
        return previewAllowed(connection, expected);
    }

    synchronized List<IslandCapturePreview.Summary> previewList(Object connection, long expected) {
        if (!previewAllowed(connection, expected)) return null;
        long epoch = previewEpoch;
        List<IslandCapturePreview.Summary> summaries = new ArrayList<>(previews.size());
        Iterator<IslandCapturePreview> newestFirst = previews.descendingIterator();
        while (newestFirst.hasNext()) summaries.add(newestFirst.next().summary);
        return previewAllowed(connection, expected) && previewEpoch == epoch
                ? Collections.unmodifiableList(summaries) : null;
    }

    synchronized List<String> visibleSnippets(Object connection, long expected, long[] handles) {
        if (handles.length == 0 || handles.length > IslandCaptureEditor.MAX_VISIBLE_BATCH
                || !previewAllowed(connection, expected)) return null;
        List<String> result = new ArrayList<>(handles.length);
        for (long handle : handles) {
            IslandCapturePreview found = null;
            for (IslandCapturePreview record : previews) {
                if (record.summary.handle == handle) { found = record; break; }
            }
            if (found == null) return null;
            result.add(found.snippet());
        }
        return previewAllowed(connection, expected) ? result : null;
    }

    synchronized long issueSave(Object connection, long expected, long handle, long preview,
            long baseline, String digest, long createdAt, List<IslandRulePaths.Entry> paths) {
        if (baseline < 0 || !previewUsable(connection, expected, handle, preview)) return 0;
        PreviewTicket source = previewTicket;
        IslandCaptureEditor.Candidate candidate = IslandCaptureEditor.candidate(
                source.record.summary.packageName, paths, createdAt, source.record.notificationType);
        if (candidate == null || !candidate.digest.equals(digest) || !source.record.containsPaths(paths)
                || !previewUsable(connection, expected, handle, preview)) return 0;
        saveTicket = new SaveTicket(++nextSaveTicket, source, digest, baseline);
        return saveTicket.id;
    }

    synchronized boolean consumeSave(Object connection, long expected, long handle, long id,
            long baseline, String digest) {
        SaveTicket ticket = saveTicket;
        if (ticket == null || ticket.id != id || ticket.source.connection != connection
                || ticket.source.record.generation != expected || ticket.source.record.summary.handle != handle) return false;
        // 同一票据只允许尝试一次，候选或基线不符也不能重放。
        saveTicket = null;
        return ticket.baseline == baseline && ticket.digest.equals(digest) && previewTicket == ticket.source
                && previewUsable(connection, expected, handle, ticket.source.id);
    }

    synchronized long openPreview(Object connection, long expected, long handle) {
        if (!previewAllowed(connection, expected)) return 0;
        for (IslandCapturePreview record : previews) {
            if (record.summary.handle != handle || record.summary.fieldCount == 0) continue;
            // 预览使用独立负编号，与规则保存票据的正编号区分。
            clearPreviewTicket();
            previewTicket = new PreviewTicket(--nextPreviewTicket, connection, record,
                    elapsedMillis.getAsLong() + PREVIEW_TTL_MS);
            return previewUsable(connection, expected, handle, previewTicket.id) ? previewTicket.id : 0;
        }
        return 0;
    }

    synchronized boolean previewUsable(Object connection, long expected, long handle, long id) {
        if (!previewAllowed(connection, expected)) return false;
        PreviewTicket ticket = previewTicket;
        if (id >= 0 || ticket == null || ticket.id != id || ticket.connection != connection
                || ticket.record.generation != expected || ticket.record.user != grant.user
                || ticket.record.summary.handle != handle) return false;
        if (elapsedMillis.getAsLong() >= ticket.expiresAt) {
            clearPreviewTicket();
            return false;
        }
        return previewTicket == ticket;
    }

    synchronized IslandCapturePreview.Page previewPage(Object connection, long expected, long handle, long id, int offset) {
        if (!previewUsable(connection, expected, handle, id)) return null;
        IslandCapturePreview record = previewTicket.record;
        if (!previewUsable(connection, expected, handle, id)) return null;
        IslandCapturePreview.Page page = record.page(offset);
        return previewUsable(connection, expected, handle, id) ? page : null;
    }

    synchronized boolean closePreview(Object connection, long expected, long handle, long id) {
        if (!previewUsable(connection, expected, handle, id)) return false;
        clearPreviewTicket();
        return true;
    }

    synchronized Status status() {
        boolean ready = canUse();
        return new Status(ready, generation, grant == null ? 0 : grant.generation, null);
    }

    boolean awaitIdle(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
        return session.awaitIdle(timeout, unit);
    }

    static boolean validPackage(String value) {
        return value != null && value.length() <= MAX_PACKAGE_LENGTH
                && value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*");
    }
}
