/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** App 私有授权。文件仅保存所批准的修订和条目组合摘要。 */
final class IslandRuleProjectionConsent {
    static final String FILE_NAME = "island-projection-consent-v2";

    static final class Preview {
        final IslandRuleProjection.Snapshot snapshot;
        final boolean approved;
        private final Object generation;

        private Preview(IslandRuleProjection.Snapshot snapshot, boolean approved, Object generation) {
            this.snapshot = snapshot;
            this.approved = approved;
            this.generation = generation;
        }
    }

    private final Path file;
    private final IslandRuleRepository.DirectorySync sync;
    private Object generation = new Object();
    private boolean uncertain;

    IslandRuleProjectionConsent(Path file, IslandRuleRepository.DirectorySync sync) {
        this.file = file;
        this.sync = sync;
    }

    synchronized Preview preview(IslandRuleRepository repository) throws IOException {
        synchronized (repository) {
            IslandRuleProjection.Snapshot snapshot = snapshot(repository);
            return new Preview(snapshot, matches(readGrant(), snapshot), generation);
        }
    }

    synchronized void approve(IslandRuleRepository repository, Preview preview) throws IOException {
        if (preview == null || preview.generation != generation || preview.snapshot.rows.isEmpty()) {
            throw new IOException("RULE_PROJECTION_CONSENT_EXPIRED");
        }
        generation = new Object();
        // 仓库变更须重新展示投影。与仓库提交串行化核验及授权落盘。
        synchronized (repository) {
            IslandRuleProjection.Snapshot current = snapshot(repository);
            if (!matches(grant(preview.snapshot), current)) throw new IOException("RULE_PROJECTION_BASELINE_CHANGED");
            replaceGrant(grant(current));
        }
    }

    synchronized byte[] read(IslandRuleRepository repository) throws IOException {
        Grant grant = readGrant();
        if (grant == null) return null;
        synchronized (repository) {
            if (repository.revision() != grant.revision) return null;
            IslandRuleProjection.Snapshot current = snapshot(repository);
            return matches(grant, current) ? current.payload() : null;
        }
    }

    enum ReductionResult { SAVED, SAVED_REAUTHORIZE }

    static final class Reduction {
        final long revision;
        final ReductionResult result;

        Reduction(long revision, ReductionResult result) {
            this.revision = revision;
            this.result = result;
        }
    }

    ReductionResult reduce(IslandRuleRepository repository, long baseline, String targetId,
            boolean delete) throws IOException {
        return reduceMany(repository, baseline, Collections.singleton(targetId), delete).result;
    }

    /** 仓库校验整批目标和页面修订，提交后才能为剩余条目写入授权。 */
    synchronized Reduction reduceMany(IslandRuleRepository repository, long baseline, Set<String> ids,
            boolean delete) throws IOException {
        Set<String> targets = new HashSet<>(ids);
        synchronized (repository) {
            IslandRuleProjection.Snapshot previous = null;
            ReductionResult result = ReductionResult.SAVED;
            try {
                Grant existing = readGrant();
                if (existing != null && existing.revision == baseline) {
                    IslandRuleProjection.Snapshot before = snapshot(repository);
                    if (matches(existing, before)) previous = before;
                }
            } catch (IOException | RuntimeException unreadableGrant) {
                // 授权不可证实仍允许减少本地规则，页面另行提示重新核对。
                result = ReductionResult.SAVED_REAUTHORIZE;
            }
            long revision = delete ? repository.deleteAll(baseline, targets)
                    : repository.changeEnabled(baseline, targets, false, null);
            if (revision == baseline || previous == null) return new Reduction(revision, result);
            generation = new Object();
            try {
                IslandRuleProjection.Snapshot current = snapshot(repository);
                if (current.revision != revision || !current.retainsAllExcept(previous, targets)) {
                    return new Reduction(revision, ReductionResult.SAVED_REAUTHORIZE);
                }
                if (!current.rows.isEmpty()) replaceGrant(grant(current));
                return new Reduction(revision, ReductionResult.SAVED);
            } catch (IOException | RuntimeException grantFailure) {
                // 清单已提交。新授权可能已重命名但目录同步失败，清除后重新授权。
                try { revoke(); }
                catch (IOException | RuntimeException unavailableStorage) { uncertain = true; }
                return new Reduction(revision, ReductionResult.SAVED_REAUTHORIZE);
            }
        }
    }

    synchronized void revoke() throws IOException {
        generation = new Object();
        // 文件系统报错时先阻断本进程继续读取，只有成功落盘才报告撤销成功。
        uncertain = true;
        Files.deleteIfExists(file);
        sync.sync(file.getParent());
        uncertain = false;
    }

    private static IslandRuleProjection.Snapshot snapshot(IslandRuleRepository repository) throws IOException {
        try (IslandRuleRepository.ReadView view = repository.openRead()) {
            return IslandRuleProjection.snapshot(view);
        }
    }

    private static final class Grant {
        final long revision;
        final String binding;

        Grant(long revision, String binding) {
            this.revision = revision;
            this.binding = binding;
        }
    }

    private static Grant grant(IslandRuleProjection.Snapshot snapshot) {
        return new Grant(snapshot.revision, snapshot.binding);
    }

    private static boolean matches(Grant grant, IslandRuleProjection.Snapshot snapshot) {
        return grant != null && grant.revision == snapshot.revision && grant.binding.equals(snapshot.binding);
    }

    private Grant readGrant() throws IOException {
        if (uncertain) return null;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            long revision = in.readLong();
            byte[] digest = new byte[64];
            in.readFully(digest);
            String binding = new String(digest, StandardCharsets.US_ASCII);
            if (revision <= 0 || !binding.matches("[0-9a-f]{64}") || in.read() != -1) {
                throw new IOException("RULE_PROJECTION_CONSENT_FORMAT");
            }
            return new Grant(revision, binding);
        } catch (NoSuchFileException absent) {
            return null;
        }
    }

    private void replaceGrant(Grant grant) throws IOException {
        uncertain = true;
        Path stage = Files.createTempFile(file.getParent(), "projection-consent-", ".stage");
        try {
            byte[] digest = grant.binding.getBytes(StandardCharsets.US_ASCII);
            ByteBuffer encoded = ByteBuffer.allocate(Long.BYTES + digest.length);
            encoded.putLong(grant.revision).put(digest).flip();
            try (FileChannel writer = FileChannel.open(stage, StandardOpenOption.WRITE)) {
                while (encoded.hasRemaining()) writer.write(encoded);
                writer.force(true);
            }
            Files.move(stage, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            sync.sync(file.getParent());
            uncertain = false;
        } finally {
            Files.deleteIfExists(stage);
        }
    }
}
