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
    static final int MAX_SAMPLE_FILE_BYTES = 2 * 1024 * 1024;
    static final int MAX_MANIFEST_BYTES = 256 * 1024;
    static final int MAX_ENTRIES = 1024;
    static final int MAX_ID_BYTES = 128;
    static final int MAX_ACTIVE = 50;
    static final String DIRECTORY_NAME = "island-rules-v2";
    // 私有清单每条额外保存存在位、样本 UUID、SHA-256 和长度。
    static final int MAX_PRIVATE_MANIFEST_BYTES = MAX_MANIFEST_BYTES + (1 + 2 + 36 + 2 + 64 + 4) * MAX_ENTRIES;
    private enum ManifestFormat {
        STRICT_IPC(0x46524952, 1, MAX_MANIFEST_BYTES), // FRIR
        PRIVATE(0x4652494d, 2, MAX_PRIVATE_MANIFEST_BYTES); // FRIM

        final int magic;
        final int version;
        final int limit;

        ManifestFormat(int magic, int version, int limit) {
            this.magic = magic;
            this.version = version;
            this.limit = limit;
        }
    }
    private static final String MANIFEST = "manifest";
    private static final String NEXT = "manifest.next";

    enum Code {
        RULE_SIZE, REPOSITORY_CAPACITY, ACTIVE_LIMIT, METADATA_LIMIT, MANIFEST_LIMIT,
        BASELINE_CHANGED, ENVIRONMENT_CHANGED, VALIDATION, CLOSED, UNKNOWN_RULE,
        CORRUPT_REPOSITORY, UNINITIALIZED, DURABILITY_UNCERTAIN, COMMITTED_CLEANUP_FAILED, SAMPLE_CAPACITY, SAMPLE_CORRUPT
    }

    static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        final Code code;

        Failure(Code code) { this(code, null); }
        Failure(Code code, Throwable cause) { super(code.name(), cause); this.code = code; }
    }

    interface DirectorySync { void sync(Path directory) throws IOException; }
    interface ManifestSwitch { void move(Path staged, Path destination) throws IOException; }

    private static final class SampleRef {
        final String blob;
        final String sha256;
        final int size;

        SampleRef(String blob, String sha256, int size) {
            this.blob = blob;
            this.sha256 = sha256;
            this.size = size;
        }

        static SampleRef create(byte[] bytes) {
            return bytes == null ? null : new SampleRef(UUID.randomUUID().toString(), digest(bytes), bytes.length);
        }
    }

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
        private final SampleRef sample;
        final String blob;

        private Entry(String id, long revision, boolean enabled, int priority, long order,
                String sha256, int byteCount, String blob, boolean forced, List<String> skipped, SampleRef sample) {
            this.id = id;
            this.revision = revision;
            this.enabled = enabled;
            this.priority = priority;
            this.order = order;
            this.sha256 = sha256;
            this.byteCount = byteCount;
            this.blob = blob;
            this.forced = forced;
            this.sample = sample;
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
        private final IslandRuleImportValidator.Environment environment;
        private boolean closed;

        private Pending(long baselineRevision, IslandRuleImportValidator.Result validation,
                Entry entry, IslandRuleImportValidator.Environment environment) {
            this.baselineRevision = baselineRevision;
            this.validation = validation;
            this.entry = entry;
            this.environment = environment;
        }

        boolean canApprove(boolean force) { return validation.canApprove(force); }

        @Override public void close() throws IOException {
            synchronized (IslandRuleRepository.this) {
                if (closed) return;
                closed = true;
                if (pending.remove(this)) sweep();
            }
        }
    }

    enum BatchRowStatus { ADD, DUPLICATE, FAILED }

    enum BatchResultStatus { ADDED, REPLACED, SKIPPED, FAILED }

    static final class BatchRuleSummary {
        final String packageName;
        final IslandNotificationType type;
        final List<String> paths;
        final boolean enabled;
        final int priority;

        private BatchRuleSummary(IslandRule rule, boolean enabled, int priority) {
            packageName = rule.meta.packageName;
            type = rule.notificationType;
            Map<String, String> bindings = new HashMap<>();
            for (ExtractSpec field : rule.extract) bindings.put(field.id, field.path);
            List<String> ordered = new ArrayList<>();
            for (DisplaySpec.Projection projection : rule.display.order) {
                String path = bindings.get(projection.ref);
                if (path != null) ordered.add(path);
            }
            paths = Collections.unmodifiableList(ordered);
            this.enabled = enabled;
            this.priority = priority;
        }
    }

    static final class BatchRow {
        final int declarationIndex;
        final BatchRowStatus status;
        final BatchRuleSummary incoming;
        final BatchRuleSummary local;
        final String failureCode;
        final boolean otherContentChanged;
        final List<IslandRuleImportValidator.Finding> findings;

        private BatchRow(int declarationIndex, BatchRowStatus status, BatchRuleSummary incoming, BatchRuleSummary local,
                String failureCode, boolean otherContentChanged, List<IslandRuleImportValidator.Finding> findings) {
            this.declarationIndex = declarationIndex;
            this.status = status;
            this.incoming = incoming;
            this.local = local;
            this.failureCode = failureCode;
            this.otherContentChanged = otherContentChanged;
            this.findings = IslandRule.immutableList(findings, "findings");
        }
    }

    static final class BatchResultRow {
        final BatchRow preview;
        final BatchResultStatus status;
        final String failureCode;

        private BatchResultRow(BatchRow preview, BatchResultStatus status) {
            this(preview, status, preview.failureCode);
        }

        private BatchResultRow(BatchRow preview, BatchResultStatus status, String failureCode) {
            this.preview = preview;
            this.status = status;
            this.failureCode = failureCode;
        }
    }

    static final class BatchResult {
        final long revision;
        final boolean changed;
        final List<BatchResultRow> rows;
        final Code warning;

        private BatchResult(long revision, boolean changed, List<BatchResultRow> rows, Code warning) {
            this.revision = revision;
            this.changed = changed;
            this.rows = IslandRule.immutableList(rows, "rows");
            this.warning = warning;
        }

        long confirmableRevision() {
            // 已切换但落盘待确认时，只刷新状态和手动授权入口。
            return changed && warning != Code.DURABILITY_UNCERTAIN ? revision : 0;
        }
    }

    private static final class BatchKey {
        final String packageName;
        final IslandNotificationType type;
        final String document;

        private BatchKey(IslandRule rule, byte[] portable) {
            packageName = rule.meta.packageName;
            type = rule.notificationType;
            // 无捕获类型时只匹配规范化后的整个可移植文档，不能按包名猜测替换对象。
            document = type == null ? new String(portable, StandardCharsets.UTF_8) : null;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof BatchKey)) return false;
            BatchKey key = (BatchKey) other;
            return packageName.equals(key.packageName) && Objects.equals(type, key.type)
                    && Objects.equals(document, key.document);
        }

        @Override public int hashCode() { return Objects.hash(packageName, type, document); }
    }

    private static final class BatchLocal {
        final Entry entry;
        final IslandRule rule;
        final byte[] bytes;

        private BatchLocal(Entry entry, IslandRule rule, byte[] bytes) {
            this.entry = entry;
            this.rule = rule;
            this.bytes = bytes;
        }
    }

    private static final class BatchContent {
        final byte[] rule;
        final byte[] sample;
        final Entry replaced;

        private BatchContent(byte[] rule, byte[] sample, Entry replaced) {
            this.rule = rule;
            this.sample = sample;
            this.replaced = replaced;
        }
    }

    final class PendingBatch implements AutoCloseable {
        final long baselineRevision;
        final int previousCount;
        final String packDigest;
        final List<BatchRow> rows;
        private final IslandRuleImportValidator.Environment environment;
        private final long ruleBytes;
        private final long sampleBytes;
        private List<BatchContent> content;
        private boolean closed;

        private PendingBatch(Manifest baseline, String packDigest, IslandRuleImportValidator.Environment environment,
                List<BatchRow> rows, List<BatchContent> content, long ruleBytes, long sampleBytes) {
            baselineRevision = baseline.revision;
            previousCount = baseline.entries.size();
            this.packDigest = packDigest;
            this.environment = environment;
            this.rows = IslandRule.immutableList(rows, "rows");
            this.content = content;
            this.ruleBytes = ruleBytes;
            this.sampleBytes = sampleBytes;
        }

        private int slots() { return Math.max(1, rows.size()); }

        private void discard() {
            synchronized (IslandRuleRepository.this) {
                closed = true;
                content = Collections.emptyList();
                pendingBatches.remove(this);
            }
        }

        @Override public void close() throws IOException { discard(); }
    }

    final class ReadView implements AutoCloseable {
        final long revision;
        final List<Entry> entries;
        private final Manifest manifest;
        private boolean closed;

        private ReadView(Manifest manifest) {
            revision = manifest.revision;
            entries = manifest.entries;
            this.manifest = manifest;
        }

        byte[] exportManifest() throws IOException {
            synchronized (IslandRuleRepository.this) {
                if (closed) throw new Failure(Code.CLOSED);
                if (revision <= 0) throw new Failure(Code.UNINITIALIZED);
                // 完整规则 IPC 始终按 v1 输出，App 私有样本独立读取。
                return encodeManifest(manifest, ManifestFormat.STRICT_IPC);
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

        long blobModifiedAt(Entry entry) throws IOException {
            synchronized (IslandRuleRepository.this) {
                if (closed) throw new Failure(Code.CLOSED);
                if (!entries.contains(entry)) throw new Failure(Code.UNKNOWN_RULE);
                return Files.getLastModifiedTime(blobPath(entry.blob), LinkOption.NOFOLLOW_LINKS).toMillis();
            }
        }

        IslandRuleSample readSample(Entry entry) throws IOException {
            synchronized (IslandRuleRepository.this) {
                if (closed) throw new Failure(Code.CLOSED);
                if (!entries.contains(entry)) throw new Failure(Code.UNKNOWN_RULE);
                return readSampleFile(entry.sample);
            }
        }

        @Override public void close() throws IOException {
            synchronized (IslandRuleRepository.this) {
                if (closed) return;
                closed = true;
                for (Entry entry : entries) {
                    unpin(pins, entry.blob);
                    if (entry.sample != null) unpin(samplePins, entry.sample.blob);
                }
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
    private final Map<String, Integer> samplePins = new HashMap<>();
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
        return prepare(input, replaceId, enabled, priority, environment, null);
    }

    Pending prepare(InputStream input, String replaceId, boolean enabled, int priority,
            IslandRuleImportValidator.Environment environment, IslandRuleSample sample) throws IOException {
        final Manifest baseline;
        final Entry replaced;
        synchronized (this) {
            baseline = current;
            replaced = replacedEntry(baseline, replaceId);
        }
        // 阻塞读取不占用仓库锁。实际写盘前再核对基线和额度。
        byte[] bytes = readBounded(input, MAX_RULE_BYTES, Code.RULE_SIZE);
        IslandRuleImportValidator.Result result = validator.validate(bytes, environment);
        byte[] sampleBytes = sample == null ? null : sample.encode(true);
        synchronized (this) {
            requireBaseline(baseline.revision);
            if (!result.canApprove(true)) return new Pending(baseline.revision, result, null, environment);
            checkSlot(replaced, enabled);
            sweep();
            if (ledgerBytes() + bytes.length > MAX_RULE_FILE_BYTES) throw new Failure(Code.REPOSITORY_CAPACITY);
            SampleRef sampleRef = replaced == null ? null : replaced.sample;
            if (sampleBytes != null) {
                checkSampleCapacity(sampleBytes.length);
                sampleRef = SampleRef.create(sampleBytes);
            }
            String blob = UUID.randomUUID().toString();
            Entry entry = proposal(replaced, enabled, priority, digest(bytes), bytes.length, blob, sampleRef);
            Pending candidate = new Pending(baseline.revision, result, entry, environment);
            try {
                writeNew(blobPath(blob), bytes);
                if (sampleBytes != null) writeNew(samplePath(sampleRef.blob), sampleBytes);
                pending.add(candidate);
                return candidate;
            } catch (IOException failure) {
                cleanupStaging(failure);
                throw failure;
            }
        }
    }

    PendingBatch prepareBatch(InputStream input, IslandRuleImportValidator.Environment environment) throws IOException {
        final Manifest baseline;
        synchronized (this) {
            verifyCurrentManifest();
            baseline = current;
        }
        // 只在锁外读取 SAF；摘要和边界校验完成前不准备任何条目。
        IslandRulePack.Decoded decoded = IslandRulePack.decode(input);
        synchronized (this) {
            requireBaseline(baseline.revision);
            verifyCurrentManifest();
            if (pendingSlots() + Math.max(1, decoded.entries.size()) > MAX_ENTRIES) {
                throw new Failure(Code.METADATA_LIMIT);
            }
            Map<BatchKey, List<BatchLocal>> local = decoded.entries.isEmpty()
                    ? Collections.emptyMap() : indexBatchBaseline(baseline);
            List<BatchRow> rows = new ArrayList<>(decoded.entries.size());
            List<BatchContent> payloads = new ArrayList<>(decoded.entries.size());
            Set<BatchKey> encountered = new HashSet<>();
            long ruleBytes = 0;
            long sampleBytes = 0;
            for (IslandRulePack.Entry item : decoded.entries) {
                IslandRulePack.checkInterrupted();
                int index = rows.size();
                byte[] ruleBytesForRow = item.bytes();
                IslandRuleImportValidator.Result result = validator.validate(ruleBytesForRow, environment);
                BatchRuleSummary incoming = result.rule == null ? null
                        : new BatchRuleSummary(result.rule, item.enabled, item.priority);
                List<IslandRuleImportValidator.Finding> findings = new ArrayList<>(result.findings);
                String failure = item.rowError == null ? null : "FRPK_" + item.rowError.name();
                byte[] portable = null;
                if (failure == null && !result.canApprove(false)) {
                    failure = findings.stream().filter(finding -> finding.blocking).findFirst()
                            .map(finding -> finding.code).orElse("VALIDATION");
                }
                if (failure == null) {
                    try {
                        portable = IslandPortableRule.rebuild(ruleBytesForRow, result.rule);
                    } catch (IOException unsupported) {
                        failure = "PORTABLE_RULE_UNSUPPORTED";
                        findings.add(new IslandRuleImportValidator.Finding(IslandRuleImportValidator.Stage.L1,
                                failure, "$", "PORTABLE_RULE_SUBSET", true));
                    }
                }
                if (failure != null) {
                    rows.add(new BatchRow(index, BatchRowStatus.FAILED, incoming, null, failure, false, findings));
                    payloads.add(null);
                    continue;
                }

                BatchKey key = new BatchKey(result.rule, portable);
                List<BatchLocal> matches = local.getOrDefault(key, Collections.emptyList());
                if (!encountered.add(key)) failure = "DUPLICATE_IN_PACK";
                else if (matches.size() > 1) failure = "AMBIGUOUS_LOCAL";
                if (failure != null) {
                    rows.add(new BatchRow(index, BatchRowStatus.FAILED, incoming, null, failure, false, findings));
                    payloads.add(null);
                    continue;
                }

                byte[] sample = item.sample == null ? null : item.sample.encode(true);
                BatchLocal match = matches.isEmpty() ? null : matches.get(0);
                BatchRuleSummary previous = match == null ? null
                        : new BatchRuleSummary(match.rule, match.entry.enabled, match.entry.priority);
                boolean otherChanged = match != null && batchOtherContentChanged(match, result.rule, portable, sample);
                rows.add(new BatchRow(index, match == null ? BatchRowStatus.ADD : BatchRowStatus.DUPLICATE,
                        incoming, previous, null, otherChanged, findings));
                payloads.add(new BatchContent(ruleBytesForRow, sample, match == null ? null : match.entry));
                ruleBytes += ruleBytesForRow.length;
                if (sample != null) sampleBytes += sample.length;
            }
            long ownRuleBytes = ruleBytes;
            long ownSampleBytes = sampleBytes;
            for (PendingBatch batch : pendingBatches) {
                ruleBytes += batch.ruleBytes;
                sampleBytes += batch.sampleBytes;
            }
            if (ruleBytes > MAX_RULE_FILE_BYTES) throw new Failure(Code.REPOSITORY_CAPACITY);
            if (sampleBytes > MAX_SAMPLE_FILE_BYTES) throw new Failure(Code.SAMPLE_CAPACITY);
            IslandRulePack.checkInterrupted();
            // 未确认的可选替换不占用磁盘额度。预览内容单独计费，并在关闭时立即释放。
            PendingBatch candidate = new PendingBatch(baseline, decoded.digest, environment, rows, payloads,
                    ownRuleBytes, ownSampleBytes);
            pendingBatches.add(candidate);
            return candidate;
        }
    }

    private Map<BatchKey, List<BatchLocal>> indexBatchBaseline(Manifest baseline) throws IOException {
        Map<BatchKey, List<BatchLocal>> local = new HashMap<>();
        for (Entry entry : baseline.entries) {
            IslandRulePack.checkInterrupted();
            byte[] bytes = readBlob(entry);
            IslandRule rule = validator.validate(bytes, null).rule;
            if (rule == null) throw new Failure(Code.CORRUPT_REPOSITORY);
            byte[] portable = null;
            if (rule.notificationType == null) {
                try { portable = IslandPortableRule.rebuild(bytes, rule); }
                catch (IOException notPortable) { continue; } // 无类型非可移植文档无法与合法包内文档完全相同。
            }
            BatchKey key = new BatchKey(rule, portable);
            local.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new BatchLocal(entry, rule, bytes));
        }
        return local;
    }

    private static boolean batchOtherContentChanged(BatchLocal local, IslandRule nextRule, byte[] incoming, byte[] sample) throws IOException {
        String oldSample = local.entry.sample == null ? null : local.entry.sample.sha256;
        if (!Objects.equals(oldSample, sample == null ? null : digest(sample))) return true;
        Map<String, Object> previous = IslandRuleCodec.portableDocument(local.bytes);
        Map<String, Object> next = IslandRuleCodec.portableDocument(incoming);
        IslandPortableRule.normalizeDefaults(previous);
        // 有序路径单独展示。这里对比其它声明，包括提取属性和显示样式。
        ((Map<?, ?>) previous.get("display")).remove("order");
        ((Map<?, ?>) next.get("display")).remove("order");
        return !previous.equals(next) || outputSlotsChanged(local.rule, nextRule);
    }

    private static boolean outputSlotsChanged(IslandRule local, IslandRule next) {
        Map<String, String> slots = new HashMap<>();
        for (DisplaySpec.Projection output : local.display.order) slots.put(output.ref, output.slot);
        for (DisplaySpec.Projection output : next.display.order) {
            if (slots.containsKey(output.ref) && !output.slot.equals(slots.get(output.ref))) return true;
        }
        return false;
    }

    BatchResult commitBatch(PendingBatch candidate, Set<Integer> replaceIndices,
            IslandRuleImportValidator.Environment environment) throws IOException {
        return commitBatch(candidate, replaceIndices, environment, null);
    }

    synchronized BatchResult commitBatch(PendingBatch candidate, Set<Integer> replaceIndices,
            IslandRuleImportValidator.Environment environment, CommitAuthorization authorization) throws IOException {
        if (candidate == null || candidate.closed || !pendingBatches.contains(candidate)) throw new Failure(Code.CLOSED);
        try {
            requireBaseline(candidate.baselineRevision);
            verifyCurrentManifest();
            if (candidate.environment == null ? environment != null : !candidate.environment.sameAs(environment)) {
                throw new Failure(Code.ENVIRONMENT_CHANGED);
            }
            IslandRulePack.checkInterrupted();
            if (replaceIndices == null) throw new Failure(Code.VALIDATION);
            Set<Integer> confirmed = new HashSet<>(replaceIndices);
            for (Integer index : confirmed) {
                if (index == null || index < 0 || index >= candidate.rows.size()
                        || candidate.rows.get(index).status != BatchRowStatus.DUPLICATE) throw new Failure(Code.VALIDATION);
            }
            return commitBatchRows(candidate, confirmed, environment, authorization);
        } catch (IOException failure) {
            if (!candidate.closed) {
                candidate.discard();
                cleanupStaging(failure);
            }
            throw failure;
        }
    }

    private BatchResult commitBatchRows(PendingBatch candidate, Set<Integer> confirmed,
            IslandRuleImportValidator.Environment environment, CommitAuthorization authorization) throws IOException {
        List<Entry> entries = new ArrayList<>(current.entries);
        List<BatchResultRow> results = new ArrayList<>(candidate.rows.size());
        List<Entry> stagedEntries = new ArrayList<>();
        List<BatchContent> stagedContent = new ArrayList<>();
        int active = (int) entries.stream().filter(entry -> entry.enabled).count();
        long remainingRules = 0;
        long remainingSamples = 0;
        boolean quotaRead = false;
        for (BatchRow row : candidate.rows) {
            IslandRulePack.checkInterrupted();
            if (row.status == BatchRowStatus.FAILED) {
                results.add(new BatchResultRow(row, BatchResultStatus.FAILED));
            } else if (row.status == BatchRowStatus.DUPLICATE && !confirmed.contains(row.declarationIndex)) {
                results.add(new BatchResultRow(row, BatchResultStatus.SKIPPED));
            } else {
                BatchContent payload = candidate.content.get(row.declarationIndex);
                Entry old = payload.replaced;
                if (!quotaRead) {
                    sweep();
                    remainingRules = MAX_RULE_FILE_BYTES - ledgerBytes();
                    remainingSamples = MAX_SAMPLE_FILE_BYTES
                            - ledgerBytes("sample-*.blob", MAX_SAMPLE_FILE_BYTES, Code.SAMPLE_CORRUPT);
                    quotaRead = true;
                }
                int nextActive = active + (row.incoming.enabled ? 1 : 0) - (old != null && old.enabled ? 1 : 0);
                int sampleLength = payload.sample == null ? 0 : payload.sample.length;
                Code limit = null;
                if (old == null && entries.size() == MAX_ENTRIES || old != null && old.revision == Long.MAX_VALUE) {
                    limit = Code.METADATA_LIMIT;
                } else if (nextActive > MAX_ACTIVE) {
                    limit = Code.ACTIVE_LIMIT;
                } else if (payload.rule.length > remainingRules) {
                    limit = Code.REPOSITORY_CAPACITY;
                } else if (sampleLength != 0 && sampleLength > remainingSamples) {
                    limit = Code.SAMPLE_CAPACITY;
                }
                if (limit != null) {
                    results.add(new BatchResultRow(row, BatchResultStatus.FAILED, limit.name()));
                    continue;
                }
                String digest = digest(payload.rule);
                // 兼容例外仅适用于曾获批准的原始内容。
                boolean keepApproval = old != null && old.sha256.equals(digest);
                Entry next = new Entry(old == null ? UUID.randomUUID().toString() : old.id,
                        old == null ? 1 : increment(old.revision), row.incoming.enabled, row.incoming.priority,
                        old == null ? entries.size() : old.order, digest, payload.rule.length,
                        UUID.randomUUID().toString(), keepApproval && old.forced,
                        keepApproval ? old.skipped : Collections.emptyList(), SampleRef.create(payload.sample));
                if (old == null) entries.add(next);
                else entries.set((int) old.order, next);
                stagedEntries.add(next);
                stagedContent.add(payload);
                active = nextActive;
                remainingRules -= payload.rule.length;
                remainingSamples -= sampleLength;
                results.add(new BatchResultRow(row, old == null ? BatchResultStatus.ADDED : BatchResultStatus.REPLACED));
            }
        }
        if (stagedEntries.isEmpty()) {
            // 无变更情况下仍校验环境与授权，但没有文件切换或修订递增。
            if (authorization != null) authorization.consume();
            IslandRulePack.checkInterrupted();
            candidate.close();
            return new BatchResult(current.revision, false, results, null);
        }
        Manifest next = new Manifest(increment(current.revision), entries);
        byte[] encoded = encodeManifest(next);
        decodeManifest(encoded, ManifestFormat.PRIVATE);
        for (int index = 0; index < stagedEntries.size(); index++) {
            IslandRulePack.checkInterrupted();
            Entry entry = stagedEntries.get(index);
            BatchContent payload = stagedContent.get(index);
            writeNew(blobPath(entry.blob), payload.rule);
            if (entry.sample != null) writeNew(samplePath(entry.sample.blob), payload.sample);
        }
        Path stagedManifest = root.resolve(NEXT);
        try {
            long revision = publishManifest(next, encoded, authorization, candidate::discard, () -> {
                verifyCurrentManifest();
                if (ledgerBytes() > MAX_RULE_FILE_BYTES) throw new Failure(Code.REPOSITORY_CAPACITY);
                if (next.entries.stream().anyMatch(entry -> entry.sample != null)) checkSampleCapacity(0);
                Set<Entry> imported = new HashSet<>(stagedEntries);
                for (Entry entry : next.entries) {
                    IslandRulePack.checkInterrupted();
                    byte[] verified = readBlob(entry);
                    if (imported.contains(entry)) {
                        // 本机原有兼容例外可以保留，但不能用它们批准导入的不同内容。
                        if (!validator.validate(verified, environment).canApprove(false)) throw new Failure(Code.VALIDATION);
                        readSampleFile(entry.sample);
                    }
                }
                if (!java.util.Arrays.equals(encoded, readManifestBytes(stagedManifest))) {
                    throw new Failure(Code.CORRUPT_REPOSITORY);
                }
                IslandRulePack.checkInterrupted();
            });
            return new BatchResult(revision, true, results, null);
        } catch (IOException failure) {
            if (candidate.closed && failure instanceof Failure) {
                Code warning = ((Failure) failure).code;
                if (warning == Code.DURABILITY_UNCERTAIN || warning == Code.COMMITTED_CLEANUP_FAILED) {
                    return new BatchResult(next.revision, true, results, warning);
                }
            }
            if (!candidate.closed) {
                try { Files.deleteIfExists(stagedManifest); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
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

    PackExport exportPackSnapshot() throws IOException { return exportPackSnapshot(false); }

    PackExport exportPackSnapshot(boolean includeSampleValues) throws IOException {
        List<IslandRulePack.Entry> portable = new ArrayList<>();
        long revision;
        try (ReadView view = openRead()) {
            if (view.revision <= 0) throw new Failure(Code.UNINITIALIZED);
            revision = view.revision;
            for (Entry entry : view.entries) {
                IslandRulePack.checkInterrupted();
                byte[] original = view.read(entry);
                IslandRuleSample sample = view.readSample(entry);
                IslandRuleImportValidator.Result result = validator.validate(original, null);
                if (!result.canApprove(false)) throw new Failure(Code.VALIDATION);
                try {
                    byte[] rebuilt = IslandPortableRule.rebuild(original, result.rule);
                    portable.add(new IslandRulePack.Entry(UUID.randomUUID(), entry.enabled, entry.priority, rebuilt, sample));
                } catch (IOException unsupported) {
                    throw new IOException("FRPK_EXPORT_UNSUPPORTED entry=" + entry.order, unsupported);
                }
            }
        }
        return new PackExport(revision, IslandRulePack.encode(portable, includeSampleValues));
    }

    synchronized Pending prepareEdit(String id, boolean enabled, int priority,
            IslandRuleImportValidator.Environment environment) throws IOException {
        Entry replaced = replacedEntry(current, Objects.requireNonNull(id));
        IslandRuleImportValidator.Result result = validator.validate(readBlob(replaced), environment);
        if (!result.canApprove(true)) return new Pending(current.revision, result, null, environment);
        checkSlot(replaced, enabled);
        Entry entry = proposal(replaced, enabled, priority, replaced.sha256, replaced.byteCount, replaced.blob, replaced.sample);
        Pending candidate = new Pending(current.revision, result, entry, environment);
        pending.add(candidate);
        return candidate;
    }

    /** 页面必须带回打开规则时的修订。编辑只替换内容，沿用该 ID 的元数据。 */
    synchronized Pending prepareUpdate(long baseline, String id, byte[] bytes,
            IslandRuleImportValidator.Environment environment) throws IOException {
        return prepareUpdate(baseline, id, bytes, environment, null);
    }

    synchronized Pending prepareUpdate(long baseline, String id, byte[] bytes,
            IslandRuleImportValidator.Environment environment, IslandRuleSample sample) throws IOException {
        requireBaseline(baseline);
        verifyCurrentManifest();
        Entry target = replacedEntry(current, Objects.requireNonNull(id));
        return prepare(new ByteArrayInputStream(bytes), target.id, target.enabled, target.priority, environment, sample);
    }

    long setEnabled(long baseline, String id, boolean enabled,
            IslandRuleImportValidator.Environment environment) throws IOException {
        return changeEnabled(baseline, Collections.singleton(id), enabled, environment);
    }

    synchronized long changeEnabled(long baseline, Set<String> ids, boolean enabled,
            IslandRuleImportValidator.Environment environment) throws IOException {
        Set<String> targets = checkedTargets(baseline, ids);
        List<Entry> entries = new ArrayList<>(current.entries);
        int active = 0;
        boolean changed = false;
        for (Entry entry : current.entries) {
            boolean nextEnabled = targets.contains(entry.id) ? enabled : entry.enabled;
            if (nextEnabled && ++active > MAX_ACTIVE) throw new Failure(Code.ACTIVE_LIMIT);
            if (nextEnabled == entry.enabled) continue;
            if (nextEnabled) {
                IslandRuleImportValidator.Result validation = validator.validate(readBlob(entry), environment);
                // 只能复用该版本已经明确批准的兼容例外，新的不匹配需要重新确认。
                if (!validation.canApprove(entry.forced)) throw new Failure(Code.VALIDATION);
            }
            entries.set((int) entry.order, copyState(entry, increment(entry.revision), nextEnabled, entry.order));
            changed = true;
        }
        return changed ? publishEntryChanges(entries) : baseline;
    }

    long delete(long baseline, String id) throws IOException {
        return deleteAll(baseline, Collections.singleton(id));
    }

    synchronized long deleteAll(long baseline, Set<String> ids) throws IOException {
        Set<String> targets = checkedTargets(baseline, ids);
        if (targets.isEmpty()) return baseline;
        List<Entry> entries = new ArrayList<>(current.entries.size() - targets.size());
        for (Entry entry : current.entries) {
            if (targets.contains(entry.id)) continue;
            int order = entries.size();
            // manifest 要求连续序号。其余规则的版本和相对顺序保持原样。
            entries.add(entry.order == order ? entry : copyState(entry, entry.revision, entry.enabled, order));
        }
        return publishEntryChanges(entries);
    }

    private Set<String> checkedTargets(long baseline, Set<String> ids) throws IOException {
        requireBaseline(baseline);
        verifyCurrentManifest();
        Set<String> targets = new HashSet<>(ids);
        Set<String> missing = new HashSet<>(targets);
        for (Entry entry : current.entries) missing.remove(entry.id);
        if (!missing.isEmpty()) throw new Failure(Code.UNKNOWN_RULE);
        return targets;
    }

    private static Entry copyState(Entry item, long revision, boolean enabled, long order) {
        return new Entry(item.id, revision, enabled, item.priority, order, item.sha256, item.byteCount,
                item.blob, item.forced, item.skipped, item.sample);
    }

    private long publishEntryChanges(List<Entry> entries) throws IOException {
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
        if (candidate == null || authorization == null) throw new Failure(Code.VALIDATION);
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
        Entry proposed = candidate.entry;
        // 提交前复核原始字节，批准记录与条目一起进入同一个 manifest。
        readBlob(proposed);
        Entry replaced = proposed.order < current.entries.size() ? current.entries.get((int) proposed.order) : null;
        if (proposed.sample != null && (replaced == null || proposed.sample != replaced.sample)) {
            checkSampleCapacity(0);
            readSampleFile(proposed.sample);
        }
        List<String> skipped = new ArrayList<>();
        if (force) {
            for (IslandRuleImportValidator.Finding finding : candidate.validation.findings) {
                if (finding.stage == IslandRuleImportValidator.Stage.L2 && finding.blocking) {
                    skipped.add(finding.code + " " + finding.path + " " + finding.reason);
                }
            }
        }
        Entry approved = new Entry(proposed.id, proposed.revision, proposed.enabled, proposed.priority,
                proposed.order, proposed.sha256, proposed.byteCount, proposed.blob, !skipped.isEmpty(), skipped, proposed.sample);
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

    synchronized long revision() throws IOException {
        verifyCurrentManifest();
        return current.revision;
    }

    synchronized ReadView openRead() throws IOException {
        verifyCurrentManifest();
        for (Entry entry : current.entries) {
            pins.merge(entry.blob, 1, Integer::sum);
            if (entry.sample != null) samplePins.merge(entry.sample.blob, 1, Integer::sum);
        }
        return new ReadView(current);
    }

    private void verifyCurrentManifest() throws IOException {
        ensureDurable();
        Path file = root.resolve(MANIFEST);
        if (current.revision == 0 && Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return;
        byte[] actual = readManifestBytes(file);
        if (current.revision == 0 || !java.util.Arrays.equals(actual, encodeManifest(current))) {
            throw new Failure(Code.CORRUPT_REPOSITORY);
        }
    }

    private void ensureDurable() throws IOException {
        if (!needsSync) return;
        sync.sync(root);
        needsSync = false;
    }

    private void sweep() throws IOException {
        ensureDurable();
        Set<String> rules = new HashSet<>(pins.keySet());
        Set<String> samples = new HashSet<>(samplePins.keySet());
        for (Entry entry : current.entries) retain(entry, rules, samples);
        for (Pending candidate : pending) if (candidate.entry != null) retain(candidate.entry, rules, samples);
        sweepFiles("rule-", rules);
        sweepFiles("sample-", samples);
    }

    private static void unpin(Map<String, Integer> pinned, String id) {
        pinned.compute(id, (key, count) -> count == 1 ? null : count - 1);
    }

    private static void retain(Entry entry, Set<String> rules, Set<String> samples) {
        rules.add(entry.blob);
        if (entry.sample != null) samples.add(entry.sample.blob);
    }

    private void sweepFiles(String prefix, Set<String> keep) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, prefix + "*.blob")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!keep.contains(name.substring(prefix.length(), name.length() - 5))) Files.delete(file);
            }
        }
    }

    private void cleanupStaging(IOException failure) {
        try { sweep(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
    }

    private long ledgerBytes() throws IOException {
        return ledgerBytes("rule-*.blob", MAX_RULE_FILE_BYTES, Code.CORRUPT_REPOSITORY);
    }

    private void checkSampleCapacity(long addedBytes) throws IOException {
        long existing = ledgerBytes("sample-*.blob", MAX_SAMPLE_FILE_BYTES, Code.SAMPLE_CORRUPT);
        if (addedBytes > MAX_SAMPLE_FILE_BYTES - existing) throw new Failure(Code.SAMPLE_CAPACITY);
    }

    private long ledgerBytes(String pattern, int limit, Code invalidDirectoryEntry) throws IOException {
        long total = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, pattern)) {
            for (Path file : files) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new Failure(invalidDirectoryEntry);
                total += Files.size(file);
                if (total > limit) return total;
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

    private Entry proposal(Entry replaced, boolean enabled, int priority, String sha256, int count, String blob, SampleRef sample)
            throws IOException {
        return new Entry(replaced == null ? UUID.randomUUID().toString() : replaced.id,
                replaced == null ? 1 : increment(replaced.revision), enabled, priority,
                replaced == null ? current.entries.size() : replaced.order, sha256, count, blob,
                false, Collections.emptyList(), sample);
    }

    private void requireBaseline(long revision) throws Failure {
        if (revision != current.revision) throw new Failure(Code.BASELINE_CHANGED);
    }

    private static long increment(long revision) throws Failure {
        if (revision == Long.MAX_VALUE) throw new Failure(Code.METADATA_LIMIT);
        return revision + 1;
    }

    private Path blobPath(String blob) { return root.resolve("rule-" + blob + ".blob"); }

    private Path samplePath(String blob) { return root.resolve("sample-" + blob + ".blob"); }

    private IslandRuleSample readSampleFile(SampleRef sample) throws IOException {
        if (sample == null) return null;
        Path file = samplePath(sample.blob);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new Failure(Code.SAMPLE_CORRUPT);
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = readBounded(input, sample.size, Code.SAMPLE_CORRUPT);
            if (bytes.length != sample.size || !digest(bytes).equals(sample.sha256)) throw new Failure(Code.SAMPLE_CORRUPT);
            return IslandRuleSample.decode(bytes);
        } catch (java.io.InterruptedIOException | Failure failure) {
            throw failure;
        } catch (IOException failure) {
            throw new Failure(Code.SAMPLE_CORRUPT, failure);
        }
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
        return encodeManifest(manifest, ManifestFormat.PRIVATE);
    }

    private static byte[] encodeManifest(Manifest manifest, ManifestFormat format) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(body)) {
            out.writeInt(format.magic);
            out.writeInt(format.version);
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
                if (format == ManifestFormat.PRIVATE) {
                    out.writeBoolean(entry.sample != null);
                    if (entry.sample != null) {
                        out.writeUTF(entry.sample.blob);
                        out.writeUTF(entry.sample.sha256);
                        out.writeInt(entry.sample.size);
                    }
                }
                if (body.size() + 64 > format.limit) {
                    throw new Failure(Code.MANIFEST_LIMIT);
                }
            }
            // 校验和覆盖完整 envelope，包括批准字段；规则元数据已由原始字节摘要覆盖。
            out.write(digest(body.toByteArray()).getBytes(StandardCharsets.US_ASCII));
        }
        return body.toByteArray();
    }

    private static Manifest readManifest(Path file) throws IOException {
        return decodeManifest(readManifestBytes(file), ManifestFormat.PRIVATE);
    }

    private static byte[] readManifestBytes(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new Failure(Code.CORRUPT_REPOSITORY);
        try (InputStream input = Files.newInputStream(file)) {
            return readBounded(input, ManifestFormat.PRIVATE.limit, Code.MANIFEST_LIMIT);
        }
    }

    static Manifest decodeManifest(byte[] bytes) throws IOException {
        return decodeManifest(bytes, ManifestFormat.STRICT_IPC);
    }

    private static Manifest decodeManifest(byte[] bytes, ManifestFormat format) throws IOException {
        if (bytes.length > format.limit) throw new Failure(Code.MANIFEST_LIMIT);
        int bodySize = bytes.length - 64;
        if (bodySize < 20 || !digest(java.util.Arrays.copyOf(bytes, bodySize)).equals(
                new String(bytes, bodySize, 64, StandardCharsets.US_ASCII))) {
            throw new Failure(Code.CORRUPT_REPOSITORY);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bodySize))) {
            if (in.readInt() != format.magic || in.readInt() != format.version) {
                throw new Failure(Code.CORRUPT_REPOSITORY);
            }
            boolean samples = format == ManifestFormat.PRIVATE;
            long revision = in.readLong();
            int count = in.readInt();
            if (revision <= 0 || count < 0 || count > MAX_ENTRIES) throw new Failure(Code.CORRUPT_REPOSITORY);
            List<Entry> entries = new ArrayList<>(count);
            Set<String> ids = new HashSet<>();
            Map<String, Entry> blobs = new HashMap<>();
            Map<String, SampleRef> sampleBlobs = new HashMap<>();
            long sampleTotal = 0;
            int active = 0;
            long total = 0;
            for (int index = 0; index < count; index++) {
                Entry entry = readEntry(in, index, revision, samples);
                if (!ids.add(entry.id)) throw new Failure(Code.CORRUPT_REPOSITORY);
                if (entry.enabled && ++active > MAX_ACTIVE) throw new Failure(Code.CORRUPT_REPOSITORY);
                Entry previous = blobs.putIfAbsent(entry.blob, entry);
                if (previous == null) total += entry.byteCount;
                else if (previous.byteCount != entry.byteCount || !previous.sha256.equals(entry.sha256)) {
                    throw new Failure(Code.CORRUPT_REPOSITORY);
                }
                if (total > MAX_RULE_FILE_BYTES) throw new Failure(Code.CORRUPT_REPOSITORY);
                if (entry.sample != null) {
                    SampleRef prior = sampleBlobs.putIfAbsent(entry.sample.blob, entry.sample);
                    if (prior == null) sampleTotal += entry.sample.size;
                    else if (prior.size != entry.sample.size || !prior.sha256.equals(entry.sample.sha256)) {
                        throw new Failure(Code.CORRUPT_REPOSITORY);
                    }
                    if (sampleTotal > MAX_SAMPLE_FILE_BYTES) throw new Failure(Code.CORRUPT_REPOSITORY);
                }
                entries.add(entry);
            }
            if (in.read() != -1) throw new Failure(Code.CORRUPT_REPOSITORY);
            return new Manifest(revision, entries);
        } catch (IOException failure) {
            if (failure instanceof Failure) throw failure;
            throw new Failure(Code.CORRUPT_REPOSITORY, failure);
        }
    }

    private static Entry readEntry(DataInputStream in, int index, long manifestRevision, boolean samples) throws IOException {
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
        SampleRef sample = null;
        if (samples) {
            int present = in.readUnsignedByte();
            if (present > 1) throw new Failure(Code.CORRUPT_REPOSITORY);
            if (present == 1) {
                String sampleBlob = in.readUTF();
                String sampleDigest = in.readUTF();
                int sampleSize = in.readInt();
                if (!uuid(sampleBlob) || !sampleDigest.matches("[0-9a-f]{64}")
                        || sampleSize <= 0 || sampleSize > IslandRuleSample.MAX_BYTES) {
                    throw new Failure(Code.CORRUPT_REPOSITORY);
                }
                sample = new SampleRef(sampleBlob, sampleDigest, sampleSize);
            }
        }
        return new Entry(id, revision, enabled == 1, priority, order, sha256, size, blob, forced == 1, skipped, sample);
    }

    private static boolean uuid(String value) {
        return value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
