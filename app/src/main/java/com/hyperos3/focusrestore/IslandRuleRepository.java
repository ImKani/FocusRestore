/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 单 App 进程持有一个实例；同一锁保护 manifest、暂存和读者保留引用。 */
final class IslandRuleRepository {
    static final int MAX_RULE_BYTES = IslandRuleCodec.MAX_UTF8_BYTES;
    static final int MAX_RULE_FILE_BYTES = 2 * 1024 * 1024;
    static final int MAX_MANIFEST_BYTES = 256 * 1024;
    static final int MAX_ENTRIES = 1024;
    static final int MAX_ID_BYTES = 128;
    static final int MAX_ACTIVE = 50;
    private static final int MAGIC = 0x46524952; // FRIR
    private static final int FORMAT = 1;
    private static final String MANIFEST = "manifest";
    private static final String NEXT = "manifest.next";

    enum Code {
        RULE_SIZE, REPOSITORY_CAPACITY, ACTIVE_LIMIT, METADATA_LIMIT, MANIFEST_LIMIT,
        BASELINE_CHANGED, ENVIRONMENT_CHANGED, VALIDATION, CLOSED, UNKNOWN_RULE,
        CORRUPT_REPOSITORY, UNINITIALIZED, DURABILITY_UNCERTAIN, COMMITTED_CLEANUP_FAILED
    }

    static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        final Code code;

        Failure(Code code) { this(code, null); }
        Failure(Code code, Throwable cause) { super(code.name(), cause); this.code = code; }
    }

    interface DirectorySync { void sync(Path directory) throws IOException; }
    interface ManifestSwitch { void move(Path staged, Path destination) throws IOException; }

    static final class Entry {
        final String id;
        final long revision;
        final boolean enabled;
        final int priority;
        final long order;
        final String sha256;
        final int byteCount;
        final boolean forced;
        final List<String> skipped;
        final String blob;

        private Entry(String id, long revision, boolean enabled, int priority, long order,
                String sha256, int byteCount, String blob, boolean forced, List<String> skipped) {
            this.id = id;
            this.revision = revision;
            this.enabled = enabled;
            this.priority = priority;
            this.order = order;
            this.sha256 = sha256;
            this.byteCount = byteCount;
            this.blob = blob;
            this.forced = forced;
            this.skipped = Collections.unmodifiableList(new ArrayList<>(skipped));
        }
    }

    static final class Manifest {
        final long revision;
        final List<Entry> entries;

        Manifest(long revision, List<Entry> entries) {
            this.revision = revision;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        }
    }

    final class Pending implements AutoCloseable {
        final long baselineRevision;
        final IslandRuleImportValidator.Result validation;
        final Entry entry;
        final boolean empty;
        private final IslandRuleImportValidator.Environment environment;
        private boolean closed;

        private Pending(long baselineRevision, IslandRuleImportValidator.Result validation,
                Entry entry, IslandRuleImportValidator.Environment environment) {
            this(baselineRevision, validation, entry, environment, false);
        }

        private Pending(long baselineRevision, IslandRuleImportValidator.Result validation,
                Entry entry, IslandRuleImportValidator.Environment environment, boolean empty) {
            this.baselineRevision = baselineRevision;
            this.validation = validation;
            this.entry = entry;
            this.environment = environment;
            this.empty = empty;
        }

        boolean canApprove(boolean force) { return empty ? !force : validation.canApprove(force); }

        @Override public void close() throws IOException {
            synchronized (IslandRuleRepository.this) {
                if (closed) return;
                closed = true;
                if (pending.remove(this)) sweep();
            }
        }
    }

    enum BatchConfirmation { REPLACE_ALL, CLEAR_ALL }

    static final class BatchFinding {
        final int declarationIndex;
        final IslandRuleImportValidator.Finding finding;

        BatchFinding(int declarationIndex, IslandRuleImportValidator.Finding finding) {
            this.declarationIndex = declarationIndex;
            this.finding = finding;
        }
    }

    static final class BatchRejected extends IOException {
        private static final long serialVersionUID = 1L;
        final List<BatchFinding> findings;

        BatchRejected(List<BatchFinding> findings) {
            super("FRPK_RULE_VALIDATION");
            this.findings = IslandRule.immutableList(findings, "findings");
        }
    }

    static final class BatchRow {
        final boolean enabled;
        final int priority;

        BatchRow(boolean enabled, int priority) {
            this.enabled = enabled;
            this.priority = priority;
        }
    }

    final class PendingBatch implements AutoCloseable {
        final long baselineRevision;
        final int previousCount;
        final int entryCount;
        final int enabledCount;
        final String packDigest;
        final List<BatchFinding> warnings;
        private final IslandRuleImportValidator.Environment environment;
        private final Manifest next;
        private final byte[] checkedManifest;
        private boolean closed;

        private PendingBatch(Manifest baseline, Manifest next, byte[] checkedManifest, String packDigest,
                IslandRuleImportValidator.Environment environment, List<BatchFinding> warnings) {
            baselineRevision = baseline.revision;
            previousCount = baseline.entries.size();
            entryCount = next.entries.size();
            enabledCount = (int) next.entries.stream().filter(entry -> entry.enabled).count();
            this.next = next;
            this.checkedManifest = checkedManifest;
            this.packDigest = packDigest;
            this.environment = environment;
            this.warnings = IslandRule.immutableList(warnings, "warnings");
        }

        BatchRow row(int index) {
            Entry entry = next.entries.get(index);
            return new BatchRow(entry.enabled, entry.priority);
        }

        private int slots() { return Math.max(1, entryCount); }

        @Override public void close() throws IOException {
            synchronized (IslandRuleRepository.this) {
                if (closed) return;
                closed = true;
                if (pendingBatches.remove(this)) sweep();
            }
        }
    }

    final class ReadView implements AutoCloseable {
        final long revision;
        final List<Entry> entries;
        private final byte[] verifiedManifest;
        private boolean closed;

        private ReadView(Manifest manifest, byte[] verifiedManifest) {
            revision = manifest.revision;
            entries = manifest.entries;
            this.verifiedManifest = verifiedManifest;
        }

        byte[] exportManifest() throws IOException {
            synchronized (IslandRuleRepository.this) {
                if (closed) throw new Failure(Code.CLOSED);
                if (revision <= 0) throw new Failure(Code.UNINITIALIZED);
                return verifiedManifest.clone();
            }
        }

        byte[] readById(String id) throws IOException {
            synchronized (IslandRuleRepository.this) {
                if (closed) throw new Failure(Code.CLOSED);
                for (Entry entry : entries) if (entry.id.equals(id)) return readBlob(entry);
                throw new Failure(Code.UNKNOWN_RULE);
            }
        }

        byte[] read(Entry entry) throws IOException {
            synchronized (IslandRuleRepository.this) {
                if (closed) throw new Failure(Code.CLOSED);
                if (!entries.contains(entry)) throw new Failure(Code.UNKNOWN_RULE);
                return readBlob(entry);
            }
        }

        @Override public void close() throws IOException {
            synchronized (IslandRuleRepository.this) {
                if (closed) return;
                closed = true;
                for (Entry entry : entries) pins.compute(entry.blob, (key, count) -> count == 1 ? null : count - 1);
                sweep();
            }
        }
    }

    private final Path root;
    private final DirectorySync sync;
    private final ManifestSwitch manifestSwitch;
    private final IslandRuleImportValidator validator = new IslandRuleImportValidator();
    private final Set<Pending> pending = new HashSet<>();
    private final Set<PendingBatch> pendingBatches = new HashSet<>();
    private final Map<String, Integer> pins = new HashMap<>();
    private Manifest current;
    // rename 已生效而目录同步失败时，保留两代文件，直到同步成功。
    private boolean needsSync;

    IslandRuleRepository(Path root, DirectorySync sync) throws IOException {
        this(root, sync, (staged, destination) -> Files.move(staged, destination,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    IslandRuleRepository(Path root, DirectorySync sync, ManifestSwitch manifestSwitch) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.sync = Objects.requireNonNull(sync);
        this.manifestSwitch = Objects.requireNonNull(manifestSwitch);
        Files.createDirectories(this.root);
        // 重试初始化也确认父目录，覆盖首次建目录后掉电或同步错误。
        sync.sync(this.root.getParent());
        Path manifest = this.root.resolve(MANIFEST);
        if (Files.notExists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            current = new Manifest(0, Collections.emptyList());
        } else {
            current = readManifest(manifest);
            Set<String> checked = new HashSet<>();
            for (Entry entry : current.entries) if (checked.add(entry.blob)) readBlob(entry);
        }
        sync.sync(this.root);
        Files.deleteIfExists(this.root.resolve(NEXT));
        sweep();
    }

    Pending prepare(InputStream input, String replaceId, boolean enabled, int priority,
            IslandRuleImportValidator.Environment environment) throws IOException {
        final Manifest baseline;
        final Entry replaced;
        synchronized (this) {
            baseline = current;
            replaced = replacedEntry(baseline, replaceId);
        }
        // 文件选择器只读一次，阻塞读取不占用仓库锁。实际写盘前再核对基线、额度。
        byte[] bytes = readBounded(input, MAX_RULE_BYTES, Code.RULE_SIZE);
        if (replaceId == null && isEmptyImport(bytes)) {
            synchronized (this) {
                requireBaseline(baseline.revision);
                if (pendingSlots() >= MAX_ENTRIES) throw new Failure(Code.METADATA_LIMIT);
                Pending candidate = new Pending(baseline.revision, null, null, environment, true);
                pending.add(candidate);
                return candidate;
            }
        }
        IslandRuleImportValidator.Result result = validator.validate(bytes, environment);
        synchronized (this) {
            requireBaseline(baseline.revision);
            if (!result.canApprove(true)) return new Pending(baseline.revision, result, null, environment);
            checkSlot(replaced, enabled);
            sweep();
            if (ledgerBytes() + bytes.length > MAX_RULE_FILE_BYTES) throw new Failure(Code.REPOSITORY_CAPACITY);
            String blob = UUID.randomUUID().toString();
            Entry entry = proposal(replaced, enabled, priority, digest(bytes), bytes.length, blob);
            writeNew(blobPath(blob), bytes);
            Pending candidate = new Pending(baseline.revision, result, entry, environment);
            pending.add(candidate);
            return candidate;
        }
    }

    PendingBatch prepareBatch(InputStream input, IslandRuleImportValidator.Environment environment) throws IOException {
        final Manifest baseline;
        synchronized (this) {
            verifyCurrentManifest();
            baseline = current;
        }
        IslandRulePack.Decoded decoded = IslandRulePack.decode(input);
        List<BatchFinding> findings = new ArrayList<>();
        List<byte[]> originals = new ArrayList<>(decoded.entries.size());
        long total = 0;
        for (IslandRulePack.Entry item : decoded.entries) {
            IslandRulePack.checkInterrupted();
            int index = originals.size();
            byte[] original = item.bytes();
            total += original.length;
            originals.add(original);
            IslandRuleImportValidator.Result result = validator.validate(original, environment);
            for (IslandRuleImportValidator.Finding finding : result.findings) findings.add(new BatchFinding(index, finding));
            if (result.canApprove(false)) {
                try {
                    IslandPortableRule.rebuild(original, result.rule);
                } catch (IOException unsupported) {
                    findings.add(new BatchFinding(index, new IslandRuleImportValidator.Finding(
                            IslandRuleImportValidator.Stage.L1, "PORTABLE_RULE_UNSUPPORTED", "$", "PORTABLE_RULE_SUBSET", true)));
                }
            }
        }
        if (findings.stream().anyMatch(item -> item.finding.blocking)) throw new BatchRejected(findings);
        synchronized (this) {
            requireBaseline(baseline.revision);
            verifyCurrentManifest();
            if (pendingSlots() + Math.max(1, originals.size()) > MAX_ENTRIES) throw new Failure(Code.METADATA_LIMIT);
            sweep();
            // 禁用或内容相同的条目仍各写一个独立文件，按实际文件计费。
            if (total > MAX_RULE_FILE_BYTES - ledgerBytes()) throw new Failure(Code.REPOSITORY_CAPACITY);
            List<Entry> entries = new ArrayList<>(originals.size());
            for (int index = 0; index < originals.size(); index++) {
                IslandRulePack.Entry item = decoded.entries.get(index);
                byte[] original = originals.get(index);
                entries.add(new Entry(UUID.randomUUID().toString(), 1, item.enabled, item.priority, index,
                        digest(original), original.length, UUID.randomUUID().toString(), false, Collections.emptyList()));
            }
            Manifest next = new Manifest(increment(baseline.revision), entries);
            byte[] encoded = encodeManifest(next);
            decodeManifest(encoded);
            PendingBatch candidate = new PendingBatch(baseline, next, encoded, decoded.digest, environment, findings);
            try {
                for (int index = 0; index < originals.size(); index++) {
                    IslandRulePack.checkInterrupted();
                    writeNew(blobPath(entries.get(index).blob), originals.get(index));
                }
                pendingBatches.add(candidate);
                return candidate;
            } catch (IOException failure) {
                try { sweep(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }

    long commitBatch(PendingBatch candidate, BatchConfirmation confirmation,
            IslandRuleImportValidator.Environment environment) throws IOException {
        return commitBatch(candidate, confirmation, environment, null);
    }

    synchronized long commitBatch(PendingBatch candidate, BatchConfirmation confirmation,
            IslandRuleImportValidator.Environment environment, CommitAuthorization authorization) throws IOException {
        if (candidate == null || candidate.closed || !pendingBatches.contains(candidate)) throw new Failure(Code.CLOSED);
        BatchConfirmation expected = candidate.entryCount == 0 ? BatchConfirmation.CLEAR_ALL : BatchConfirmation.REPLACE_ALL;
        if (confirmation != expected) throw new Failure(Code.VALIDATION);
        try {
            requireBaseline(candidate.baselineRevision);
            verifyCurrentManifest();
            if (candidate.environment == null ? environment != null : !candidate.environment.sameAs(environment)) {
                throw new Failure(Code.ENVIRONMENT_CHANGED);
            }
            sweep();
            byte[] encoded = encodeManifest(candidate.next);
            if (!java.util.Arrays.equals(encoded, candidate.checkedManifest)) throw new Failure(Code.CORRUPT_REPOSITORY);
            decodeManifest(encoded);
            return publishManifest(candidate.next, encoded, authorization, () -> {
                pendingBatches.remove(candidate);
                candidate.closed = true;
            }, () -> {
                // 同步候选后，在同一个所有者锁内完成最后一次校验再切换。
                verifyCurrentManifest();
                if (ledgerBytes() > MAX_RULE_FILE_BYTES) throw new Failure(Code.REPOSITORY_CAPACITY);
                for (Entry entry : candidate.next.entries) {
                    IslandRulePack.checkInterrupted();
                    if (!validator.validate(readBlob(entry), environment).canApprove(false)) throw new Failure(Code.VALIDATION);
                }
                if (!java.util.Arrays.equals(encoded, readManifestBytes(root.resolve(NEXT)))) {
                    throw new Failure(Code.CORRUPT_REPOSITORY);
                }
                IslandRulePack.checkInterrupted();
            });
        } catch (IOException failure) {
            // 切换后 publishManifest 已关闭候选，保留原有已提交错误语义。
            if (!candidate.closed) {
                try { Files.deleteIfExists(root.resolve(NEXT)); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                try { candidate.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    static final class PackExport {
        final long revision;
        final byte[] bytes;

        PackExport(long revision, byte[] bytes) {
            this.revision = revision;
            this.bytes = bytes;
        }
    }

    byte[] exportPack() throws IOException { return exportPackSnapshot().bytes; }

    PackExport exportPackSnapshot() throws IOException {
        List<IslandRulePack.Entry> portable = new ArrayList<>();
        long revision;
        try (ReadView view = openRead()) {
            if (view.revision <= 0) throw new Failure(Code.UNINITIALIZED);
            revision = view.revision;
            for (Entry entry : view.entries) {
                IslandRulePack.checkInterrupted();
                byte[] original = view.read(entry);
                IslandRuleImportValidator.Result result = validator.validate(original, null);
                if (!result.canApprove(false)) throw new Failure(Code.VALIDATION);
                try {
                    byte[] rebuilt = IslandPortableRule.rebuild(original, result.rule);
                    portable.add(new IslandRulePack.Entry(UUID.randomUUID(), entry.enabled, entry.priority, rebuilt));
                } catch (IOException unsupported) {
                    throw new IOException("FRPK_EXPORT_UNSUPPORTED entry=" + entry.order, unsupported);
                }
            }
        }
        return new PackExport(revision, IslandRulePack.encode(portable));
    }

    synchronized Pending prepareEdit(String id, boolean enabled, int priority,
            IslandRuleImportValidator.Environment environment) throws IOException {
        Entry replaced = replacedEntry(current, Objects.requireNonNull(id));
        IslandRuleImportValidator.Result result = validator.validate(readBlob(replaced), environment);
        if (!result.canApprove(true)) return new Pending(current.revision, result, null, environment);
        checkSlot(replaced, enabled);
        Entry entry = proposal(replaced, enabled, priority, replaced.sha256, replaced.byteCount, replaced.blob);
        Pending candidate = new Pending(current.revision, result, entry, environment);
        pending.add(candidate);
        return candidate;
    }

    /** 页面必须带回打开规则时的修订。编辑只替换内容，沿用该 ID 的元数据。 */
    synchronized Pending prepareUpdate(long baseline, String id, byte[] bytes,
            IslandRuleImportValidator.Environment environment) throws IOException {
        requireBaseline(baseline);
        verifyCurrentManifest();
        Entry target = replacedEntry(current, Objects.requireNonNull(id));
        return prepare(new ByteArrayInputStream(bytes), target.id, target.enabled, target.priority, environment);
    }

    synchronized long setEnabled(long baseline, String id, boolean enabled,
            IslandRuleImportValidator.Environment environment) throws IOException {
        requireBaseline(baseline);
        verifyCurrentManifest();
        Entry target = replacedEntry(current, Objects.requireNonNull(id));
        if (target.enabled == enabled) return baseline;
        if (enabled) {
            checkSlot(target, true);
            IslandRuleImportValidator.Result validation = validator.validate(readBlob(target), environment);
            // 只能复用该版本已经明确批准的兼容例外，新的不匹配需要重新确认。
            if (!validation.canApprove(target.forced)) throw new Failure(Code.VALIDATION);
        }
        List<Entry> entries = new ArrayList<>(current.entries);
        entries.set((int) target.order, copyState(target, increment(target.revision), enabled, target.order));
        return publishSingleChange(entries);
    }

    synchronized long delete(long baseline, String id) throws IOException {
        requireBaseline(baseline);
        verifyCurrentManifest();
        Entry target = replacedEntry(current, Objects.requireNonNull(id));
        List<Entry> entries = new ArrayList<>(current.entries);
        entries.remove((int) target.order);
        // manifest 要求连续序号；仅压紧删除后的序号，其他规则的版本及相对次序不变。
        for (int index = (int) target.order; index < entries.size(); index++) {
            Entry survivor = entries.get(index);
            entries.set(index, copyState(survivor, survivor.revision, survivor.enabled, index));
        }
        return publishSingleChange(entries);
    }

    private static Entry copyState(Entry item, long revision, boolean enabled, long order) {
        return new Entry(item.id, revision, enabled, item.priority, order, item.sha256, item.byteCount,
                item.blob, item.forced, item.skipped);
    }

    private long publishSingleChange(List<Entry> entries) throws IOException {
        Manifest next = new Manifest(increment(current.revision), entries);
        return publishManifest(next, encodeManifest(next), null, () -> { }, null);
    }

    interface ManifestPublication { void publish() throws IOException; }

    interface CommitAuthorization {
        void consume() throws IOException;
        default void publish(ManifestPublication publication) throws IOException { publication.publish(); }
    }

    synchronized long commit(Pending candidate, boolean force, IslandRuleImportValidator.Environment environment)
            throws IOException {
        return commit(candidate, force, environment, null);
    }

    synchronized long commitCapture(Pending candidate, IslandRuleImportValidator.Environment environment,
            CommitAuthorization authorization) throws IOException {
        if (candidate == null || candidate.empty || authorization == null) throw new Failure(Code.VALIDATION);
        return commit(candidate, false, environment, authorization);
    }

    private long commit(Pending candidate, boolean force, IslandRuleImportValidator.Environment environment,
            CommitAuthorization authorization) throws IOException {
        if (candidate == null || candidate.closed || !pending.contains(candidate)) throw new Failure(Code.CLOSED);
        requireBaseline(candidate.baselineRevision);
        if (candidate.environment == null ? environment != null : !candidate.environment.sameAs(environment)) {
            throw new Failure(Code.ENVIRONMENT_CHANGED);
        }
        if (!candidate.canApprove(force)) throw new Failure(Code.VALIDATION);
        sweep();
        if (candidate.empty) return publish(candidate, Collections.emptyList(), null);
        Entry proposed = candidate.entry;
        // 提交前复核原始字节，批准记录与条目一起进入同一个 manifest。
        readBlob(proposed);
        List<String> skipped = new ArrayList<>();
        if (force) {
            for (IslandRuleImportValidator.Finding finding : candidate.validation.findings) {
                if (finding.stage == IslandRuleImportValidator.Stage.L2 && finding.blocking) {
                    skipped.add(finding.code + " " + finding.path + " " + finding.reason);
                }
            }
        }
        Entry approved = new Entry(proposed.id, proposed.revision, proposed.enabled, proposed.priority,
                proposed.order, proposed.sha256, proposed.byteCount, proposed.blob, !skipped.isEmpty(), skipped);
        List<Entry> entries = new ArrayList<>(current.entries);
        if (approved.order == entries.size()) entries.add(approved);
        else entries.set((int) approved.order, approved);
        return publish(candidate, entries, authorization);
    }

    private long publish(Pending candidate, List<Entry> entries, CommitAuthorization authorization) throws IOException {
        Manifest next = new Manifest(increment(current.revision), entries);
        return publishManifest(next, encodeManifest(next), authorization, () -> {
            pending.remove(candidate);
            candidate.closed = true;
        }, null);
    }

    private long publishManifest(Manifest next, byte[] encoded, CommitAuthorization authorization,
            Runnable onPublished, ManifestPublication beforePublication) throws IOException {
        Path stagedManifest = root.resolve(NEXT);
        Files.deleteIfExists(stagedManifest);
        writeNew(stagedManifest, encoded);
        sync.sync(root);
        ManifestPublication publication = () -> manifestSwitch.move(stagedManifest, root.resolve(MANIFEST));
        if (beforePublication != null) beforePublication.publish();
        if (authorization == null) publication.publish();
        else {
            try {
                authorization.consume();
                authorization.publish(publication);
            } catch (IOException denied) {
                try { Files.deleteIfExists(stagedManifest); }
                catch (IOException cleanup) { denied.addSuppressed(cleanup); }
                throw denied;
            }
        }
        current = next;
        onPublished.run();
        needsSync = true;
        try {
            sync.sync(root);
            needsSync = false;
        } catch (IOException failure) {
            throw new Failure(Code.DURABILITY_UNCERTAIN, failure);
        }
        try {
            sweep();
        } catch (IOException failure) {
            throw new Failure(Code.COMMITTED_CLEANUP_FAILED, failure);
        }
        return next.revision;
    }

    private static boolean isEmptyImport(byte[] bytes) {
        int index = skipWhitespace(bytes, 0);
        if (index == bytes.length || bytes[index++] != '[') return false;
        index = skipWhitespace(bytes, index);
        return index < bytes.length && bytes[index++] == ']' && skipWhitespace(bytes, index) == bytes.length;
    }

    private static int skipWhitespace(byte[] bytes, int index) {
        while (index < bytes.length) {
            byte value = bytes[index];
            if (value != ' ' && value != '\t' && value != '\r' && value != '\n') break;
            index++;
        }
        return index;
    }

    synchronized long revision() throws IOException {
        verifyCurrentManifest();
        return current.revision;
    }

    synchronized ReadView openRead() throws IOException {
        byte[] verified = verifyCurrentManifest();
        for (Entry entry : current.entries) pins.merge(entry.blob, 1, Integer::sum);
        return new ReadView(current, verified);
    }

    private byte[] verifyCurrentManifest() throws IOException {
        ensureDurable();
        Path file = root.resolve(MANIFEST);
        if (current.revision == 0 && Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        byte[] actual = readManifestBytes(file);
        if (current.revision == 0 || !java.util.Arrays.equals(actual, encodeManifest(current))) {
            throw new Failure(Code.CORRUPT_REPOSITORY);
        }
        return actual;
    }

    private void ensureDurable() throws IOException {
        if (!needsSync) return;
        sync.sync(root);
        needsSync = false;
    }

    private void sweep() throws IOException {
        ensureDurable();
        Set<String> keep = new HashSet<>(pins.keySet());
        for (Entry entry : current.entries) keep.add(entry.blob);
        for (Pending candidate : pending) if (candidate.entry != null) keep.add(candidate.entry.blob);
        for (PendingBatch candidate : pendingBatches) for (Entry entry : candidate.next.entries) keep.add(entry.blob);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "rule-*.blob")) {
            for (Path file : files) if (!keep.contains(blobId(file))) Files.delete(file);
        }
    }

    private long ledgerBytes() throws IOException {
        long total = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "rule-*.blob")) {
            for (Path file : files) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new Failure(Code.CORRUPT_REPOSITORY);
                total += Files.size(file);
                if (total > MAX_RULE_FILE_BYTES) return total;
            }
        }
        return total;
    }

    private int pendingSlots() {
        int total = pending.size();
        for (PendingBatch candidate : pendingBatches) total += candidate.slots();
        return total;
    }

    private void checkSlot(Entry replaced, boolean enabled) throws IOException {
        if (pendingSlots() >= MAX_ENTRIES || (replaced == null && current.entries.size() == MAX_ENTRIES)) {
            throw new Failure(Code.METADATA_LIMIT);
        }
        if (enabled && (replaced == null || !replaced.enabled)
                && current.entries.stream().filter(entry -> entry.enabled).count() >= MAX_ACTIVE) {
            throw new Failure(Code.ACTIVE_LIMIT);
        }
    }

    private static Entry replacedEntry(Manifest manifest, String id) throws IOException {
        if (id == null) return null;
        for (Entry entry : manifest.entries) if (entry.id.equals(id)) return entry;
        throw new Failure(Code.UNKNOWN_RULE);
    }

    private Entry proposal(Entry replaced, boolean enabled, int priority, String sha256, int count, String blob)
            throws IOException {
        return new Entry(replaced == null ? UUID.randomUUID().toString() : replaced.id,
                replaced == null ? 1 : increment(replaced.revision), enabled, priority,
                replaced == null ? current.entries.size() : replaced.order, sha256, count, blob,
                false, Collections.emptyList());
    }

    private void requireBaseline(long revision) throws Failure {
        if (revision != current.revision) throw new Failure(Code.BASELINE_CHANGED);
    }

    private static long increment(long revision) throws Failure {
        if (revision == Long.MAX_VALUE) throw new Failure(Code.METADATA_LIMIT);
        return revision + 1;
    }

    private Path blobPath(String blob) { return root.resolve("rule-" + blob + ".blob"); }

    private static String blobId(Path file) {
        String name = file.getFileName().toString();
        return name.substring(5, name.length() - 5);
    }

    private byte[] readBlob(Entry entry) throws IOException {
        Path file = blobPath(entry.blob);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new Failure(Code.CORRUPT_REPOSITORY);
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = readBounded(input, entry.byteCount, Code.CORRUPT_REPOSITORY);
            if (bytes.length != entry.byteCount || !digest(bytes).equals(entry.sha256)) {
                throw new Failure(Code.CORRUPT_REPOSITORY);
            }
            return bytes;
        }
    }

    private static void writeNew(Path path, byte[] bytes) throws IOException {
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) file.write(buffer);
            file.force(true);
        }
    }

    private static byte[] readBounded(InputStream input, int limit, Code failure) throws IOException {
        return readBounded(input, limit, new Failure(failure));
    }

    static byte[] readBounded(InputStream input, int limit, IOException failure) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("RULE_READ_INTERRUPTED");
            int count = input.read(buffer, 0, Math.min(buffer.length, limit + 1 - out.size()));
            if (count < 0) return out.toByteArray();
            if (count == 0) {
                int next = input.read();
                if (next < 0) return out.toByteArray();
                out.write(next);
            } else {
                out.write(buffer, 0, count);
            }
            if (out.size() > limit) throw failure;
        }
    }

    static String digest(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static String hex(byte[] hash) {
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    private static byte[] encodeManifest(Manifest manifest) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(body)) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeLong(manifest.revision);
            out.writeInt(manifest.entries.size());
            for (Entry entry : manifest.entries) {
                out.writeUTF(entry.id);
                out.writeLong(entry.revision);
                out.writeBoolean(entry.enabled);
                out.writeInt(entry.priority);
                out.writeLong(entry.order);
                out.writeUTF(entry.sha256);
                out.writeInt(entry.byteCount);
                out.writeUTF(entry.blob);
                out.writeBoolean(entry.forced);
                out.writeByte(entry.skipped.size());
                for (String item : entry.skipped) out.writeUTF(item);
                if (body.size() + 64 > MAX_MANIFEST_BYTES) throw new Failure(Code.MANIFEST_LIMIT);
            }
            // 校验和覆盖完整 envelope，包括批准字段；规则元数据已由原始字节摘要覆盖。
            out.write(digest(body.toByteArray()).getBytes(StandardCharsets.US_ASCII));
        }
        return body.toByteArray();
    }

    private static Manifest readManifest(Path file) throws IOException {
        return decodeManifest(readManifestBytes(file));
    }

    private static byte[] readManifestBytes(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new Failure(Code.CORRUPT_REPOSITORY);
        try (InputStream input = Files.newInputStream(file)) {
            return readBounded(input, MAX_MANIFEST_BYTES, Code.MANIFEST_LIMIT);
        }
    }

    static Manifest decodeManifest(byte[] bytes) throws IOException {
        if (bytes.length > MAX_MANIFEST_BYTES) throw new Failure(Code.MANIFEST_LIMIT);
        int bodySize = bytes.length - 64;
        if (bodySize < 20 || !digest(java.util.Arrays.copyOf(bytes, bodySize)).equals(
                new String(bytes, bodySize, 64, StandardCharsets.US_ASCII))) {
            throw new Failure(Code.CORRUPT_REPOSITORY);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bodySize))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) throw new Failure(Code.CORRUPT_REPOSITORY);
            long revision = in.readLong();
            int count = in.readInt();
            if (revision <= 0 || count < 0 || count > MAX_ENTRIES) throw new Failure(Code.CORRUPT_REPOSITORY);
            List<Entry> entries = new ArrayList<>(count);
            Set<String> ids = new HashSet<>();
            Map<String, Entry> blobs = new HashMap<>();
            int active = 0;
            long total = 0;
            for (int index = 0; index < count; index++) {
                Entry entry = readEntry(in, index, revision);
                if (!ids.add(entry.id)) throw new Failure(Code.CORRUPT_REPOSITORY);
                if (entry.enabled && ++active > MAX_ACTIVE) throw new Failure(Code.CORRUPT_REPOSITORY);
                Entry previous = blobs.putIfAbsent(entry.blob, entry);
                if (previous == null) total += entry.byteCount;
                else if (previous.byteCount != entry.byteCount || !previous.sha256.equals(entry.sha256)) {
                    throw new Failure(Code.CORRUPT_REPOSITORY);
                }
                if (total > MAX_RULE_FILE_BYTES) throw new Failure(Code.CORRUPT_REPOSITORY);
                entries.add(entry);
            }
            if (in.read() != -1) throw new Failure(Code.CORRUPT_REPOSITORY);
            return new Manifest(revision, entries);
        } catch (IOException failure) {
            if (failure instanceof Failure) throw failure;
            throw new Failure(Code.CORRUPT_REPOSITORY, failure);
        }
    }

    private static Entry readEntry(DataInputStream in, int index, long manifestRevision) throws IOException {
        String id = in.readUTF();
        long revision = in.readLong();
        int enabled = in.readUnsignedByte();
        int priority = in.readInt();
        long order = in.readLong();
        String sha256 = in.readUTF();
        int size = in.readInt();
        String blob = in.readUTF();
        int forced = in.readUnsignedByte();
        int skippedCount = in.readUnsignedByte();
        if (id.getBytes(StandardCharsets.UTF_8).length > MAX_ID_BYTES || !uuid(id)
                || revision <= 0 || revision > manifestRevision || enabled > 1 || order != index
                || !sha256.matches("[0-9a-f]{64}") || size <= 0 || size > MAX_RULE_BYTES || !uuid(blob)
                || forced > 1 || skippedCount != forced) {
            throw new Failure(Code.CORRUPT_REPOSITORY);
        }
        List<String> skipped = new ArrayList<>();
        if (forced == 1) {
            String item = in.readUTF();
            if (!"L2_ENVIRONMENT $.meta.systemUiVersion SYSTEM_UI_VERSION_MISMATCH".equals(item)) {
                throw new Failure(Code.CORRUPT_REPOSITORY);
            }
            skipped.add(item);
        }
        return new Entry(id, revision, enabled == 1, priority, order, sha256, size, blob, forced == 1, skipped);
    }

    private static boolean uuid(String value) {
        return value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
