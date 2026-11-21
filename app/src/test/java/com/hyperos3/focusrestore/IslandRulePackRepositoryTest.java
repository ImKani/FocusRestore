/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
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
    private static final java.util.Set<Integer> NONE = Collections.emptySet();
    private static final java.util.Set<Integer> FIRST = Collections.singleton(0);
    private static final String RULE = "{\"ruleVersion\":2,\"meta\":{\"packageName\":\"com.test.rule\",\"templateType\":\"baseInfo\","
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
    private static byte[] pad(int index, int size) {
        byte[] source = utf8(RULE.replace("com.test.rule", "com.test.rule" + index));
        byte[] bytes = Arrays.copyOf(source, size);
        Arrays.fill(bytes, source.length, size, (byte) ' ');
        return bytes;
    }

    private static byte[] copies(int offset, int count, int size) throws IOException {
        List<IslandRulePack.Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) entries.add(IslandRulePackTest.entry(i, false, i, pad(offset + i, size)));
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

    @Test public void currentStorageDoesNotReadOrModifyLegacyRulesAndConsent() throws Exception {
        Path filesDir = temp.newFolder().toPath();
        Path oldRoot = Files.createDirectory(filesDir.resolve("island-rules"));
        Path oldManifest = oldRoot.resolve("manifest");
        Path oldBlob = oldRoot.resolve("rule-00000000-0000-0000-0000-000000000001.blob");
        byte[] betaBlob = utf8("old rule bytes");
        Files.write(oldBlob, betaBlob);
        Path oldGrant = filesDir.resolve("island-projection-consent");
        IslandRuleProjectionConsent oldConsent = new IslandRuleProjectionConsent(oldGrant, ignored -> {});
        Path currentRoot = filesDir.resolve(IslandRuleRepository.DIRECTORY_NAME);
        IslandRuleRepository repository = repo(currentRoot);
        assertEquals(0, repository.revision());
        IslandCaptureEditor.Candidate eligible = IslandCaptureEditor.candidate("com.test.rule",
                Collections.singletonList(IslandRulePaths.find("/baseInfo/title")), 0L);
        assertNotNull(eligible);
        single(repository, new String(eligible.bytes(), StandardCharsets.UTF_8), false);
        byte[] strict;
        try (IslandRuleRepository.ReadView view = repository.openRead()) {
            strict = view.exportManifest();
            assertNull(view.readSample(view.entries.get(0)));
        }
        Files.write(oldManifest, strict);
        byte[] privateManifest = Files.readAllBytes(currentRoot.resolve("manifest"));
        assertEquals(0x4652494d, ByteBuffer.wrap(privateManifest).getInt());
        assertEquals(2, ByteBuffer.wrap(privateManifest).getInt(4));
        assertEquals(0, privateManifest[privateManifest.length - 65]);
        rejects(IslandRuleRepository.Code.CORRUPT_REPOSITORY, () -> repo(oldRoot));
        assertEquals(1, repo(currentRoot).revision());

        oldConsent.approve(repository, oldConsent.preview(repository));
        byte[] previousGrant = Files.readAllBytes(oldGrant);
        IslandRuleProjectionConsent currentConsent = new IslandRuleProjectionConsent(
                filesDir.resolve(IslandRuleProjectionConsent.FILE_NAME), ignored -> {});
        assertNotNull(oldConsent.read(repository));
        assertFalse(currentConsent.preview(repository).approved);
        assertNull(currentConsent.read(repository));
        currentConsent.approve(repository, currentConsent.preview(repository));
        assertNotNull(currentConsent.read(repository));
        currentConsent.revoke();
        assertArrayEquals(previousGrant, Files.readAllBytes(oldGrant));
        assertArrayEquals(strict, Files.readAllBytes(oldManifest));
        assertArrayEquals(betaBlob, Files.readAllBytes(oldBlob));
    }

    @Test public void privateExamplesSurviveReopenAndDefaultExportDropsValuesOnly() throws Exception {
        Path source = root();
        IslandRuleRepository repository = repo(source);
        IslandRuleSample sample = sample("仅供测试的示例889");
        String id = saveSample(repository, sample);
        repository = repo(source);
        try (IslandRuleRepository.ReadView view = repository.openRead()) {
            IslandRuleRepository.Entry item = view.entries.get(0);
            assertEquals(id, item.id);
            assertArrayEquals(utf8(RULE), view.read(item));
            assertEquals("仅供测试的示例889", view.readSample(item).fields.get(0).value);
            byte[] strictManifest = view.exportManifest();
            assertEquals(1, ByteBuffer.wrap(strictManifest).getInt(4));
            assertEquals(id, IslandRuleRepository.decodeManifest(strictManifest).entries.get(0).id);
            assertFalse(new String(strictManifest, StandardCharsets.ISO_8859_1).contains("sample-"));
        }
        byte[] privateManifest = Files.readAllBytes(source.resolve("manifest"));
        assertEquals(2, ByteBuffer.wrap(privateManifest).getInt(4));
        rejects(IslandRuleRepository.Code.CORRUPT_REPOSITORY, () -> IslandRuleRepository.decodeManifest(privateManifest));

        byte[] redacted = repository.exportPack();
        byte[] original = repository.exportPackSnapshot(true).bytes;
        assertEquals(2, ByteBuffer.wrap(redacted).getInt(4));
        assertEquals(2, ByteBuffer.wrap(original).getInt(4));
        assertFalse(new String(redacted, StandardCharsets.UTF_8).contains("仅供测试的示例889"));
        assertTrue(new String(original, StandardCharsets.UTF_8).contains("仅供测试的示例889"));
        for (byte[] bytes : Arrays.asList(redacted, original)) {
            boolean hasValues = bytes == original;
            IslandRuleRepository destination = repo(root());
            try (IslandRuleRepository.PendingBatch candidate = prepare(destination, bytes)) {
                assertEquals(1, destination.commitBatch(candidate, NONE, CURRENT).revision);
            }
            try (IslandRuleRepository.ReadView view = destination.openRead()) {
                IslandRuleSample imported = view.readSample(view.entries.get(0));
                assertEquals(hasValues, imported.hasValues());
                assertEquals("/baseInfo/title", imported.fields.get(0).path);
                assertEquals(IslandCapturePreview.Type.STRING, imported.fields.get(0).type);
                assertEquals(hasValues ? "仅供测试的示例889" : null, imported.fields.get(0).value);
                assertEquals("/param_island/content", imported.fields.get(1).path);
                assertEquals(IslandCapturePreview.Type.BOOLEAN, imported.fields.get(1).type);
                assertEquals(hasValues ? "false" : null, imported.fields.get(1).value);
            }
        }
    }

    @Test public void fullCatalogFitsThePrivateManifestWithoutGrowingTheIpcLimit() throws Exception {
        Path source = root();
        IslandRuleRepository repository = repo(source);
        IslandRuleSample catalog = IslandRuleSample.decode(sample("示例").encode(false));
        List<IslandRulePack.Entry> entries = new ArrayList<>();
        for (int index = 0; index < IslandRuleRepository.MAX_ENTRIES; index++) {
            entries.add(new IslandRulePack.Entry(new java.util.UUID(0, index), false, 0, utf8(RULE.replace("com.test.rule", "com.test.rule" + index)), catalog));
        }
        try (IslandRuleRepository.PendingBatch candidate = prepare(repository, IslandRulePack.encode(entries))) {
            assertEquals(1, repository.commitBatch(candidate, NONE, CURRENT).revision);
        }
        assertTrue(Files.size(source.resolve("manifest")) > IslandRuleRepository.MAX_MANIFEST_BYTES);
        try (IslandRuleRepository.ReadView view = repo(source).openRead()) {
            assertEquals(IslandRuleRepository.MAX_ENTRIES, view.entries.size());
            assertTrue(view.exportManifest().length <= IslandRuleRepository.MAX_MANIFEST_BYTES);
            assertEquals(IslandRuleRepository.MAX_ENTRIES,
                    IslandRuleRepository.decodeManifest(view.exportManifest()).entries.size());
        }
    }

    @Test public void editAndMoveKeepPrivateExamplesUntilPinnedViewCloses() throws Exception {
        Path path = root();
        IslandRuleRepository repository = repo(path);
        String id = saveSample(repository, sample("保存的旧示例"));
        try (IslandRuleRepository.ReadView old = repository.openRead()) {
            try (IslandRuleRepository.Pending pending = repository.prepareUpdate(1, id, utf8(RULE + " "), CURRENT)) {
                assertEquals(2, repository.commit(pending, false, CURRENT));
            }
            assertEquals(3, repository.setEnabled(2, id, false, CURRENT));
            try (IslandRuleRepository.ReadView view = repository.openRead()) {
                IslandRuleRepository.Entry entry = view.entries.get(0);
                assertEquals(id, entry.id);
                assertFalse(entry.enabled);
                assertEquals("保存的旧示例", view.readSample(entry).fields.get(0).value);
            }
            try (IslandRuleRepository.Pending pending = repository.prepareUpdate(3, id, utf8(RULE), CURRENT,
                    sample("新的样本"))) {
                assertEquals(4, repository.commit(pending, false, CURRENT));
            }
            try (IslandRuleRepository.ReadView view = repository.openRead()) {
                assertEquals("新的样本", view.readSample(view.entries.get(0)).fields.get(0).value);
            }
            assertEquals(5, repository.delete(4, id));
            assertEquals("保存的旧示例", old.readSample(old.entries.get(0)).fields.get(0).value);
            assertEquals(2, files(path));
        }
        assertEquals(0, files(path));
    }

    @Test public void invalidSampleCannotPublishAndMissingSavedSampleLeavesRuleReadable() throws Exception {
        Path path = root();
        IslandRuleRepository repository = repo(path);
        try (IslandRuleRepository.Pending pending = repository.prepare(new ByteArrayInputStream(utf8(RULE)), null,
                true, 0, CURRENT, sample("示例"))) {
            Files.write(sampleFile(path), new byte[] {0});
            rejects(IslandRuleRepository.Code.SAMPLE_CORRUPT, () -> repository.commit(pending, false, CURRENT));
        }
        assertEquals(0, files(path));
        saveSample(repository, sample("示例"));
        Files.delete(sampleFile(path));
        IslandRuleRepository reopened = repo(path);
        try (IslandRuleRepository.ReadView view = reopened.openRead()) {
            IslandRuleRepository.Entry entry = view.entries.get(0);
            assertArrayEquals(utf8(RULE), view.read(entry));
            IslandRuleRepository.decodeManifest(view.exportManifest());
            rejects(IslandRuleRepository.Code.SAMPLE_CORRUPT, () -> view.readSample(entry));
        }
    }

    private static IslandRuleSample sample(String text) throws IOException {
        return IslandRuleSample.capture(Arrays.asList(
                IslandCapturePreview.Field.create(IslandRulePaths.find("/baseInfo/title"),
                        IslandCapturePreview.Type.STRING, text, null),
                IslandCapturePreview.Field.create(IslandRulePaths.find("/param_island/content"),
                        IslandCapturePreview.Type.BOOLEAN, null, false)));
    }

    private static String saveSample(IslandRuleRepository repository, IslandRuleSample sample) throws IOException {
        try (IslandRuleRepository.Pending pending = repository.prepare(new ByteArrayInputStream(utf8(RULE)), null,
                true, 11, CURRENT, sample)) {
            repository.commit(pending, false, CURRENT);
            return pending.entry.id;
        }
    }

    private static Path sampleFile(Path path) throws IOException {
        try (Stream<Path> files = Files.list(path)) {
            return files.filter(item -> item.getFileName().toString().startsWith("sample-")).findFirst().get();
        }
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
            assertEquals(2, batch.rows.size());
            assertEquals(IslandRuleRepository.BatchRowStatus.DUPLICATE, batch.rows.get(0).status);
            assertEquals(IslandRuleRepository.BatchRowStatus.ADD, batch.rows.get(1).status);
            assertEquals(IslandRulePackTest.decode(packed).digest, batch.packDigest);
            assertEquals(1, repository.revision());
            assertEquals(2, repository.commitBatch(batch, FIRST, CURRENT).revision);
            rejects(IslandRuleRepository.Code.CLOSED, () -> repository.commitBatch(batch, FIRST, CURRENT));
            assertEquals(3, files(path));
            assertArrayEquals(utf8(RULE), old.read(old.entries.get(0)));
            try (IslandRuleRepository.ReadView view = repository.openRead()) {
                assertEquals(2, view.entries.size());
                IslandRuleRepository.Entry first = view.entries.get(0);
                assertEquals(original, first.id);
                assertNotEquals(IslandRulePackTest.decode(packed).entries.get(0).id.toString(), first.id);
                assertEquals(2, first.revision);
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

    private static byte[] typed(String scene, String... paths) {
        IslandNotificationType type = IslandNotificationType.create("1", "travel", scene,
                Arrays.asList("/baseInfo/title", "/param_island/content"));
        List<IslandRulePaths.Entry> selection = new ArrayList<>();
        for (String path : paths) selection.add(IslandRulePaths.find(path));
        return IslandCaptureEditor.candidate("com.test.rule", selection, 0L, type).bytes();
    }

    private static IslandRulePack.Entry typedItem(int id, String scene, String... paths) throws IOException {
        return new IslandRulePack.Entry(new java.util.UUID(0, id), false, -3, typed(scene, paths), sample("导入样本"));
    }

    @Test public void typedMergeKeepsLocalPositionsAndSkipsUnconfirmedDuplicates() throws Exception {
        IslandRuleRepository repository = repo(root());
        String first = single(repository, new String(typed("pickup", "/baseInfo/title"), StandardCharsets.UTF_8), false);
        String second = single(repository, new String(typed("arrived", "/baseInfo/title"), StandardCharsets.UTF_8), false);
        IslandRulePack.Entry incoming = typedItem(10, "pickup", "/param_island/content", "/baseInfo/title");
        byte[] body = IslandRulePack.encode(Arrays.asList(typedItem(1, "arrived", "/param_island/content"), incoming,
                typedItem(2, "dropoff", "/baseInfo/title"), typedItem(3, "pickup", "/baseInfo/title")), true);
        try (IslandRuleRepository.PendingBatch preview = prepare(repository, body)) {
            assertEquals(IslandRuleRepository.BatchRowStatus.DUPLICATE, preview.rows.get(0).status);
            assertEquals(IslandRuleRepository.BatchRowStatus.DUPLICATE, preview.rows.get(1).status);
            assertEquals(Arrays.asList("/param_island/content", "/baseInfo/title"), preview.rows.get(1).incoming.paths);
            assertEquals(Collections.singletonList("/baseInfo/title"), preview.rows.get(1).local.paths);
            assertEquals("DUPLICATE_IN_PACK", preview.rows.get(3).failureCode);
            IslandRuleRepository.BatchResult outcome = repository.commitBatch(preview, Collections.singleton(1), CURRENT);
            assertEquals(3, outcome.revision);
            assertNull(outcome.warning);
            assertEquals(IslandRuleRepository.BatchResultStatus.SKIPPED, outcome.rows.get(0).status);
            assertEquals(IslandRuleRepository.BatchResultStatus.REPLACED, outcome.rows.get(1).status);
            assertEquals(IslandRuleRepository.BatchResultStatus.ADDED, outcome.rows.get(2).status);
            assertEquals(IslandRuleRepository.BatchResultStatus.FAILED, outcome.rows.get(3).status);
        }
        try (IslandRuleRepository.ReadView view = repository.openRead()) {
            assertEquals(3, view.entries.size());
            IslandRuleRepository.Entry replaced = view.entries.get(0);
            assertEquals(first, replaced.id);
            assertEquals(0L, replaced.order);
            assertFalse(replaced.enabled);
            assertEquals(-3, replaced.priority);
            assertArrayEquals(incoming.bytes(), view.read(replaced));
            assertEquals("导入样本", view.readSample(replaced).fields.get(0).value);
            assertEquals(second, view.entries.get(1).id);
            assertEquals(1L, view.entries.get(1).revision);
            assertEquals(2L, view.entries.get(2).order);
        }
    }

    @Test public void ambiguousLocalTypeFailsWhileUnrelatedRowCommits() throws Exception {
        IslandRuleRepository repository = repo(root());
        for (String path : Arrays.asList("/baseInfo/title", "/param_island/content")) {
            single(repository, new String(typed("pickup", path), StandardCharsets.UTF_8), false);
        }
        try (IslandRuleRepository.PendingBatch preview = prepare(repository, pack(
                typedItem(1, "pickup", "/baseInfo/title"), typedItem(2, "arrived", "/baseInfo/title")))) {
            assertEquals("AMBIGUOUS_LOCAL", preview.rows.get(0).failureCode);
            assertNull(preview.rows.get(0).local);
            IslandRuleRepository.BatchResult outcome = repository.commitBatch(preview, NONE, CURRENT);
            assertEquals(IslandRuleRepository.BatchResultStatus.FAILED, outcome.rows.get(0).status);
            assertEquals(IslandRuleRepository.BatchResultStatus.ADDED, outcome.rows.get(1).status);
        }
        try (IslandRuleRepository.ReadView view = repository.openRead()) {
            assertEquals(3, view.entries.size());
            assertEquals(1, view.entries.get(0).revision);
            assertEquals(1, view.entries.get(1).revision);
        }
    }

    @Test public void noTypeUsesWholeDocumentAndOnlyExactApprovedBytesKeepLocalApproval() throws Exception {
        IslandRuleRepository repository = repo(root());
        String mismatch = VERSIONED.replace("\"code\":42", "\"code\":43");
        String id = single(repository, mismatch, true);
        try (IslandRuleRepository.PendingBatch preview = repository.prepareBatch(new ByteArrayInputStream(
                pack(item(0, mismatch), item(1, RULE))), OTHER)) {
            assertEquals(IslandRuleRepository.BatchRowStatus.DUPLICATE, preview.rows.get(0).status);
            assertEquals(IslandRuleRepository.BatchRowStatus.ADD, preview.rows.get(1).status);
            assertEquals(2, repository.commitBatch(preview, FIRST, OTHER).revision);
        }
        try (IslandRuleRepository.ReadView view = repository.openRead()) {
            IslandRuleRepository.Entry approved = view.entries.get(0);
            assertEquals(id, approved.id);
            assertTrue(approved.forced);
            assertFalse(approved.skipped.isEmpty());
            assertEquals(2, view.entries.size());
        }
    }

    @Test public void emptyIsNoOpAndPreservesRevision() throws Exception {
        IslandRuleRepository repository = repo(root());
        rejects(IslandRuleRepository.Code.UNINITIALIZED, repository::exportPack);
        assertThrows(IslandRulePack.Failure.class, () -> prepare(repository, utf8("[]")));
        byte[] empty = pack();
        try (IslandRuleRepository.PendingBatch stage = prepare(repository, empty)) {
            IslandRuleRepository.BatchResult result = repository.commitBatch(stage, NONE, CURRENT);
            assertFalse(result.changed);
            assertEquals(0, result.revision);
            assertTrue(result.rows.isEmpty());
        }
        rejects(IslandRuleRepository.Code.UNINITIALIZED, repository::exportPack);
        String id = single(repository, RULE, false);
        try (IslandRuleRepository.PendingBatch stage = prepare(repository, empty)) {
            assertEquals(1, repository.commitBatch(stage, NONE, CURRENT).revision);
        }
        try (IslandRuleRepository.ReadView view = repository.openRead()) { assertEquals(id, view.entries.get(0).id); }
    }

    @Test public void invalidRowsAreReportedAndValidRowsCanCommit() throws Exception {
        Path path = root();
        IslandRuleRepository repository = repo(path);
        single(repository, RULE, false);
        byte[] manifest = Files.readAllBytes(path.resolve("manifest"));
        List<String> invalidRules = Arrays.asList("{}", RULE.replace("\"ref\":\"field1\"", "\"ref\":\"field2\""),
                VERSIONED.replace("\"code\":42", "\"code\":43"), RULE.replace("\"ruleVersion\":2", "\"ruleVersion\":2,\"x-note\":\"synthetic\""));
        List<IslandRuleImportValidator.Stage> stages = Arrays.asList(IslandRuleImportValidator.Stage.L1,
                IslandRuleImportValidator.Stage.L3, IslandRuleImportValidator.Stage.L2, IslandRuleImportValidator.Stage.L1);
        for (int i = 0; i < invalidRules.size(); i++) {
            byte[] invalid = pack(item(0, RULE), IslandRulePackTest.entry(1, false, 0, utf8(invalidRules.get(i))));
            try (IslandRuleRepository.PendingBatch preview = prepare(repository, invalid)) {
                IslandRuleRepository.BatchRow row = preview.rows.get(1);
                assertEquals(1, row.declarationIndex);
                assertEquals(IslandRuleRepository.BatchRowStatus.FAILED, row.status);
                assertEquals(stages.get(i), row.findings.get(0).stage);
                assertTrue(row.findings.get(0).blocking);
                IslandRuleRepository.BatchResult outcome = repository.commitBatch(preview, NONE, CURRENT);
                assertFalse(outcome.changed);
                assertEquals(IslandRuleRepository.BatchResultStatus.SKIPPED, outcome.rows.get(0).status);
                assertEquals(IslandRuleRepository.BatchResultStatus.FAILED, outcome.rows.get(1).status);
            }
            assertArrayEquals(manifest, Files.readAllBytes(path.resolve("manifest")));
            assertEquals(1, files(path));
        }
    }

    @Test public void unverifiableIsVisibleAndEnvironmentChangeConsumesPendingWithoutMutation() throws Exception {
        Path path = root();
        IslandRuleRepository repository = repo(path);
        byte[] packed = pack(item(0, VERSIONED));
        try (IslandRuleRepository.PendingBatch stage = repository.prepareBatch(new ByteArrayInputStream(packed), null)) {
            assertEquals(1, stage.rows.get(0).findings.size());
            assertEquals("L2_UNVERIFIABLE", stage.rows.get(0).findings.get(0).code);
            assertFalse(stage.rows.get(0).findings.get(0).blocking);
            assertEquals(1, repository.commitBatch(stage, NONE, null).revision);
        }
        try (IslandRuleRepository.PendingBatch stage = prepare(repository, packed)) {
            rejects(IslandRuleRepository.Code.ENVIRONMENT_CHANGED, () -> repository.commitBatch(stage, FIRST, OTHER));
            rejects(IslandRuleRepository.Code.CLOSED, () -> repository.commitBatch(stage, NONE, CURRENT));
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
        try (IslandRuleRepository.PendingBatch stage = prepare(repository, exported)) {
            assertEquals("L2_ENVIRONMENT", stage.rows.get(0).failureCode);
        }
        try (IslandRuleRepository.PendingBatch stage = repository.prepareBatch(new ByteArrayInputStream(exported), OTHER)) {
            assertEquals(2, repository.commitBatch(stage, FIRST, OTHER).revision);
        }
        try (IslandRuleRepository.ReadView view = repository.openRead()) {
            assertFalse(view.entries.get(0).forced);
            assertTrue(view.entries.get(0).skipped.isEmpty());
        }
    }

    @Test public void unsupportedExportFieldsRejectWholeExportWithoutReadingCaptureState() throws Exception {
        for (String rule : Arrays.asList(RULE.replace("\"ruleVersion\":2", "\"ruleVersion\":2,\"x-note\":\"sample sentinel\""),
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
            rejects(IslandRuleRepository.Code.BASELINE_CHANGED, () -> repository.commitBatch(stale, NONE, CURRENT));
            assertEquals(1, files(path));
        }
        try (IslandRuleRepository.PendingBatch winner = prepare(repository, body); IslandRuleRepository.PendingBatch stale = prepare(repository, body)) {
            repository.commitBatch(winner, FIRST, CURRENT);
            rejects(IslandRuleRepository.Code.BASELINE_CHANGED, () -> repository.commitBatch(stale, NONE, CURRENT));
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

    @Test public void oldReadersSharePhysicalQuotaAndFailedRowsDoNotBlockSmallerRows() throws Exception {
        Path path = root();
        IslandRuleRepository repository = repo(path);
        try (IslandRuleRepository.PendingBatch full = prepare(repository, copies(0, 31, 65536))) {
            assertEquals(1, repository.commitBatch(full, NONE, CURRENT).revision);
        }
        try (IslandRuleRepository.ReadView old = repository.openRead()) {
            for (IslandRuleRepository.Entry entry : old.entries) repository.delete(repository.revision(), entry.id);
            assertEquals(31, files(path));
            byte[] body = pack(IslandRulePackTest.entry(0, false, 0, pad(99, 65536 - 4096)),
                    IslandRulePackTest.entry(1, false, 0, pad(98, 65536)), item(2, RULE));
            try (IslandRuleRepository.PendingBatch preview = prepare(repository, body)) {
                IslandRuleRepository.BatchResult result = repository.commitBatch(preview, NONE, CURRENT);
                assertTrue(result.changed);
                assertEquals(33, result.revision);
                assertEquals(IslandRuleRepository.BatchResultStatus.ADDED, result.rows.get(0).status);
                assertEquals("REPOSITORY_CAPACITY", result.rows.get(1).failureCode);
                assertEquals(IslandRuleRepository.BatchResultStatus.ADDED, result.rows.get(2).status);
            }
            assertEquals(33, files(path));
            assertArrayEquals(pad(30, 65536), old.read(old.entries.get(30)));
        }
        assertEquals(2, files(path));
        try (IslandRuleRepository.PendingBatch stage = prepare(repository, copies(100, 31, 65536))) {
            assertEquals(34, repository.commitBatch(stage, NONE, CURRENT).revision);
        }
        assertEquals(33, files(path));
        try (IslandRuleRepository.PendingBatch duplicate = prepare(repository, copies(100, 1, 65536))) {
            IslandRuleRepository.BatchResult result = repository.commitBatch(duplicate, NONE, CURRENT);
            assertEquals(IslandRuleRepository.BatchResultStatus.SKIPPED, result.rows.get(0).status);
            assertFalse(result.changed);
        }
    }

    @Test public void tamperedStagedManifestCannotReplaceCurrent() throws Exception {
        Path path = root();
        boolean[] tamperNext = {false};
        IslandRuleRepository repository = new IslandRuleRepository(path, directory -> {
            if (tamperNext[0] && Files.exists(path.resolve("manifest.next"))) Files.write(path.resolve("manifest.next"), new byte[] {1});
        });
        single(repository, RULE, false);
        byte[] manifest = Files.readAllBytes(path.resolve("manifest"));
        try (IslandRuleRepository.PendingBatch batch = prepare(repository, pack(item(0, RULE)))) {
            tamperNext[0] = true;
            rejects(IslandRuleRepository.Code.CORRUPT_REPOSITORY, () -> repository.commitBatch(batch, FIRST, CURRENT));
        }
        assertArrayEquals(manifest, Files.readAllBytes(path.resolve("manifest")));
        assertEquals(1, files(path));
        assertFalse(Files.exists(path.resolve("manifest.next")));
    }

    @Test public void stagedBlobTamperedDuringDirectorySyncCannotBePublished() throws Exception {
        Path path = root();
        String[] blob = {null};
        IslandRuleRepository repository = new IslandRuleRepository(path, directory -> {
            if (blob[0] != null && Files.exists(path.resolve("manifest.next"))) {
                Path staged = extraBlob(path, blob[0]);
                byte[] bytes = Files.readAllBytes(staged);
                bytes[0] ^= 1;
                Files.write(staged, bytes);
            }
        });
        single(repository, RULE, false);
        byte[] old = Files.readAllBytes(path.resolve("manifest"));
        try (IslandRuleRepository.ReadView view = repository.openRead()) { blob[0] = view.entries.get(0).blob; }
        try (IslandRuleRepository.PendingBatch batch = prepare(repository, pack(item(0, RULE)))) {
            rejects(IslandRuleRepository.Code.CORRUPT_REPOSITORY, () -> repository.commitBatch(batch, FIRST, CURRENT));
        }
        assertArrayEquals(old, Files.readAllBytes(path.resolve("manifest")));
        assertEquals(1, files(path));
        assertFalse(Files.exists(path.resolve("manifest.next")));
    }

    @Test public void interruptionBeforeManifestSwitchKeepsOldRevision() throws Exception {
        Path path = root();
        boolean[] interrupt = {false};
        IslandRuleRepository repository = new IslandRuleRepository(path, directory -> {
            if (interrupt[0] && Files.exists(path.resolve("manifest.next"))) Thread.currentThread().interrupt();
        });
        single(repository, RULE, false);
        try (IslandRuleRepository.PendingBatch batch = prepare(repository, pack(item(0, RULE)))) {
            interrupt[0] = true;
            try {
                assertThrows(java.io.InterruptedIOException.class, () -> repository.commitBatch(batch, FIRST, CURRENT));
            } finally {
                Thread.interrupted();
            }
            rejects(IslandRuleRepository.Code.CLOSED, () -> repository.commitBatch(batch, FIRST, CURRENT));
        }
        assertEquals(1, repository.revision());
        assertEquals(1, files(path));
        assertFalse(Files.exists(path.resolve("manifest.next")));
    }

    @Test public void reopenAfterAbandonedBatchLeavesNoPayloadFilesAndDiscardsStagedManifest() throws Exception {
        Path path = root();
        IslandRuleRepository repository = repo(path);
        single(repository, RULE, false);
        byte[] old = Files.readAllBytes(path.resolve("manifest"));
        prepare(repository, pack(item(0, RULE), item(1, VERSIONED)));
        assertEquals(1, files(path));
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
            try (IslandRuleRepository.PendingBatch batch = prepare(repository, pack(item(0, RULE), item(1, VERSIONED)))) {
                failSync[0] = stage == 1;
                failMove[0] = stage == 2;
                Path next = path.resolve("manifest.next");
                if (stage == 0) {
                    Files.createDirectory(next);
                    Files.write(next.resolve("block"), new byte[] {1});
                }
                assertThrows(IOException.class, () -> repository.commitBatch(batch, FIRST, CURRENT));
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
        try (IslandRuleRepository.PendingBatch batch = prepare(repository, pack(item(0, RULE), item(1, VERSIONED)))) {
            failSync[0] = true;
            IslandRuleRepository.BatchResult result = repository.commitBatch(batch, FIRST, CURRENT);
            assertEquals(IslandRuleRepository.Code.DURABILITY_UNCERTAIN, result.warning);
            assertEquals(0, result.confirmableRevision());
            assertEquals(IslandRuleRepository.BatchResultStatus.REPLACED, result.rows.get(0).status);
            assertEquals(IslandRuleRepository.BatchResultStatus.ADDED, result.rows.get(1).status);
            rejects(IslandRuleRepository.Code.CLOSED, () -> repository.commitBatch(batch, FIRST, CURRENT));
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
        try (IslandRuleRepository.PendingBatch batch = prepare(repository, pack(item(0, RULE), item(1, VERSIONED)))) {
            inject[0] = true;
            IslandRuleRepository.BatchResult result = repository.commitBatch(batch, FIRST, CURRENT);
            assertEquals(IslandRuleRepository.Code.COMMITTED_CLEANUP_FAILED, result.warning);
            assertEquals(2, result.confirmableRevision());
            assertEquals(IslandRuleRepository.BatchResultStatus.REPLACED, result.rows.get(0).status);
            assertEquals(IslandRuleRepository.BatchResultStatus.ADDED, result.rows.get(1).status);
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
