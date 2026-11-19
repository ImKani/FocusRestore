/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class IslandRulePackRepositoryTest {
    private static final IslandRuleImportValidator.Environment CURRENT = new IslandRuleImportValidator.Environment("17.03.260226.r", 42L);
    private static final IslandRuleImportValidator.Environment OTHER = new IslandRuleImportValidator.Environment("17.03.260226.r", 43L);
    private static final IslandRuleRepository.BatchConfirmation REPLACE = IslandRuleRepository.BatchConfirmation.REPLACE_ALL;
    private static final IslandRuleRepository.BatchConfirmation CLEAR = IslandRuleRepository.BatchConfirmation.CLEAR_ALL;
    private static final String RULE = "{\"ruleVersion\":1,\"meta\":{\"packageName\":\"com.test.rule\",\"templateType\":\"baseInfo\","
            + "\"ruleAuthor\":\"FocusRestore\",\"ruleDescription\":\"\",\"createdAt\":\"2026-11-19T00:00:00Z\"},"
            + "\"match\":{\"all\":[{\"field\":\"packageName\",\"op\":\"equals\",\"value\":\"com.test.rule\"}]},"
            + "\"extract\":[{\"id\":\"field1\",\"path\":\"/baseInfo/title\"}],\"display\":{\"order\":[{\"slot\":\"extra\",\"ref\":\"field1\"}]}}";
    private static final String VERSIONED = RULE.replace("\"createdAt\"", "\"systemUiVersion\":{\"name\":\"17.03.260226.r\",\"code\":42},\"createdAt\"");

    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private Path root() throws IOException { return temp.newFolder().toPath().resolve("rules"); }
    private static IslandRuleRepository repo(Path root) throws IOException { return new IslandRuleRepository(root, ignored -> {}); }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] pack(IslandRulePack.Entry... entries) throws IOException { return IslandRulePack.encode(Arrays.asList(entries)); }
    private static IslandRulePack.Entry item(int id, String json) { return IslandRulePackTest.entry(id, true, 0, utf8(json)); }
    private static byte[] pad(int size) {
        byte[] source = utf8(RULE);
        byte[] bytes = Arrays.copyOf(source, size);
        Arrays.fill(bytes, source.length, size, (byte) ' ');
        return bytes;
    }

    private static byte[] copies(int count, int size) throws IOException {
        List<IslandRulePack.Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) entries.add(IslandRulePackTest.entry(i, false, i, pad(size)));
        return IslandRulePack.encode(entries);
    }

    private static IslandRuleRepository.PendingBatch prepare(IslandRuleRepository repository, byte[] bytes) throws IOException {
        return repository.prepareBatch(new ByteArrayInputStream(bytes), CURRENT);
    }

    private static String single(IslandRuleRepository repository, String text, boolean force) throws IOException {
        try (IslandRuleRepository.Pending pending = repository.prepare(new ByteArrayInputStream(utf8(text)), null, true, 11, CURRENT)) {
            repository.commit(pending, force, CURRENT);
            return pending.entry.id;
        }
    }

    private static long files(Path root) throws IOException {
        try (Stream<Path> list = Files.list(root)) {
            return list.filter(p -> p.getFileName().toString().endsWith(".blob")).count();
        }
    }

    private static Path extraBlob(Path root, String existing) throws IOException {
        try (Stream<Path> list = Files.list(root)) {
            return list.filter(p -> p.getFileName().toString().endsWith(".blob"))
                    .filter(p -> !p.getFileName().toString().equals("rule-" + existing + ".blob")).findFirst().get();
        }
    }

    private interface Checked { void run() throws Exception; }
    private static void rejects(IslandRuleRepository.Code code, Checked action) {
        assertEquals(code, assertThrows(IslandRuleRepository.Failure.class, action::run).code);
    }

    @Test public void atomicReplaceKeepsOriginalBytesOrderAndPinnedOldView() throws Exception {
        Path path = root();
        IslandRuleRepository repository = repo(path);
        String original = single(repository, RULE, false);
        byte[] exact = utf8(RULE + "\r\n ");
        byte[] packed = pack(IslandRulePackTest.entry(10, false, -4, exact), item(11, VERSIONED));
        try (IslandRuleRepository.ReadView old = repository.openRead(); IslandRuleRepository.PendingBatch batch = prepare(repository, packed)) {
            assertEquals(1, batch.baselineRevision);
            assertEquals(1, batch.previousCount);
            assertEquals(2, batch.entryCount);
            assertEquals(1, batch.enabledCount);
            assertEquals(IslandRulePackTest.decode(packed).digest, batch.packDigest);
            assertEquals(1, repository.revision());
            rejects(IslandRuleRepository.Code.VALIDATION, () -> repository.commitBatch(batch, CLEAR, CURRENT));
            assertEquals(2, repository.commitBatch(batch, REPLACE, CURRENT));
            rejects(IslandRuleRepository.Code.CLOSED, () -> repository.commitBatch(batch, REPLACE, CURRENT));
            assertEquals(3, files(path));
            assertArrayEquals(utf8(RULE), old.read(old.entries.get(0)));
            try (IslandRuleRepository.ReadView view = repository.openRead()) {
                assertEquals(2, view.entries.size());
                IslandRuleRepository.Entry first = view.entries.get(0);
                assertNotEquals(original, first.id);
                assertNotEquals(IslandRulePackTest.decode(packed).entries.get(0).id.toString(), first.id);
                assertEquals(1, first.revision);
                assertEquals(0, first.order);
                assertEquals(-4, first.priority);
                assertFalse(first.enabled || first.forced);
                assertTrue(first.skipped.isEmpty());
                assertArrayEquals(exact, view.read(first));
                assertEquals(1, view.entries.get(1).order);
            }
        }
        assertEquals(2, files(path));
    }

    @Test public void emptyRequiresDistinctConfirmationAndCreatesPositiveRevision() throws Exception {
        IslandRuleRepository repository = repo(root());
        rejects(IslandRuleRepository.Code.UNINITIALIZED, repository::exportPack);
        assertThrows(IslandRulePack.Failure.class, () -> prepare(repository, utf8("[]")));
        byte[] empty = pack();
        try (IslandRuleRepository.PendingBatch stage = prepare(repository, empty)) {
            rejects(IslandRuleRepository.Code.VALIDATION, () -> repository.commitBatch(stage, REPLACE, CURRENT));
            assertEquals(0, repository.revision());
            assertEquals(1, repository.commitBatch(stage, CLEAR, CURRENT));
        }
        assertArrayEquals(empty, repository.exportPack());
        single(repository, RULE, false);
        try (IslandRuleRepository.PendingBatch stage = prepare(repository, empty)) {
            assertEquals(3, repository.commitBatch(stage, CLEAR, CURRENT));
        }
        try (IslandRuleRepository.ReadView view = repository.openRead()) { assertTrue(view.entries.isEmpty()); }
    }

    @Test public void disabledTailL1L3L2OrUnsupportedRuleLeavesWholeOldRevision() throws Exception {
        Path path = root();
        IslandRuleRepository repository = repo(path);
        single(repository, RULE, false);
        byte[] manifest = Files.readAllBytes(path.resolve("manifest"));
        List<String> invalidRules = Arrays.asList("{}", RULE.replace("\"ref\":\"field1\"", "\"ref\":\"field2\""),
                VERSIONED.replace("\"code\":42", "\"code\":43"), RULE.replace("\"ruleVersion\":1", "\"ruleVersion\":1,\"x-note\":\"synthetic\""));
        List<IslandRuleImportValidator.Stage> stages = Arrays.asList(IslandRuleImportValidator.Stage.L1,
                IslandRuleImportValidator.Stage.L3, IslandRuleImportValidator.Stage.L2, IslandRuleImportValidator.Stage.L1);
        for (int i = 0; i < invalidRules.size(); i++) {
            byte[] invalid = pack(item(0, RULE), IslandRulePackTest.entry(1, false, 0, utf8(invalidRules.get(i))));
            IslandRuleRepository.BatchRejected rejected = assertThrows(IslandRuleRepository.BatchRejected.class, () -> prepare(repository, invalid));
            assertEquals(1, rejected.findings.get(0).declarationIndex);
            assertEquals(stages.get(i), rejected.findings.get(0).finding.stage);
            assertTrue(rejected.findings.get(0).finding.blocking);
            assertArrayEquals(manifest, Files.readAllBytes(path.resolve("manifest")));
            assertEquals(1, files(path));
        }
    }

    @Test public void unverifiableIsVisibleAndEnvironmentChangeConsumesPendingWithoutMutation() throws Exception {
        Path path = root();
        IslandRuleRepository repository = repo(path);
        byte[] packed = pack(item(0, VERSIONED));
        try (IslandRuleRepository.PendingBatch stage = repository.prepareBatch(new ByteArrayInputStream(packed), null)) {
            assertEquals(1, stage.warnings.size());
            assertEquals("L2_UNVERIFIABLE", stage.warnings.get(0).finding.code);
            assertFalse(stage.warnings.get(0).finding.blocking);
            assertEquals(1, repository.commitBatch(stage, REPLACE, null));
        }
        try (IslandRuleRepository.PendingBatch stage = prepare(repository, packed)) {
            rejects(IslandRuleRepository.Code.ENVIRONMENT_CHANGED, () -> repository.commitBatch(stage, REPLACE, OTHER));
            rejects(IslandRuleRepository.Code.CLOSED, () -> repository.commitBatch(stage, REPLACE, CURRENT));
        }
        assertEquals(1, repository.revision());
        assertEquals(1, files(path));
    }

    @Test public void exportRebuildsPortableFieldsAndDoesNotCarryForceOrLocalIdentity() throws Exception {
        IslandRuleRepository repository = repo(root());
        String text = VERSIONED.replace("\"code\":42", "\"code\":43");
        String id = single(repository, text + " \n", true);
        IslandRulePack.Entry portable = IslandRulePackTest.decode(repository.exportPack()).entries.get(0);
        String rebuilt = new String(portable.bytes(), StandardCharsets.UTF_8);
        assertNotEquals(id, portable.id.toString());
        assertEquals(11, portable.priority);
        assertTrue(portable.enabled);
        assertTrue(rebuilt.contains("\"code\":43"));
        assertTrue(rebuilt.contains("\"required\":false"));
        assertFalse(rebuilt.contains("force") || rebuilt.contains("skipped") || rebuilt.contains(id));
        byte[] exported = repository.exportPack();
        assertThrows(IslandRuleRepository.BatchRejected.class, () -> prepare(repository, exported));
        try (IslandRuleRepository.PendingBatch stage = repository.prepareBatch(new ByteArrayInputStream(exported), OTHER)) {
            assertEquals(2, repository.commitBatch(stage, REPLACE, OTHER));
        }
        try (IslandRuleRepository.ReadView view = repository.openRead()) {
            assertFalse(view.entries.get(0).forced);
            assertTrue(view.entries.get(0).skipped.isEmpty());
        }
    }

    @Test public void unsupportedExportFieldsRejectWholeExportWithoutReadingCaptureState() throws Exception {
        for (String rule : Arrays.asList(RULE.replace("\"ruleVersion\":1", "\"ruleVersion\":1,\"x-note\":\"sample sentinel\""),
                RULE.replace("\"ruleDescription\":\"\"", "\"ruleDescription\":\"sample sentinel\""),
                RULE.replace("\"id\":\"field1\"", "\"id\":\"field1\",\"replace\":[{\"from\":\"a\",\"to\":\"sample sentinel\"}]"),
                RULE.replace("\"templateType\"", "\"notificationTag\":\"sample sentinel\",\"templateType\""))) {
            IslandRuleRepository repository = repo(root());
            single(repository, RULE, false);
            single(repository, rule, false);
            IOException unsupported = assertThrows(IOException.class, repository::exportPack);
            assertEquals("FRPK_EXPORT_UNSUPPORTED entry=1", unsupported.getMessage());
            assertEquals(2, repository.revision());
        }
    }

    @Test public void singleCommitAndOtherBatchInvalidateBaseline() throws Exception {
        Path path = root();
        IslandRuleRepository repository = repo(path);
        byte[] body = pack(item(0, RULE));
        try (IslandRuleRepository.PendingBatch stale = prepare(repository, body)) {
            single(repository, RULE, false);
            rejects(IslandRuleRepository.Code.BASELINE_CHANGED, () -> repository.commitBatch(stale, REPLACE, CURRENT));
            assertEquals(1, files(path));
        }
        try (IslandRuleRepository.PendingBatch winner = prepare(repository, body); IslandRuleRepository.PendingBatch stale = prepare(repository, body)) {
            repository.commitBatch(winner, REPLACE, CURRENT);
            rejects(IslandRuleRepository.Code.BASELINE_CHANGED, () -> repository.commitBatch(stale, REPLACE, CURRENT));
            assertEquals(1, files(path));
        }
        assertEquals(2, repository.revision());
    }

    @Test public void concurrentChangeWhileReadingRejectsBeforeStaging() throws Exception {
        Path path = root();
        IslandRuleRepository repository = repo(path);
        byte[] body = pack(item(0, RULE));
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch advance = new CountDownLatch(1);
        InputStream block = new InputStream() {
            final ByteArrayInputStream delegate = new ByteArrayInputStream(body);
            @Override public int read() throws IOException {
                reading.countDown();
                try {
                    if (!advance.await(5, TimeUnit.SECONDS)) throw new IOException("read timeout");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException(failure);
                }
                return delegate.read();
            }
        };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<IslandRuleRepository.PendingBatch> result = worker.submit(() -> repository.prepareBatch(block, CURRENT));
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            single(repository, RULE, false);
            advance.countDown();
            Throwable failure = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS)).getCause();
            assertEquals(IslandRuleRepository.Code.BASELINE_CHANGED, ((IslandRuleRepository.Failure) failure).code);
            assertEquals(1, files(path));
        } finally {
            advance.countDown();
            worker.shutdownNow();
        }
    }

    @Test public void oldReadersDisabledDuplicateFilesAndOtherStagesSharePhysicalQuota() throws Exception {
        Path path = root();
        IslandRuleRepository repository = repo(path);
        byte[] thirtyOne = copies(31, 65536);
        try (IslandRuleRepository.PendingBatch full = prepare(repository, thirtyOne)) {
            assertEquals(0, full.enabledCount);
            repository.commitBatch(full, REPLACE, CURRENT);
        }
        try (IslandRuleRepository.ReadView old = repository.openRead(); IslandRuleRepository.PendingBatch clear = prepare(repository, pack())) {
            repository.commitBatch(clear, CLEAR, CURRENT);
            assertEquals(31, files(path));
            rejects(IslandRuleRepository.Code.REPOSITORY_CAPACITY, () -> prepare(repository, copies(2, 65536)));
            try (IslandRuleRepository.PendingBatch exact = prepare(repository, copies(1, 65536))) {
                assertEquals(32, files(path));
                rejects(IslandRuleRepository.Code.REPOSITORY_CAPACITY, () -> prepare(repository, pack(item(7, RULE))));
            }
            assertEquals(31, files(path));
            assertArrayEquals(pad(65536), old.read(old.entries.get(30)));
            assertEquals(2, repository.revision());
        }
        assertEquals(0, files(path));
        try (IslandRuleRepository.PendingBatch nowFits = prepare(repository, copies(32, 65536))) {
            assertEquals(3, repository.commitBatch(nowFits, REPLACE, CURRENT));
        }
        assertEquals(32, files(path));
    }

    @Test public void tamperedStagedRuleOrManifestCannotReplaceCurrent() throws Exception {
        Path path = root();
        boolean[] tamperNext = {false};
        IslandRuleRepository repository = new IslandRuleRepository(path, directory -> {
            if (tamperNext[0] && Files.exists(path.resolve("manifest.next"))) Files.write(path.resolve("manifest.next"), new byte[] {1});
        });
        single(repository, RULE, false);
        byte[] manifest = Files.readAllBytes(path.resolve("manifest"));
        String blob;
        try (IslandRuleRepository.ReadView view = repository.openRead()) { blob = view.entries.get(0).blob; }
        try (IslandRuleRepository.PendingBatch batch = prepare(repository, pack(item(0, RULE)))) {
            Files.write(extraBlob(path, blob), new byte[] {1});
            rejects(IslandRuleRepository.Code.CORRUPT_REPOSITORY, () -> repository.commitBatch(batch, REPLACE, CURRENT));
        }
        try (IslandRuleRepository.PendingBatch batch = prepare(repository, pack(item(0, RULE)))) {
            tamperNext[0] = true;
            rejects(IslandRuleRepository.Code.CORRUPT_REPOSITORY, () -> repository.commitBatch(batch, REPLACE, CURRENT));
        }
        assertArrayEquals(manifest, Files.readAllBytes(path.resolve("manifest")));
        assertEquals(1, files(path));
        assertFalse(Files.exists(path.resolve("manifest.next")));
    }

    @Test public void stagedBlobTamperedDuringDirectorySyncCannotBePublished() throws Exception {
        Path path = root();
        Path[] staged = {null};
        IslandRuleRepository repository = new IslandRuleRepository(path, directory -> {
            if (staged[0] != null && Files.exists(path.resolve("manifest.next"))) {
                byte[] bytes = Files.readAllBytes(staged[0]);
                bytes[0] ^= 1;
                Files.write(staged[0], bytes);
            }
        });
        single(repository, RULE, false);
        byte[] old = Files.readAllBytes(path.resolve("manifest"));
        String blob;
        try (IslandRuleRepository.ReadView view = repository.openRead()) { blob = view.entries.get(0).blob; }
        try (IslandRuleRepository.PendingBatch batch = prepare(repository, pack(item(0, RULE)))) {
            staged[0] = extraBlob(path, blob);
            rejects(IslandRuleRepository.Code.CORRUPT_REPOSITORY, () -> repository.commitBatch(batch, REPLACE, CURRENT));
        }
        assertArrayEquals(old, Files.readAllBytes(path.resolve("manifest")));
        assertEquals(1, files(path));
        assertFalse(Files.exists(path.resolve("manifest.next")));
    }

    @Test public void interruptionBeforeEmptyManifestSwitchKeepsOldRevision() throws Exception {
        Path path = root();
        boolean[] interrupt = {false};
        IslandRuleRepository repository = new IslandRuleRepository(path, directory -> {
            if (interrupt[0] && Files.exists(path.resolve("manifest.next"))) Thread.currentThread().interrupt();
        });
        single(repository, RULE, false);
        try (IslandRuleRepository.PendingBatch batch = prepare(repository, pack())) {
            interrupt[0] = true;
            try {
                assertThrows(java.io.InterruptedIOException.class, () -> repository.commitBatch(batch, CLEAR, CURRENT));
            } finally {
                Thread.interrupted();
            }
            rejects(IslandRuleRepository.Code.CLOSED, () -> repository.commitBatch(batch, CLEAR, CURRENT));
        }
        assertEquals(1, repository.revision());
        assertEquals(1, files(path));
        assertFalse(Files.exists(path.resolve("manifest.next")));
    }

    @Test public void reopenAfterAbandonedBatchDiscardsStagedFilesAndManifest() throws Exception {
        Path path = root();
        IslandRuleRepository repository = repo(path);
        single(repository, RULE, false);
        byte[] old = Files.readAllBytes(path.resolve("manifest"));
        prepare(repository, pack(item(0, RULE), item(1, RULE)));
        assertEquals(3, files(path));
        Files.write(path.resolve("manifest.next"), new byte[] {1, 2, 3});

        // 模拟原进程退出，第二个仓库实例独占恢复目录。
        try (IslandRuleRepository.ReadView view = repo(path).openRead()) {
            assertEquals(1, view.revision);
            assertEquals(1, view.entries.size());
            assertArrayEquals(utf8(RULE), view.read(view.entries.get(0)));
        }
        assertArrayEquals(old, Files.readAllBytes(path.resolve("manifest")));
        assertEquals(1, files(path));
        assertFalse(Files.exists(path.resolve("manifest.next")));
    }

    @Test public void failuresBeforeManifestSyncAndAtAtomicMoveKeepPreviousRevision() throws Exception {
        Path path = root();
        boolean[] failSync = {false};
        boolean[] failMove = {false};
        IslandRuleRepository repository = new IslandRuleRepository(path, directory -> {
            if (failSync[0]) throw new IOException("pre-switch sync");
        }, (source, destination) -> {
            if (failMove[0]) throw new AtomicMoveNotSupportedException("test", "test", "injected");
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        });
        single(repository, RULE, false);
        byte[] old = Files.readAllBytes(path.resolve("manifest"));
        for (int stage = 0; stage < 3; stage++) {
            try (IslandRuleRepository.PendingBatch batch = prepare(repository, pack(item(0, RULE), item(1, RULE)))) {
                failSync[0] = stage == 1;
                failMove[0] = stage == 2;
                Path next = path.resolve("manifest.next");
                if (stage == 0) {
                    Files.createDirectory(next);
                    Files.write(next.resolve("block"), new byte[] {1});
                }
                assertThrows(IOException.class, () -> repository.commitBatch(batch, REPLACE, CURRENT));
                if (stage == 0) {
                    Files.delete(next.resolve("block"));
                    Files.delete(next);
                }
                assertArrayEquals(old, Files.readAllBytes(path.resolve("manifest")));
                assertEquals(1, files(path));
            } finally {
                failSync[0] = false;
                failMove[0] = false;
            }
        }
        assertEquals(1, repo(path).revision());
    }

    @Test public void postSwitchSyncFailureKeepsBothFilesUntilDurabilityRetry() throws Exception {
        Path path = root();
        boolean[] failSync = {false};
        IslandRuleRepository repository = new IslandRuleRepository(path, directory -> {
            if (failSync[0] && !Files.exists(path.resolve("manifest.next"))) throw new IOException("post-switch sync");
        });
        single(repository, RULE, false);
        try (IslandRuleRepository.PendingBatch batch = prepare(repository, pack(item(0, RULE), item(1, RULE)))) {
            failSync[0] = true;
            rejects(IslandRuleRepository.Code.DURABILITY_UNCERTAIN, () -> repository.commitBatch(batch, REPLACE, CURRENT));
            rejects(IslandRuleRepository.Code.CLOSED, () -> repository.commitBatch(batch, REPLACE, CURRENT));
            assertEquals(3, files(path));
            assertThrows(IOException.class, repository::revision);
            failSync[0] = false;
        }
        assertEquals(2, repository.revision());
        try (IslandRuleRepository.ReadView view = repository.openRead()) { assertEquals(2, view.entries.size()); }
        assertEquals(2, files(path));
        assertEquals(2, repo(path).revision());
    }

    @Test public void cleanupFailureReportsCommittedAndRestartCanReclaimOldFiles() throws Exception {
        Path path = root();
        Path block = path.resolve("rule-block.blob");
        boolean[] inject = {false};
        IslandRuleRepository repository = new IslandRuleRepository(path, directory -> {
            if (inject[0] && !Files.exists(path.resolve("manifest.next"))) {
                Files.createDirectory(block);
                Files.write(block.resolve("keep"), new byte[] {1});
                inject[0] = false;
            }
        });
        single(repository, RULE, false);
        try (IslandRuleRepository.PendingBatch batch = prepare(repository, pack(item(0, RULE), item(1, RULE)))) {
            inject[0] = true;
            rejects(IslandRuleRepository.Code.COMMITTED_CLEANUP_FAILED, () -> repository.commitBatch(batch, REPLACE, CURRENT));
            assertEquals(2, repository.revision());
        }
        Files.delete(block.resolve("keep"));
        Files.delete(block);
        try (IslandRuleRepository.ReadView view = repo(path).openRead()) {
            assertEquals(2, view.revision);
            assertEquals(2, view.entries.size());
        }
        assertEquals(2, files(path));
    }
}
