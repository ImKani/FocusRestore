/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 批量 SAF 的事务和结果合同。规则字节只交给既有仓库处理。 */
final class IslandRulePackSaf {
    static final long TIMEOUT_MS = 60_000;
    private static final int WRITE_CHUNK = 16 * 1024;

    enum Picker { SELECTED, CANCELLED, INVALID }
    enum Stop { CANCELLED, TIMEOUT }
    enum Cleanup { REMOVED, EMPTIED, UNCONFIRMED }

    static Picker picker(boolean accepted, String address, boolean document) {
        if (!accepted) return Picker.CANCELLED;
        if (!document || address == null) return Picker.INVALID;
        try {
            URI uri = URI.create(address);
            if ("content".equals(uri.getScheme()) && uri.getRawAuthority() != null
                    && !uri.getRawAuthority().isEmpty() && uri.getRawFragment() == null) return Picker.SELECTED;
        } catch (IllegalArgumentException malformed) {
            return Picker.INVALID;
        }
        return Picker.INVALID;
    }

    static final class Stopped extends InterruptedIOException {
        private static final long serialVersionUID = 1L;
        final Stop reason;

        Stopped(Stop reason) { this.reason = reason; }
    }

    enum Deadline { NOT_DUE, EXPIRED, COMMIT_PENDING }

    /** 跨页面共用工作位，只有后台退出后才能受理下一次文档操作。 */
    static final class Flight {
        private Object owner;

        synchronized boolean acquire(Object candidate) {
            if (owner != null) return false;
            owner = candidate;
            return true;
        }

        synchronized void release(Object candidate) {
            if (owner == candidate) owner = null;
        }

        synchronized boolean occupied() { return owner != null; }

        <T> T run(Object candidate, Operation operation, Supplier<T> work) {
            try { return work.get(); }
            finally {
                operation.leave();
                release(candidate);
            }
        }
    }

    /** 文档超时与原子切换边界分别处理：后者一旦开始，必须等待实际仓库结果。 */
    static final class Operation {
        private final LongSupplier clock;
        private final long start;
        private Stop stopped;
        private boolean commitStarted;
        private boolean outputConfirmed;
        private boolean exitedBeforeDeadline;
        private boolean timedOut;

        Operation(LongSupplier clock) {
            this.clock = clock;
            start = clock.getAsLong();
        }

        synchronized void leave() { exitedBeforeDeadline = !expired(); }

        synchronized boolean stop(Stop reason) {
            if (commitStarted || outputConfirmed || stopped != null) return false;
            // 界面只登记取消；中断或关闭描述符可能同步进入阻塞的 I/O。
            stopped = reason;
            return true;
        }

        synchronized Deadline deadline() {
            if (timedOut) return Deadline.EXPIRED;
            if (outputConfirmed || exitedBeforeDeadline || !expired()) return Deadline.NOT_DUE;
            if (commitStarted) return Deadline.COMMIT_PENDING;
            timedOut = true;
            stop(Stop.TIMEOUT);
            return Deadline.EXPIRED;
        }

        synchronized long remainingMs() {
            long nanos = TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS) - (clock.getAsLong() - start);
            return nanos <= 0 ? 0 : 1 + (nanos - 1) / 1_000_000;
        }

        private boolean expired() { return remainingMs() == 0; }

        synchronized void check() throws Stopped {
            if (stopped != null) throw new Stopped(stopped);
            if (expired()) throw new Stopped(Stop.TIMEOUT);
        }

        synchronized void confirmOutput() throws Stopped {
            check();
            outputConfirmed = true;
        }

        void publish(IslandRuleRepository.ManifestPublication publication) throws IOException {
            synchronized (this) {
                check();
                // 跨过最终提交边界后，取消不能撤销已经发起的原子切换。
                commitStarted = true;
            }
            publication.publish();
        }

        synchronized boolean canDeliver(boolean current, boolean destroyed) {
            return canUpdate(current, destroyed) && stopped == null && deadline() != Deadline.EXPIRED;
        }

        static boolean canUpdate(boolean current, boolean destroyed) {
            return current && !destroyed;
        }
    }

    interface Environment {
        IslandRuleImportValidator.Environment read() throws IOException;
    }

    interface InputDocument { InputStream open() throws IOException; }

    interface OutputDocument {
        OutputStream open() throws IOException;
        Cleanup discard();
    }

    static final class WriteFailure extends IOException {
        private static final long serialVersionUID = 1L;
        final Cleanup cleanup;
        final Stop stopped;

        WriteFailure(Cleanup cleanup, Stop stopped) {
            this.cleanup = cleanup;
            this.stopped = stopped;
        }
    }

    static IslandRuleRepository.PendingBatch prepare(IslandRuleRepository repository, InputDocument document,
            Environment environment, Operation operation) throws IOException {
        IslandRuleRepository.PendingBatch candidate = null;
        try {
            operation.check();
            IslandRuleImportValidator.Environment checked = environment.read();
            try (InputStream input = new FilterInputStream(document.open()) {
                @Override public int read() throws IOException {
                    operation.check();
                    return in.read();
                }

                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    operation.check();
                    return in.read(bytes, offset, length);
                }
            }) {
                candidate = repository.prepareBatch(input, checked);
            }
            operation.check();
            return candidate;
        } catch (IOException | RuntimeException failure) {
            if (candidate != null) {
                Thread.interrupted();
                try { candidate.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            operation.check();
            throw failure;
        }
    }

    static long commit(IslandRuleRepository repository, IslandRuleRepository.PendingBatch candidate,
            IslandRuleRepository.BatchConfirmation confirmation, Environment environment, Operation operation) throws IOException {
        operation.check();
        IslandRuleImportValidator.Environment checked = environment.read();
        return repository.commitBatch(candidate, confirmation, checked, new IslandRuleRepository.CommitAuthorization() {
            @Override public void consume() throws IOException {
                operation.check();
                IslandRuleImportValidator.Environment latest = environment.read();
                if (checked == null ? latest != null : !checked.sameAs(latest)) {
                    throw new IslandRuleRepository.Failure(IslandRuleRepository.Code.ENVIRONMENT_CHANGED);
                }
            }

            @Override public void publish(IslandRuleRepository.ManifestPublication publication) throws IOException {
                operation.publish(publication);
            }
        });
    }

    static IslandRuleRepository.PackExport prepareExport(IslandRuleRepository repository, Operation operation) throws IOException {
        operation.check();
        IslandRuleRepository.PackExport exported = repository.exportPackSnapshot();
        operation.check();
        return exported;
    }

    static void write(IslandRuleRepository.PackExport exported, OutputDocument document, Operation operation) throws IOException {
        try {
            operation.check();
            try (OutputStream output = document.open()) {
                for (int offset = 0; offset < exported.bytes.length; offset += WRITE_CHUNK) {
                    operation.check();
                    output.write(exported.bytes, offset, Math.min(WRITE_CHUNK, exported.bytes.length - offset));
                }
                operation.check();
                output.flush();
            }
            operation.confirmOutput();
        } catch (IOException | RuntimeException failure) {
            Stop reason = null;
            try { operation.check(); } catch (Stopped stopped) { reason = stopped.reason; }
            throw new WriteFailure(discard(document), reason);
        }
    }

    static Cleanup discard(OutputDocument document) {
        // 写入和描述符关闭返回后，才由同一工作线程接手清理。
        try { return document.discard(); }
        catch (RuntimeException failure) { return Cleanup.UNCONFIRMED; }
    }

    static String timeoutText(boolean output) {
        return output ? "导出未确认，目标文件可能不完整，清理待确认。" : stopped(Stop.TIMEOUT);
    }

    static String summary(IslandRuleRepository.PendingBatch candidate) {
        StringBuilder text = new StringBuilder();
        text.append("当前已保存 ").append(candidate.previousCount)
                .append(" 条。\n规则包 ").append(candidate.entryCount).append(" 条，启用 ").append(candidate.enabledCount).append(" 条。");
        if (candidate.entryCount == 0) return text.append("\n\n确认后将清空全部规则，并保存一个新的空修订。").toString();
        boolean[] unverified = new boolean[candidate.entryCount];
        boolean anyUnverified = false;
        for (IslandRuleRepository.BatchFinding item : candidate.warnings) {
            if ("L2_UNVERIFIABLE".equals(item.finding.code)) {
                unverified[item.declarationIndex] = true;
                anyUnverified = true;
            }
        }
        text.append("\n确认后将整批替换。以下按文件声明顺序排列，同优先级按声明顺序处理。\n");
        if (anyUnverified) text.append("部分规则的设备兼容性暂时无法核实。\n");
        for (int i = 0; i < candidate.entryCount; i++) {
            IslandRuleRepository.BatchRow entry = candidate.row(i);
            text.append('\n').append(i + 1).append(". ").append(entry.enabled ? "启用" : "禁用")
                    .append(" · 优先级 ").append(entry.priority);
            if (unverified[i]) text.append(" · 兼容性待核实");
        }
        return text.toString();
    }

    static String failure(Throwable failure) {
        if (failure instanceof Stopped) return stopped(((Stopped) failure).reason);
        if (failure instanceof WriteFailure) {
            WriteFailure write = (WriteFailure) failure;
            String prefix = write.stopped == null ? "规则包写入失败。" : stopped(write.stopped);
            return prefix + cleanupText(write.cleanup);
        }
        if (failure instanceof IslandRulePack.Failure) {
            IslandRulePack.Code code = ((IslandRulePack.Failure) failure).code;
            if (code == IslandRulePack.Code.PACK_SIZE) return "规则包超过 2,122,796 字节。";
            return "规则包格式或完整性校验失败，请重新选择完整的规则包。";
        }
        if (failure instanceof IslandRuleRepository.BatchRejected) {
            for (IslandRuleRepository.BatchFinding item : ((IslandRuleRepository.BatchRejected) failure).findings) {
                if (item.finding.blocking) return "规则包第 " + (item.declarationIndex + 1) + " 条未通过校验，整批未保存。";
            }
        }
        if (failure instanceof IslandRuleRepository.Failure) {
            switch (((IslandRuleRepository.Failure) failure).code) {
                case REPOSITORY_CAPACITY: return "规则仓库空间不足，已保留当前修订。";
                case BASELINE_CHANGED: return "规则仓库已更新，请重新导入并确认。";
                case ENVIRONMENT_CHANGED: return "SystemUI 版本信息已变化，请重新导入并确认。";
                case UNINITIALIZED: return "App 尚无已保存的规则修订。";
                case VALIDATION: return "规则校验失败，整批未保存。";
                default: return "规则仓库无法完成操作。";
            }
        }
        return "规则包操作失败，请检查文档访问和规则仓库。";
    }

    static String cleanupText(Cleanup cleanup) {
        switch (cleanup) {
            case REMOVED: return "已删除未完成的文件。";
            case EMPTIED: return "已清空未完成的文件。";
            default: return "无法确认清理，请在文件管理器中删除未完成的文件。";
        }
    }

    static String stopped(Stop stop) { return stop == Stop.TIMEOUT ? "规则包操作超时。" : "已取消规则包操作。"; }

    private IslandRulePackSaf() {}
}
