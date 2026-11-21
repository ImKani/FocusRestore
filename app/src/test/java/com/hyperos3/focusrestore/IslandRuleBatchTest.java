/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class IslandRuleBatchTest {
    private static final IslandRuleImportValidator.Environment ENV = new IslandRuleImportValidator.Environment("test", 1L);
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private Path root() throws IOException { return temp.newFolder().toPath().resolve("rules"); }
    private IslandRuleRepository repository(Path path) throws IOException { return new IslandRuleRepository(path, ignored -> {}); }
    private byte[] rule() throws IOException { return Files.readAllBytes(Paths.get("docs", "samples", "rule-travel.valid.json")); }

    private long commit(IslandRuleRepository repository, byte[] data, boolean enabled) throws IOException {
        try (IslandRuleRepository.Pending proposal = repository.prepare(new ByteArrayInputStream(data), null, enabled, 9, ENV)) {
            return repository.commit(proposal, false, ENV);
        }
    }

    private long deleteOnlyRule(IslandRuleRepository repository) throws IOException {
        try (IslandRuleRepository.ReadView view = repository.openRead()) {
            return repository.delete(view.revision, view.entries.get(0).id);
        }
    }

    private static byte[] send(IslandRuleRepository.ReadView view) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IslandRuleBatch.write(output, view);
        return output.toByteArray();
    }

    private static byte[] send(IslandRuleRepository repository) throws IOException {
        try (IslandRuleRepository.ReadView view = repository.openRead()) { return send(view); }
    }

    private static IslandRuleBatch read(byte[] batch, long revision) throws IOException {
        return IslandRuleBatch.read(new ByteArrayInputStream(batch), revision, ENV);
    }

    @Test public void uninitializedAndCorruptRepositoriesAreNeverValidEmptyBatches() throws Exception {
        Path root = root();
        IslandRuleRepository repository = repository(root);
        assertEquals(0, repository.revision());
        assertEquals(IslandRuleRepository.Code.UNINITIALIZED,
                assertThrows(IslandRuleRepository.Failure.class, () -> send(repository)).code);
        try (IslandRuleRepository.Pending invalid = repository.prepare(
                new ByteArrayInputStream("[]".getBytes(StandardCharsets.UTF_8)), null, true, 0, ENV)) {
            assertFalse(invalid.canApprove(false));
            assertThrows(IslandRuleRepository.Failure.class, () -> repository.commit(invalid, false, ENV));
        }
        assertEquals(0, repository.revision());
        assertEquals(1, commit(repository, rule(), true));
        assertEquals(2, deleteOnlyRule(repository));
        assertTrue(read(send(repository(root)), 2).sources.isEmpty());
        Files.write(root.resolve("manifest"), new byte[]{1});
        assertThrows(IOException.class, () -> repository(root));
        assertThrows(IOException.class, () -> send(repository));
    }

    @Test public void emptyCommitKeepsPinnedOlderBytesAndPublishesPositiveNewRevision() throws Exception {
        Path root = root();
        IslandRuleRepository repository = repository(root);
        byte[] original = rule();
        assertEquals(1, commit(repository, original, true));
        try (IslandRuleRepository.ReadView old = repository.openRead()) {
            assertEquals(1, repository.revision());
            assertEquals(2, deleteOnlyRule(repository));
            assertArrayEquals(original, old.read(old.entries.get(0)));
            assertEquals(1, read(send(old), 1).sources.size());
            IslandRuleBatch batch = read(send(repository), 2);
            assertEquals(2, batch.revision);
            assertTrue(batch.sources.isEmpty());
        }
        assertEquals(2, repository(root).revision());
    }

    @Test public void emptyBatchStillRequiresKnownSystemUiVersion() throws Exception {
        IslandRuleRepository repository = repository(root());
        commit(repository, rule(), true);
        deleteOnlyRule(repository);
        byte[] batch = send(repository);
        IslandRuleImportValidator.Environment unknown = new IslandRuleImportValidator.Environment(null, null);
        assertEquals("BATCH_SYSTEM_UI_VERSION_UNKNOWN", assertThrows(IOException.class,
                () -> IslandRuleBatch.read(new ByteArrayInputStream(batch), 2, unknown)).getMessage());
    }

    @Test public void changedSystemUiVersionNeedsApprovalForCurrentBlockingFinding() throws Exception {
        IslandRuleRepository repository = repository(root());
        byte[] versioned = new String(rule(), StandardCharsets.UTF_8)
                .replace("\"systemUiVersion\": null", "\"systemUiVersion\": {\"name\":\"test\",\"code\":1}")
                .getBytes(StandardCharsets.UTF_8);
        commit(repository, versioned, true);
        byte[] approved = send(repository);
        IslandRuleImportValidator.Environment changed = new IslandRuleImportValidator.Environment("updated", 2L);
        assertEquals("BATCH_RULE_VALIDATION", assertThrows(IOException.class,
                () -> IslandRuleBatch.read(new ByteArrayInputStream(approved), 1, changed)).getMessage());
        try (IslandRuleRepository.Pending candidate = repository.prepare(
                new ByteArrayInputStream(versioned), null, true, 9, changed)) {
            assertEquals(1, candidate.validation.findings.stream().filter(finding -> finding.blocking).count());
            repository.commit(candidate, true, changed);
        }
        // 新清单仍含旧的普通批准，审批只对所属条目生效。
        byte[] mixed = send(repository);
        assertThrows(IOException.class, () -> IslandRuleBatch.read(new ByteArrayInputStream(mixed), 2, changed));
    }

    @Test public void truncatedWrongDigestMixedRevisionsAndTrailingDataFailClosed() throws Exception {
        IslandRuleRepository repository = repository(root());
        commit(repository, rule(), true);
        byte[] original = send(repository);
        int manifestLength = ByteBuffer.wrap(original).getInt(16);
        int blobOffset = 20 + manifestLength;
        for (int length : new int[]{8, 22, blobOffset + 1, original.length - 1}) {
            assertThrows(IOException.class, () -> read(Arrays.copyOf(original, length), 1));
        }
        byte[] wrongDigest = original.clone();
        wrongDigest[blobOffset] ^= 1;
        assertEquals("BATCH_DIGEST", assertThrows(IOException.class, () -> read(wrongDigest, 1)).getMessage());
        byte[] mixedManifest = original.clone();
        ByteBuffer.wrap(mixedManifest).putLong(8, 2L);
        assertEquals("BATCH_MANIFEST_REVISION", assertThrows(IOException.class, () -> read(mixedManifest, 2)).getMessage());
        byte[] mixedTrailer = original.clone();
        ByteBuffer.wrap(mixedTrailer).putLong(original.length - 8, 2L);
        assertEquals("BATCH_TRAILER", assertThrows(IOException.class, () -> read(mixedTrailer, 1)).getMessage());
        assertThrows(IOException.class, () -> read(Arrays.copyOf(original, original.length + 1), 1));
        byte[] hugeManifest = original.clone();
        ByteBuffer.wrap(hugeManifest).putInt(16, IslandRuleRepository.MAX_MANIFEST_BYTES + 1);
        assertEquals("BATCH_MANIFEST_SIZE", assertThrows(IOException.class, () -> read(hugeManifest, 1)).getMessage());
        byte[] badManifest = original.clone();
        badManifest[20] ^= 1;
        assertThrows(IOException.class, () -> read(badManifest, 1));
    }

    @Test public void disabledBatchCanPublishAndCorruptBlobCannotBeStreamed() throws Exception {
        Path root = root();
        IslandRuleRepository repository = repository(root);
        byte[] data = rule();
        commit(repository, data, false);
        IslandRuleBatch batch = read(send(repository), 1);
        IslandRulePublisher publisher = new IslandRulePublisher(() -> 0);
        IslandRulePublisher.Head head = new IslandRulePublisher.Head("app", 1);
        IslandRulePublisher.Task task = publisher.begin();
        assertTrue(publisher.target(task, head));
        assertTrue(publisher.finish(task, head, publisher.load(task, batch.revision, batch.sources)));
        assertTrue(publisher.current().loaded);
        assertEquals(1, publisher.current().ruleSet.entries.size());
        assertEquals(data.length, publisher.current().ruleSet.totalUtf8Bytes);
        assertTrue(publisher.current().orderedActiveEntries.isEmpty());
        try (IslandRuleRepository.ReadView view = repository.openRead()) {
            Files.write(root.resolve("rule-" + view.entries.get(0).blob + ".blob"), new byte[]{1});
            assertThrows(IOException.class, () -> send(view));
        }
        assertThrows(IOException.class, () -> repository(root));
    }
}
