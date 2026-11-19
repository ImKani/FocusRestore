/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class IslandRuleRepositoryTest {
    private static final IslandRuleImportValidator.Environment CURRENT = new IslandRuleImportValidator.Environment("test", 1L);
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private Path root() throws IOException { return temp.newFolder().toPath().resolve("island-rules"); }
    // JVM 测试注入目录同步边界。平台 fsync 行为由 Android/ROM 验收。
    private IslandRuleRepository repo(Path path) throws IOException { return new IslandRuleRepository(path, ignored -> {}); }
    private byte[] valid() throws IOException { return Files.readAllBytes(Paths.get("docs", "samples", "rule-travel.valid.json")); }
    private byte[] variant(String from, String to) throws IOException {
        String rule = new String(valid(), StandardCharsets.UTF_8);
        assertTrue(rule.contains(from));
        return rule.replace(from, to).getBytes(StandardCharsets.UTF_8);
    }
    private byte[] padded(int size) throws IOException {
        byte[] exact = valid();
        assertTrue(size >= exact.length);
        byte[] padded = Arrays.copyOf(exact, size);
        Arrays.fill(padded, exact.length, padded.length, (byte) ' ');
        return padded;
    }
    private IslandRuleRepository.Pending prepare(IslandRuleRepository r, byte[] bytes, String replace, boolean enabled) throws IOException {
        return r.prepare(new ByteArrayInputStream(bytes), replace, enabled, 0, CURRENT);
    }
    private String add(IslandRuleRepository r, byte[] bytes, boolean enabled) throws IOException {
        try (IslandRuleRepository.Pending p = prepare(r, bytes, null, enabled)) {
            r.commit(p, false, CURRENT);
            return p.entry.id;
        }
    }
    private static long files(Path root) throws IOException {
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".blob")).count();
        }
    }
    private static void rejects(IslandRuleRepository.Code code, Checked operation) {
        IslandRuleRepository.Failure failure = assertThrows(IslandRuleRepository.Failure.class, operation::run);
        assertEquals(code, failure.code);
    }
    private interface Checked { void run() throws IOException; }

    @Test public void managementChecksDisplayedRevisionAndPreservesSiblingsAndPriority() throws Exception {
        Path dir = root();
        IslandRuleRepository owner = repo(dir);
        byte[] bytes = valid();
        String first = add(owner, bytes, true);
        String middle;
        try (IslandRuleRepository.Pending p = owner.prepare(new ByteArrayInputStream(bytes), null, false, 23, CURRENT)) {
            middle = p.entry.id;
            owner.commit(p, false, CURRENT);
        }
        String last = add(owner, bytes, true);
        try (IslandRuleRepository.Pending invalid = owner.prepareUpdate(3, middle, "[]".getBytes(StandardCharsets.UTF_8), CURRENT)) {
            assertFalse(invalid.empty);
            assertFalse(invalid.canApprove(true));
            assertEquals(3, owner.revision());
        }
        byte[] changed = variant("human label", "edited label");
        try (IslandRuleRepository.ReadView pinned = owner.openRead();
                IslandRuleRepository.Pending pending = owner.prepareUpdate(3, middle, changed, CURRENT)) {
            assertEquals(4, owner.commit(pending, false, CURRENT));
            rejects(IslandRuleRepository.Code.BASELINE_CHANGED, () -> owner.setEnabled(3, middle, true, CURRENT));
            rejects(IslandRuleRepository.Code.BASELINE_CHANGED, () -> owner.prepareUpdate(3, middle, bytes, CURRENT));
            rejects(IslandRuleRepository.Code.BASELINE_CHANGED, () -> owner.delete(3, first));
            assertFalse(pending.entry.enabled);
            assertEquals(1, pending.entry.order);
            assertEquals(23, pending.entry.priority);
            assertEquals(5, owner.setEnabled(4, middle, true, CURRENT));
            assertEquals(6, owner.setEnabled(5, middle, false, CURRENT));
            assertEquals(7, owner.delete(6, first));
            assertArrayEquals(bytes, pinned.read(pinned.entries.get(0)));
        }
        try (IslandRuleRepository.ReadView view = repo(dir).openRead()) {
            assertEquals(2, view.entries.size());
            IslandRuleRepository.Entry updated = view.entries.get(0);
            assertEquals(middle, updated.id);
            assertEquals(23, updated.priority);
            assertEquals(0, updated.order);
            assertFalse(updated.enabled);
            assertArrayEquals(changed, view.readById(middle));
            rejects(IslandRuleRepository.Code.UNKNOWN_RULE, () -> view.readById(first));
            assertEquals(last, view.entries.get(1).id);
            assertEquals(1, view.entries.get(1).order);
            assertEquals(1, view.entries.get(1).revision);
        }
        rejects(IslandRuleRepository.Code.UNKNOWN_RULE, () -> owner.delete(7, first));
    }

    @Test public void boundaryReadsExactlyOnceAndRetainsOriginalUtf8() throws Exception {
        Path root = root();
        IslandRuleRepository r = repo(root);
        byte[] bytes = padded(65536);
        add(r, bytes, true);
        ByteArrayInputStream excess = new ByteArrayInputStream(padded(65538));
        rejects(IslandRuleRepository.Code.RULE_SIZE, () -> r.prepare(excess, null, true, 0, CURRENT));
        assertEquals(1, excess.available());
        rejects(IslandRuleRepository.Code.RULE_SIZE, () -> prepare(r, padded(65537), null, true));
        try (IslandRuleRepository.ReadView view = repo(root).openRead()) {
            assertEquals(1, view.revision);
            assertArrayEquals(bytes, view.read(view.entries.get(0)));
        }
    }

    @Test public void disabledIndependentCopiesChargeExactQuotaAndFullReplacementKeepsCurrent() throws Exception {
        Path root = root();
        IslandRuleRepository r = repo(root);
        byte[] bytes = padded(65536);
        String id = add(r, bytes, false);
        for (int i = 1; i < 31; i++) add(r, bytes, false);
        add(r, padded(65535), false);
        assertEquals(32, files(root));
        try (IslandRuleRepository.Pending lastByte = prepare(r, valid(), id, false)) {
            fail("replacement should require new physical bytes");
        } catch (IslandRuleRepository.Failure failure) {
            assertEquals(IslandRuleRepository.Code.REPOSITORY_CAPACITY, failure.code);
        }
        // 释放一字节差额只通过另一个独立仓库场景验证，当前版本始终计费。
        try (IslandRuleRepository.ReadView view = r.openRead()) {
            assertEquals(32, view.revision);
            assertTrue(view.entries.stream().noneMatch(e -> e.enabled));
        }
        Path exactRoot = root();
        IslandRuleRepository exact = repo(exactRoot);
        for (int i = 0; i < 31; i++) add(exact, bytes, false);
        try (IslandRuleRepository.Pending stage = prepare(exact, bytes, null, false)) {
            assertEquals(32, files(exactRoot));
            rejects(IslandRuleRepository.Code.REPOSITORY_CAPACITY, () -> prepare(exact, valid(), null, false));
            exact.commit(stage, false, CURRENT);
        }
        try (IslandRuleRepository.ReadView view = exact.openRead()) {
            IslandRuleRepository.Entry entry = view.entries.get(0);
            rejects(IslandRuleRepository.Code.REPOSITORY_CAPACITY, () -> prepare(exact, bytes, entry.id, false));
            // 元数据修订引用原文件，读者与当前 manifest 重叠时仍只计一次。
            try (IslandRuleRepository.Pending edit = exact.prepareEdit(entry.id, true, 2, CURRENT)) {
                assertEquals(33, exact.commit(edit, false, CURRENT));
            }
            assertEquals(32, files(exactRoot));
            assertFalse(entry.enabled);
            assertArrayEquals(bytes, view.read(entry));
        }
    }

    @Test public void twoConcurrentStagesFitExactlyAndOneByteMoreIsRejected() throws Exception {
        Path root = root();
        IslandRuleRepository r = repo(root);
        byte[] full = padded(65536);
        for (int i = 0; i < 30; i++) add(r, full, false);
        int partial = valid().length + 10;
        add(r, padded(partial), false);
        try (IslandRuleRepository.Pending large = prepare(r, full, null, false)) {
            int remaining = 65536 - partial;
            rejects(IslandRuleRepository.Code.REPOSITORY_CAPACITY, () -> prepare(r, padded(remaining + 1), null, false));
            try (IslandRuleRepository.Pending rest = prepare(r, padded(remaining), null, false)) {
                assertEquals(33, files(root));
                r.commit(large, false, CURRENT);
                rejects(IslandRuleRepository.Code.BASELINE_CHANGED, () -> r.commit(rest, false, CURRENT));
            }
        }
        assertEquals(32, files(root));
    }

    @Test public void fiftyActiveAndDisabledRulesDoNotConsumeActiveSlots() throws Exception {
        IslandRuleRepository r = repo(root());
        byte[] bytes = valid();
        String disabled = add(r, bytes, false);
        for (int i = 0; i < 50; i++) add(r, bytes, true);
        rejects(IslandRuleRepository.Code.ACTIVE_LIMIT, () -> prepare(r, bytes, null, true));
        rejects(IslandRuleRepository.Code.ACTIVE_LIMIT, () -> r.prepareEdit(disabled, true, 0, CURRENT));
        add(r, bytes, false);
        try (IslandRuleRepository.ReadView v = r.openRead()) {
            assertEquals(52, v.entries.size());
            assertEquals(50, v.entries.stream().filter(e -> e.enabled).count());
        }
    }

    @Test public void pinsOldBytesUntilLastReaderClosesAndReclaimsCancelledStage() throws Exception {
        Path root = root();
        IslandRuleRepository r = repo(root);
        byte[] original = valid();
        byte[] updated = variant("human label", "new label");
        String id = add(r, original, true);
        try (IslandRuleRepository.ReadView first = r.openRead(); IslandRuleRepository.ReadView second = r.openRead()) {
            IslandRuleRepository.Entry old = first.entries.get(0);
            IslandRuleRepository.Pending cancelled = prepare(r, updated, id, true);
            assertEquals(2, files(root));
            cancelled.close();
            rejects(IslandRuleRepository.Code.CLOSED, () -> r.commit(cancelled, false, CURRENT));
            assertEquals(1, files(root));
            try (IslandRuleRepository.Pending replacement = prepare(r, updated, id, false)) {
                assertEquals(2, r.commit(replacement, false, CURRENT));
            }
            first.close();
            assertEquals(2, files(root));
            assertArrayEquals(original, second.read(old));
            try (IslandRuleRepository.ReadView now = r.openRead()) {
                IslandRuleRepository.Entry newer = now.entries.get(0);
                assertEquals(id, newer.id);
                assertEquals(2, newer.revision);
                assertEquals(old.order, newer.order);
                assertFalse(newer.enabled);
                assertArrayEquals(updated, now.read(newer));
                rejects(IslandRuleRepository.Code.UNKNOWN_RULE, () -> now.read(old));
            }
        }
        assertEquals(1, files(root));
    }

    @Test public void commitDuringBlockingReadRejectsStaleBaseBeforeSpooling() throws Exception {
        Path root = root();
        IslandRuleRepository r = repo(root);
        byte[] bytes = valid();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        InputStream slow = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                started.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return super.read(b, off, len);
            }
        };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> stale = worker.submit(() -> rejects(IslandRuleRepository.Code.BASELINE_CHANGED,
                    () -> r.prepare(slow, null, true, 0, CURRENT)));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            add(r, bytes, true);
            release.countDown();
            stale.get(5, TimeUnit.SECONDS);
            assertEquals(1, files(root));
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test public void forceOnlyBypassesExactBlockingL2AndChangedEnvironmentRequiresNewConfirmation() throws Exception {
        Path root = root();
        IslandRuleRepository r = repo(root);
        byte[] mismatch = variant("\"systemUiVersion\": null", "\"systemUiVersion\": {\"name\":\"other\",\"code\":2}");
        try (IslandRuleRepository.Pending p = prepare(r, mismatch, null, true)) {
            assertFalse(p.validation.canApprove(false));
            assertTrue(p.validation.canApprove(true));
            rejects(IslandRuleRepository.Code.VALIDATION, () -> r.commit(p, false, CURRENT));
            rejects(IslandRuleRepository.Code.ENVIRONMENT_CHANGED,
                    () -> r.commit(p, true, new IslandRuleImportValidator.Environment("other", 2L)));
            r.commit(p, true, CURRENT);
        }
        try (IslandRuleRepository.ReadView v = repo(root).openRead()) {
            IslandRuleRepository.Entry approved = v.entries.get(0);
            assertTrue(approved.forced);
            assertEquals(Arrays.asList("L2_ENVIRONMENT $.meta.systemUiVersion SYSTEM_UI_VERSION_MISMATCH"), approved.skipped);
        }
        IslandRuleImportValidator validator = new IslandRuleImportValidator();
        IslandRuleImportValidator.Result unknown = validator.validate(mismatch, new IslandRuleImportValidator.Environment(null, null));
        assertTrue(unknown.canApprove(false));
        assertEquals("L2_UNVERIFIABLE", unknown.findings.get(0).code);
        assertEquals("$.meta.systemUiVersion", unknown.findings.get(0).path);
        assertTrue(validator.validate(mismatch, new IslandRuleImportValidator.Environment("other", 2L)).canApprove(false));
        assertEquals(IslandRuleImportValidator.Stage.L1, validator.validate(new byte[]{'{'}, CURRENT).findings.get(0).stage);
        byte[] badReference = variant("\"ref\": \"gate\"", "\"ref\": \"missing\"");
        byte[] badOperator = variant("\"op\": \"equals\"", "\"op\": \"contains\"");
        for (byte[] invalid : Arrays.asList(badReference, badOperator)) {
            try (IslandRuleRepository.Pending p = prepare(r, invalid, null, true)) {
                assertFalse(p.validation.canApprove(true));
                assertTrue(p.validation.findings.stream().allMatch(f -> f.stage == IslandRuleImportValidator.Stage.L3));
                assertNull(p.entry);
                rejects(IslandRuleRepository.Code.CLOSED, () -> r.commit(p, true, CURRENT));
            }
        }
        assertEquals(1, files(root));
    }

    @Test public void tamperedStageCannotCommitEvenWithForce() throws Exception {
        Path root = root();
        IslandRuleRepository r = repo(root);
        byte[] original = valid();
        String id = add(r, original, true);
        Path old;
        try (Stream<Path> paths = Files.list(root)) {
            old = paths.filter(path -> path.toString().endsWith(".blob")).findFirst().get();
        }
        try (IslandRuleRepository.Pending update = prepare(r, original, id, true)) {
            Path staged;
            try (Stream<Path> paths = Files.list(root)) {
                staged = paths.filter(path -> path.toString().endsWith(".blob") && !path.equals(old)).findFirst().get();
            }
            byte[] changed = original.clone();
            changed[changed.length - 1] = (byte) 'X';
            Files.write(staged, changed);
            rejects(IslandRuleRepository.Code.CORRUPT_REPOSITORY, () -> r.commit(update, true, CURRENT));
        }
        try (IslandRuleRepository.ReadView view = r.openRead()) {
            assertEquals(1, view.revision);
            assertArrayEquals(original, view.read(view.entries.get(0)));
        }
        assertEquals(1, files(root));
    }

    @Test public void crashBeforeManifestSwitchPreservesOldAndSweepsStageAndManifestCandidate() throws Exception {
        faultRecovery(1, false);
    }

    @Test public void crashAfterManifestSwitchRecoversNewAndSweepsOnlyUnreferencedBytes() throws Exception {
        faultRecovery(2, true);
    }

    private void faultRecovery(int failAt, boolean switched) throws Exception {
        Path root = root();
        int[] countdown = {0};
        IslandRuleRepository r = new IslandRuleRepository(root, dir -> {
            if (countdown[0] > 0 && --countdown[0] == 0) throw new IOException("injected directory sync failure");
        });
        byte[] old = valid(), updated = variant("human label", "new label");
        String id = add(r, old, true);
        IslandRuleRepository.Pending stage = prepare(r, updated, id, true);
        countdown[0] = failAt;
        if (switched) rejects(IslandRuleRepository.Code.DURABILITY_UNCERTAIN, () -> r.commit(stage, false, CURRENT));
        else assertEquals("injected directory sync failure", assertThrows(IOException.class, () -> r.commit(stage, false, CURRENT)).getMessage());
        assertEquals(2, files(root));
        assertEquals(!switched, Files.exists(root.resolve("manifest.next")));
        // 模拟进程退出，不走旧实例的 close。新实例按磁盘可见的原子提交点恢复。
        try (IslandRuleRepository.ReadView view = repo(root).openRead()) {
            assertEquals(switched ? 2 : 1, view.revision);
            assertArrayEquals(switched ? updated : old, view.read(view.entries.get(0)));
        }
        assertEquals(1, files(root));
        assertFalse(Files.exists(root.resolve("manifest.next")));
    }

    @Test public void refusesOversizedManifestAndOversizedImportedIdOnRecovery() throws Exception {
        Path root = root();
        IslandRuleRepository r = repo(root);
        add(r, valid(), true);
        Path manifest = root.resolve("manifest");
        Files.write(manifest, new byte[IslandRuleRepository.MAX_MANIFEST_BYTES]);
        rejects(IslandRuleRepository.Code.CORRUPT_REPOSITORY, () -> repo(root));
        Files.write(manifest, new byte[IslandRuleRepository.MAX_MANIFEST_BYTES + 1]);
        rejects(IslandRuleRepository.Code.MANIFEST_LIMIT, () -> repo(root));
        String longId = String.join("", java.util.Collections.nCopies(43, "界"));
        assertEquals(129, longId.getBytes(StandardCharsets.UTF_8).length);
        fixtureManifest(root, 1, longId);
        rejects(IslandRuleRepository.Code.CORRUPT_REPOSITORY, () -> repo(root));
        assertTrue(Files.exists(manifest));
    }

    @Test public void exactly1024MetadataEntriesShareOneFileAnd1025IsRejected() throws Exception {
        Path root = root();
        Files.createDirectory(root);
        fixtureManifest(root, 1024, null);
        IslandRuleRepository r = repo(root);
        try (IslandRuleRepository.ReadView view = r.openRead()) {
            assertEquals(1024, view.entries.size());
            assertEquals(36, view.entries.get(0).id.getBytes(StandardCharsets.UTF_8).length);
            assertArrayEquals(valid(), view.read(view.entries.get(1023)));
        }
        assertEquals(1, files(root));
        rejects(IslandRuleRepository.Code.METADATA_LIMIT, () -> prepare(r, valid(), null, false));
        fixtureManifest(root, 1025, null);
        rejects(IslandRuleRepository.Code.CORRUPT_REPOSITORY, () -> repo(root));
    }

    private void fixtureManifest(Path root, int count, String firstId) throws Exception {
        byte[] bytes = valid();
        String blob = "00000000-0000-0000-0000-000000000010";
        Files.write(root.resolve("rule-" + blob + ".blob"), bytes);
        ByteArrayOutputStream envelope = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(envelope)) {
            out.writeInt(0x46524952); out.writeInt(1); out.writeLong(count); out.writeInt(count);
            for (int i = 0; i < count; i++) {
                out.writeUTF(i == 0 && firstId != null ? firstId : new UUID(0, i).toString());
                out.writeLong(1); out.writeBoolean(false); out.writeInt(0); out.writeLong(i);
                out.writeUTF(hash(bytes)); out.writeInt(bytes.length); out.writeUTF(blob);
                out.writeBoolean(false); out.writeByte(0);
            }
            out.write(hash(envelope.toByteArray()).getBytes(StandardCharsets.US_ASCII));
        }
        Files.write(root.resolve("manifest"), envelope.toByteArray());
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x", b & 255));
        return hex.toString();
    }
}
