/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.hyperos3.focusrestore.IslandCaptureRecord.Reason;
import com.hyperos3.focusrestore.IslandCaptureRecord.State;

/**
 * 纯 Java、进程内 C2-3 捕获器。
 * 合同来源：docs/capture-export-spec.md §3.2、§7.2、§8.1、§12.1（2026-11-18）。
 * 不连接 Hook、Provider、设置或导出；实例所有者负责传入真实锁屏及独立身份授权结论。
 */
public final class IslandCaptureSession implements AutoCloseable {
    public static final int MAX_ITEMS = 64;
    public static final int MAX_FIELD_BYTES = 65536;
    public static final int MAX_RECORD_BYTES = 196608;
    public static final long MAX_BUFFER_BYTES = 2097152L;

    public enum LockState { LOCKED, UNLOCKED, UNKNOWN }
    public enum IdentityAuthorization { CONFIRMED, UNVERIFIED, DENIED }
    public enum Submission { QUEUED, DISABLED, NOT_ALLOWLISTED, AUTH_UNVERIFIED, QUEUE_FULL }

    /**
     * 只由工作线程调用。调用方必须提供脱离 View/Entry 的受限标量读取源，
     * 不得借此捕获任意通知/Bundle 对象或进行序列化、IO、网络。
     * 锁屏准入检查不等于能够中断已经进入的任意回调；最终发布仍检查代次与锁屏。
     */
    public interface BodySource { BodyFields read() throws Exception; }

    /** 保留 Object 类型用于区分错误类型与缺失；只接受真实 String，不调用 toString。 */
    public static final class BodyFields {
        public final Object bodyText;
        public final Object rawParamJSON;
        public final Object extrasJson;
        final String sourceKey;

        public BodyFields(Object bodyText, Object rawParamJSON, Object extrasJson) {
            this(bodyText, rawParamJSON, extrasJson, null);
        }

        BodyFields(Object bodyText, Object rawParamJSON, Object extrasJson, String sourceKey) {
            this.bodyText = bodyText;
            this.rawParamJSON = rawParamJSON;
            this.extrasJson = extrasJson;
            this.sourceKey = sourceKey;
        }
    }

    private static final BodyFields RECENT_ONLY = new BodyFields(null, null, null);

    static BodyFields recentOnlyFields() { return RECENT_ONLY; }

    /** 只含无正文计数，生命周期累计，clear 不重置计数。 */
    public static final class Counters {
        public final long droppedQueueFull;
        public final long droppedOversize;
        public final long droppedCompletedBufferFull;
        public final long droppedSessionStale;
        public final long rejectedDisabled;
        public final long rejectedNotAllowlisted;
        public final long rejectedAuthUnverified;
        public final long lockedPlaceholders;

        private Counters(IslandCaptureSession session) {
            droppedQueueFull = session.droppedQueueFull;
            droppedOversize = session.droppedOversize;
            droppedCompletedBufferFull = session.droppedCompletedBufferFull;
            droppedSessionStale = session.droppedSessionStale;
            rejectedDisabled = session.rejectedDisabled;
            rejectedNotAllowlisted = session.rejectedNotAllowlisted;
            rejectedAuthUnverified = session.rejectedAuthUnverified;
            lockedPlaceholders = session.lockedPlaceholders;
        }
    }

    private static final class Pending {
        final long generation;
        final long sequence;
        final long capturedAt;
        final Reason fixedReason;
        final String packageName;
        final Integer userId;
        final String notificationKey;
        final BodySource source;
        final Consumer<IslandCaptureRecord> onCaptured;
        final long reservation;

        Pending(long generation, long sequence, Reason fixedReason, String packageName,
                Integer userId, String notificationKey, BodySource source,
                Consumer<IslandCaptureRecord> onCaptured) {
            this.generation = generation;
            this.sequence = sequence;
            this.capturedAt = System.currentTimeMillis();
            this.fixedReason = fixedReason;
            boolean body = fixedReason == null;
            this.packageName = body ? packageName : null;
            this.userId = body ? userId : null;
            this.notificationKey = body ? notificationKey : null;
            this.source = body ? source : null;
            this.onCaptured = body ? onCaptured : null;
            // 未读正文时不能相信调用方声明的长度，以合同单条上界预留，绝不读取源估算。
            this.reservation = body ? MAX_RECORD_BYTES : 0;
        }
    }

    private final Object monitor = new Object();
    private final ArrayDeque<Pending> pending = new ArrayDeque<Pending>();
    private final ArrayList<IslandCaptureRecord> completed = new ArrayList<IslandCaptureRecord>();
    private Set<String> allowlist = Collections.emptySet();
    private LockState lockState = LockState.UNKNOWN;
    private boolean enabled;
    private boolean closed;
    private Thread worker;
    private int active;
    private long generation;
    private long sequence;
    private long pendingReservedBytes;
    private long completedBytes;
    private long droppedQueueFull;
    private long droppedOversize;
    private long droppedCompletedBufferFull;
    private long droppedSessionStale;
    private long rejectedDisabled;
    private long rejectedNotAllowlisted;
    private long rejectedAuthUnverified;
    private long lockedPlaceholders;

    public IslandCaptureSession() { }

    /** 显式新会话。空/未知白名单仍全部拒绝；不会由规则或配置缺失自动调用。 */
    private boolean liveHistory;
    /** 活动捕获由 Runtime 按真实包 UID 验证并持有唯一的最近记录环。 */
    void enableLiveHistory() { enable(Collections.emptySet(), true); }

    public void enable(Set<String> packages) { enable(packages, false); }

    private void enable(Set<String> packages, boolean liveHistory) {
        Set<String> copy = new HashSet<String>();
        if (packages != null) {
            for (String name : packages) {
                if (name != null && !name.isEmpty()) copy.add(name);
            }
        }
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("capture session is closed");
            clearGeneration();
            this.liveHistory = liveHistory;
            allowlist = Collections.unmodifiableSet(copy);
            enabled = true;
            if (worker == null) {
                worker = new Thread(new Runnable() {
                    @Override public void run() { workerLoop(); }
                }, "FocusRestore-capture");
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    public void disable() { invalidateSession(); }

    /** 主动失效并关闭捕获；不推断锁屏/profile 的会话策略。 */
    public void invalidateSession() {
        synchronized (monitor) {
            enabled = false;
            clearGeneration();
        }
    }

    /** 主动清除当前内存数据并递增代次；不改调用方的显式启用选择。 */
    public void clear() {
        synchronized (monitor) { clearGeneration(); }
    }

    private void clearGeneration() {
        generation++;
        droppedSessionStale += pending.size();
        pending.clear();
        pendingReservedBytes = 0;
        completed.clear();
        completedBytes = 0;
        monitor.notifyAll();
    }

    /** 只更新正文准入状态；不清历史、不增代次、不自动恢复或补排任务。 */
    public void setLockState(LockState state) {
        synchronized (monitor) { lockState = state == null ? LockState.UNKNOWN : state; }
    }

    /**
     * 热路径：只校验元数据、计数及入队，不调用 source，不等待容量或工作线程。
     * 队列/关闭等无法保存的新事件只返回固定状态并计数，不另建无界拒绝队列。
     */
    public Submission offer(String packageName, Integer userId, String notificationKey,
            IdentityAuthorization authorization, BodySource source) {
        return offer(packageName, userId, notificationKey, authorization, source, null);
    }

    Submission offer(String packageName, Integer userId, String notificationKey,
            IdentityAuthorization authorization, BodySource source, Consumer<IslandCaptureRecord> onCaptured) {
        synchronized (monitor) {
            if (!enabled || closed) {
                rejectedDisabled++;
                return Submission.DISABLED;
            }
            if (packageName == null || packageName.isEmpty() || (!liveHistory && !allowlist.contains(packageName))) {
                rejectedNotAllowlisted++;
                return Submission.NOT_ALLOWLISTED;
            }
            Reason reason = null;
            if (authorization != IdentityAuthorization.CONFIRMED) {
                rejectedAuthUnverified++;
                reason = Reason.AUTH_UNVERIFIED;
            } else if (lockState != LockState.UNLOCKED) {
                reason = lockReason();
            }
            long reservation = reason == null ? MAX_RECORD_BYTES : 0;
            if (pending.size() >= MAX_ITEMS || pendingReservedBytes + reservation > MAX_BUFFER_BYTES) {
                droppedQueueFull++;
                return Submission.QUEUE_FULL;
            }
            Pending item = new Pending(generation, ++sequence, reason,
                    packageName, userId, notificationKey, source, onCaptured);
            pending.addLast(item);
            pendingReservedBytes += item.reservation;
            monitor.notifyAll();
            return reason == Reason.AUTH_UNVERIFIED ? Submission.AUTH_UNVERIFIED : Submission.QUEUED;
        }
    }

    public List<IslandCaptureRecord> records() {
        synchronized (monitor) {
            return Collections.unmodifiableList(new ArrayList<IslandCaptureRecord>(completed));
        }
    }

    public Counters counters() { synchronized (monitor) { return new Counters(this); } }
    public int pendingCount() { synchronized (monitor) { return pending.size(); } }
    /** 未知正文的保守预留量，不伪称已读取的实际字符串字节。 */
    public long pendingReservedBodyBytes() { synchronized (monitor) { return pendingReservedBytes; } }
    public long completedBodyBytes() { synchronized (monitor) { return completedBytes; } }

    /** 非捕获入口的显式等待；不等待导出。可用于所有者收尾及 JVM 受控同步。 */
    public boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long remaining = unit.toNanos(timeout);
        long start = System.nanoTime();
        synchronized (monitor) {
            if (Thread.currentThread() == worker) throw new IllegalStateException("worker cannot await itself");
            while (!pending.isEmpty() || active != 0) {
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                long now = System.nanoTime();
                remaining -= now - start;
                start = now;
            }
            return true;
        }
    }

    @Override public void close() {
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            enabled = false;
            clearGeneration();
        }
    }

    private void workerLoop() {
        while (true) {
            Pending item;
            synchronized (monitor) {
                while (pending.isEmpty() && !closed) {
                    try { monitor.wait(); }
                    catch (InterruptedException ignored) {
                        // 关闭由代次/closed 控制；中断本身不得复活或清空授权任务。
                    }
                }
                if (closed) return;
                item = pending.removeFirst();
                pendingReservedBytes -= item.reservation;
                active++;
            }
            try {
                process(item);
            } catch (Throwable failure) {
                propagateFatal(failure);
                // 不传播或保存异常文本；该条失败仍按固定错误记录，后续条目继续。
                publish(item, withoutBody(item, State.READ_ERROR, Reason.READ_FAILED));
            } finally {
                synchronized (monitor) {
                    active--;
                    monitor.notifyAll();
                }
            }
        }
    }

    private void process(Pending item) {
        synchronized (monitor) {
            if (stale(item)) { droppedSessionStale++; return; }
            if (item.fixedReason != null) {
                publish(item, withoutBody(item,
                        item.fixedReason == Reason.AUTH_UNVERIFIED ? State.REJECTED : State.LOCKED_PLACEHOLDER,
                        item.fixedReason));
                return;
            }
            // 准入时二次核对；已锁定的任务不调用源，也不会解锁后回填锁屏正文。
            if (lockState != LockState.UNLOCKED) {
                publish(item, withoutBody(item, State.LOCKED_PLACEHOLDER, lockReason()));
                return;
            }
        }
        if (item.userId == null || item.notificationKey == null || item.source == null) {
            publish(item, withoutBody(item, State.READ_ERROR, Reason.MISSING_FIELD));
            return;
        }
        BodyFields fields;
        try { fields = item.source.read(); }
        catch (Throwable failure) {
            propagateFatal(failure);
            publish(item, withoutBody(item, State.READ_ERROR, Reason.READ_FAILED));
            return;
        }
        if (fields == RECENT_ONLY) {
            synchronized (monitor) {
                if (!liveHistory || stale(item)) return;
            }
            deliver(item, new IslandCaptureRecord(item.generation, item.sequence, item.capturedAt,
                    State.CAPTURED, null, item.packageName, item.userId, null, null, null, null, 0));
            return;
        }
        if (fields == null || (fields.bodyText == null && fields.sourceKey == null)
                || fields.rawParamJSON == null || fields.extrasJson == null) {
            publish(item, withoutBody(item, State.READ_ERROR, Reason.MISSING_FIELD));
            return;
        }
        if ((fields.bodyText != null && !(fields.bodyText instanceof String)) || !(fields.rawParamJSON instanceof String)
                || !(fields.extrasJson instanceof String)) {
            publish(item, withoutBody(item, State.READ_ERROR, Reason.TYPE_MISMATCH));
            return;
        }
        String body = (String) fields.bodyText;
        String raw = (String) fields.rawParamJSON;
        String extras = (String) fields.extrasJson;
        int bodySize = body == null ? 0 : boundedUtf8Bytes(body);
        int rawSize = boundedUtf8Bytes(raw);
        int extrasSize = boundedUtf8Bytes(extras);
        long bytes = (long) bodySize + rawSize + extrasSize;
        if (bodySize > MAX_FIELD_BYTES || rawSize > MAX_FIELD_BYTES || extrasSize > MAX_FIELD_BYTES
                || bytes > MAX_RECORD_BYTES) {
            synchronized (monitor) {
                if (stale(item)) { droppedSessionStale++; return; }
                droppedOversize++;
            }
            publish(item, withoutBody(item, State.REJECTED, Reason.OVERSIZE));
            return;
        }
        IslandCaptureRecord record = new IslandCaptureRecord(item.generation, item.sequence, item.capturedAt,
                State.CAPTURED, null, item.packageName, item.userId, item.notificationKey,
                body, raw, extras, bytes, fields.sourceKey);
        deliver(item, record);
    }

    private void deliver(Pending item, IslandCaptureRecord record) {
        if (publish(item, record) && item.onCaptured != null) {
            // 旧列表满时仍可提供投影。释放 monitor 才调用所有者，避免和 Runtime 门禁反序加锁。
            try { item.onCaptured.accept(record); }
            catch (Throwable failure) {
                // 派生视图的失败不追加旧 READ_ERROR 记录。
                propagateFatal(failure);
            }
        }
    }

    // 最多检查到首个超额点，不为 UTF-8 编码额外分配大数组；孤立代理项按 Java UTF-8 的 '?' 替换计1。
    private static int boundedUtf8Bytes(String text) {
        int size = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x7f) size++;
            else if (c <= 0x7ff) size += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) { size += 4; i++; }
            else if (Character.isSurrogate(c)) size++;
            else size += 3;
            if (size > MAX_FIELD_BYTES) return size;
        }
        return size;
    }

    private static void propagateFatal(Throwable failure) {
        if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
        if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
    }

    private IslandCaptureRecord withoutBody(Pending item, State state, Reason reason) {
        return new IslandCaptureRecord(item.generation, item.sequence, item.capturedAt,
                state, reason, null, null, null, null, null, null, 0);
    }

    private Reason lockReason() {
        return lockState == LockState.LOCKED ? Reason.LOCKED : Reason.LOCK_STATE_UNKNOWN;
    }

    private boolean stale(Pending item) {
        return item.generation != generation || !enabled || closed;
    }

    private boolean publish(Pending item, IslandCaptureRecord record) {
        synchronized (monitor) {
            if (stale(item)) { droppedSessionStale++; return false; }
            // 不宣称取消在途回调；若读取中锁屏，正文不得进入完成缓冲。
            if (record.state == State.CAPTURED && lockState != LockState.UNLOCKED) {
                record = withoutBody(item, State.LOCKED_PLACEHOLDER, lockReason());
            }
            if (record.state == State.LOCKED_PLACEHOLDER) lockedPlaceholders++;
            // 日常会话只有最近记录一份；completed 仅服务显式旧格式采样。
            if (liveHistory) return record.state == State.CAPTURED;
            if (completed.size() >= MAX_ITEMS || completedBytes + record.bodyBytes > MAX_BUFFER_BYTES) {
                droppedCompletedBufferFull++;
            } else {
                completed.add(record);
                completedBytes += record.bodyBytes;
            }
            return record.state == State.CAPTURED;
        }
    }
}
