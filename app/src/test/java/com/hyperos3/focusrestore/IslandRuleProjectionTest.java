/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class IslandRuleProjectionTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private static final String TITLE = "/baseInfo/title";
    private static final String CONTENT = "/baseInfo/content";
    private static final String UI = "com.android.systemui";
    private static final String[] SHARED = {"android", UI, "com.android.settings"};

    @Test public void callerNeedsPlatformSharedUidAndVerifiedAttribution() {
        assertTrue(IslandRuleAccess.allowsProjectionRead(1000, 1000, true, true, UI, SHARED, true, true));
        assertFalse(IslandRuleAccess.allows(1000, 1000, true, true, UI, SHARED, true));
        assertFalse(IslandRuleAccess.allowsProjectionRead(1000, 1000, true, true, UI, new String[]{UI}, true, true));
        assertFalse(IslandRuleAccess.allowsProjectionRead(1001, 1001, true, true, UI, SHARED, true, true));
        assertFalse(IslandRuleAccess.allowsProjectionRead(1000, 1001, true, true, UI, SHARED, true, true));
        assertFalse(IslandRuleAccess.allowsProjectionRead(1000, 1000, true, true, "android", SHARED, true, true));
        assertFalse(IslandRuleAccess.allowsProjectionRead(1000, 1000, true, true, null, SHARED, true, true));
        assertFalse(IslandRuleAccess.allowsProjectionRead(1000, 1000, false, true, UI, SHARED, true, true));
        assertFalse(IslandRuleAccess.allowsProjectionRead(1000, 1000, true, false, UI, SHARED, true, true));
        assertFalse(IslandRuleAccess.allowsProjectionRead(1000, 1000, true, true, UI, SHARED, false, true));
        assertFalse(IslandRuleAccess.allowsProjectionRead(1000, 1000, true, true, UI, SHARED, true, false));
    }

    @Test public void consentDefaultsOffSurvivesRestartAndCanBeRevoked() throws Exception {
        Path root = folder.newFolder().toPath();
        IslandRuleRepository repository = repository(root);
        save(repository, rule("com.test.alpha", 0, CONTENT, TITLE), true, 0);
        Path file = root.resolve("consent");
        IslandRuleProjectionConsent consent = consent(file);
        assertNull(consent.read(repository));
        IslandRuleProjectionConsent.Preview preview = consent.preview(repository);
        assertFalse(preview.approved);
        assertNull(consent.read(repository));
        consent.approve(repository, preview);
        assertEquals(preview.snapshot.rows.get(0).paths,
                IslandRuleProjection.decode(consent.read(repository)).get(0).paths);
        assertTrue(consent(file).preview(repository).approved);
        assertArrayEquals(consent.read(repository), consent(file).read(repository));
        consent.revoke();
        assertNull(consent(file).read(repository));
        assertThrows(IOException.class, () -> consent.approve(repository, preview));
    }

    @Test public void excludesDisabledAndRulesWithUnprojectableFieldsAndSortsByPrecedence() throws Exception {
        IslandRuleRepository repository = repository(folder.newFolder().toPath());
        save(repository, rule("com.test.low", 0, TITLE), true, Integer.MIN_VALUE);
        save(repository, rule("com.test.first", 0, CONTENT, TITLE), true, Integer.MAX_VALUE);
        byte[] second = rule("com.test.second", 0, TITLE);
        save(repository, IslandPortableRule.rebuild(second, new IslandRuleCodec().decode(second).rule), true, Integer.MAX_VALUE);
        save(repository, rule("com.test.disabled", 0, TITLE), false, Integer.MAX_VALUE);
        byte[] extra = replace(rule("com.test.annotated", 0, TITLE), "{\"ruleVersion\"",
                "{\"x-note\":\"do not export\",\"ruleVersion\"");
        save(repository, extra, true, Integer.MAX_VALUE);
        save(repository, replace(rule("com.test.changed", 0, TITLE), " · ", "private value"), true, 0);
        save(repository, replace(rule("com.test.required", 0, TITLE), "\"path\":",
                "\"required\":true,\"path\":"), true, 0);
        IslandRuleProjection.Snapshot snapshot = consent(folder.newFolder().toPath().resolve("consent"))
                .preview(repository).snapshot;
        List<IslandRuleProjection.Row> rows = IslandRuleProjection.decode(snapshot.payload());
        assertEquals(3, rows.size());
        assertEquals("com.test.first", rows.get(0).packageName);
        assertEquals(Arrays.asList(CONTENT, TITLE), rows.get(0).paths);
        assertEquals("com.test.second", rows.get(1).packageName);
        assertEquals("com.test.low", rows.get(2).packageName);
        String wire = new String(snapshot.payload(), StandardCharsets.UTF_8);
        assertFalse(wire.contains("com.test.disabled"));
        assertFalse(wire.contains("do not export"));
        assertFalse(wire.contains("private value"));
        assertFalse(wire.contains("FocusRestore"));
        assertFalse(wire.contains("1970-01-01"));
    }

    @Test public void repositoryRevisionChangeRejectsReadsAndOpenConfirmations() throws Exception {
        Path root = folder.newFolder().toPath();
        IslandRuleRepository repository = repository(root);
        IslandRuleProjectionConsent consent = consent(root.resolve("consent"));
        save(repository, rule("com.test.first", 0, TITLE), true, 0);
        consent.approve(repository, consent.preview(repository));
        IslandRuleProjectionConsent.Preview stale = consent.preview(repository);
        save(repository, rule("com.test.new", 0, TITLE), false, 0);
        assertNull(consent.read(repository));
        assertEquals("RULE_PROJECTION_BASELINE_CHANGED",
                assertThrows(IOException.class, () -> consent.approve(repository, stale)).getMessage());
        assertFalse(consent.preview(repository).approved);
    }

    @Test public void changedEntryDigestInvalidatesEvenIfRevisionAndProjectedFieldsMatch() throws Exception {
        Path root = folder.newFolder().toPath();
        IslandRuleRepository original = repository(root);
        IslandRuleRepository replaced = repository(folder.newFolder().toPath());
        save(original, rule("com.test.same", 0, TITLE), true, 0);
        save(replaced, rule("com.test.same", 1000, TITLE), true, 0);
        IslandRuleProjectionConsent consent = consent(root.resolve("consent"));
        consent.approve(original, consent.preview(original));
        assertArrayEquals(consent.read(original), consent.preview(replaced).snapshot.payload());
        assertEquals(original.revision(), replaced.revision());
        assertNull(consent.read(replaced));
    }

    @Test public void corruptConsentAndUnsyncedRevocationFailClosed() throws Exception {
        Path root = folder.newFolder().toPath();
        IslandRuleRepository repository = repository(root);
        Path file = root.resolve("consent");
        save(repository, rule("com.test.first", 0, TITLE), true, 0);
        Files.write(file, new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> consent(file).read(repository));
        AtomicBoolean failSync = new AtomicBoolean();
        IslandRuleProjectionConsent consent = new IslandRuleProjectionConsent(file, directory -> {
            if (failSync.get()) throw new IOException("sync failed");
        });
        consent.revoke();
        consent.approve(repository, consent.preview(repository));
        failSync.set(true);
        assertThrows(IOException.class, consent::revoke);
        assertNull(consent.read(repository));
    }

    @Test public void sameSnapshotCannotBeApprovedAfterRevoke() throws Exception {
        Path root = folder.newFolder().toPath();
        IslandRuleRepository repository = repository(root);
        save(repository, rule("com.test.first", 0, TITLE), true, 0);
        IslandRuleProjectionConsent consent = consent(root.resolve("consent"));
        IslandRuleProjectionConsent.Preview preview = consent.preview(repository);
        consent.revoke();
        assertThrows(IOException.class, () -> consent.approve(repository, preview));
        assertNull(consent.read(repository));
    }

    @Test public void explicitReductionsKeepOnlyPreviouslyApprovedSurvivorsInOrderAcrossRestart() throws Exception {
        Path root = folder.newFolder().toPath();
        Path grantFile = root.resolve("consent");
        IslandRuleRepository store = repository(root);
        byte[] repeated = rule("com.test.same", 0, CONTENT, TITLE);
        save(store, repeated, true, 20);
        save(store, rule("com.test.mixed", 0, "/title", TITLE), true, 10);
        save(store, repeated, true, 0);
        save(store, rule("com.test.disabled", 0, CONTENT), false, 30);
        String first = entryId(store, 0), mixed = entryId(store, 1), last = entryId(store, 2);
        String disabled = entryId(store, 3);
        IslandRuleProjectionConsent consent = consent(grantFile);
        consent.approve(store, consent.preview(store));
        assertEquals(2, consent.read(store)[4]);
        assertEquals(IslandRuleProjectionConsent.ReductionResult.SAVED,
                consent.reduce(store, store.revision(), disabled, true));
        assertEquals(3, IslandRuleProjection.decode(consent.read(store)).size());
        assertEquals(IslandRuleProjectionConsent.ReductionResult.SAVED,
                consent.reduce(store, store.revision(), first, false));
        List<IslandRuleProjection.Row> rows = IslandRuleProjection.decode(consent.read(store));
        assertEquals(2, rows.size());
        assertEquals("com.test.mixed", rows.get(0).packageName);
        assertEquals(Arrays.asList(CONTENT, TITLE), rows.get(1).paths);
        assertEquals(IslandRuleProjectionConsent.ReductionResult.SAVED,
                consent.reduce(store, store.revision(), mixed, true));
        IslandRuleRepository reopened = repository(root);
        byte[] surviving = consent(grantFile).read(reopened);
        assertEquals(1, surviving[4]);
        assertEquals(1, IslandRuleProjection.decode(surviving).size());
        assertEquals("com.test.same", IslandRuleProjection.decode(surviving).get(0).packageName);
        consent.reduce(store, store.revision(), last, false);
        assertNull(consent(grantFile).read(repository(root)));
    }

    @Test public void reductionRequiresCurrentGrantAndRejectsStalePageOrWrongTarget() throws Exception {
        Path root = folder.newFolder().toPath();
        IslandRuleRepository store = repository(root);
        IslandRuleProjectionConsent consent = consent(root.resolve("consent"));
        save(store, rule("com.test.first", 0, TITLE), true, 0);
        save(store, rule("com.test.second", 0, CONTENT), true, 0);
        String second = entryId(store, 1);
        long baseline = store.revision();
        consent.approve(store, consent.preview(store));
        assertThrows(IslandRuleRepository.Failure.class, () -> consent.reduce(store, baseline - 1, second, true));
        assertThrows(IslandRuleRepository.Failure.class, () -> consent.reduce(store, baseline, "missing", false));
        assertEquals(baseline, store.revision());
        assertEquals(2, IslandRuleProjection.decode(consent.read(store)).size());
        // 直接停用、新增和重新启用均按普通修订失效，之后的缩减不能复活过期授权。
        store.setEnabled(baseline, second, false, null);
        assertNull(consent.read(store));
        store.setEnabled(store.revision(), second, true, null);
        save(store, rule("com.test.new", 0, TITLE), true, 0);
        consent.reduce(store, store.revision(), second, true);
        assertNull(consent.read(store));
        consent.revoke();
        long noGrant = store.revision();
        assertEquals(IslandRuleProjectionConsent.ReductionResult.SAVED,
                consent.reduce(store, noGrant, entryId(store, 0), false));
        assertEquals(noGrant + 1, store.revision());
        assertNull(consent.read(store));
    }

    @Test public void subsetCheckDistinguishesIdenticalRowsByIdAndRetainsOriginalDigests() throws Exception {
        Path root = folder.newFolder().toPath();
        IslandRuleRepository store = repository(root);
        IslandRuleProjectionConsent consent = consent(root.resolve("consent"));
        byte[] original = rule("com.test.same", 0, TITLE);
        save(store, original, true, 0);
        save(store, original, true, 0);
        String first = entryId(store, 0), second = entryId(store, 1);
        IslandRuleProjection.Snapshot before = consent.preview(store).snapshot;
        store.setEnabled(store.revision(), first, false, null);
        IslandRuleProjection.Snapshot reduced = consent.preview(store).snapshot;
        assertTrue(reduced.retainsAllExcept(before, first));
        assertFalse(reduced.retainsAllExcept(before, second));

        consent.approve(store, consent.preview(store));
        try (IslandRuleRepository.Pending update = store.prepareUpdate(store.revision(), second,
                rule("com.test.same", 1000, TITLE), null)) {
            store.commit(update, false, null);
        }
        IslandRuleProjection.Snapshot edited = consent.preview(store).snapshot;
        assertArrayEquals(reduced.payload(), edited.payload());
        assertFalse(edited.retainsAllExcept(before, first));
        assertNull(consent.read(store));
        consent.approve(store, consent.preview(store));
        store.setEnabled(store.revision(), first, true, null);
        assertNull(consent.read(store));
    }

    @Test public void grantReadOrWriteFailureLeavesReductionCommittedAndGrantUnavailable() throws Exception {
        Path root = folder.newFolder().toPath();
        Path file = root.resolve("consent");
        IslandRuleRepository store = repository(root);
        save(store, rule("com.test.first", 0, TITLE), true, 0);
        save(store, rule("com.test.second", 0, TITLE), true, 0);
        save(store, rule("com.test.third", 0, CONTENT), true, 0);
        AtomicBoolean failSync = new AtomicBoolean();
        IslandRuleProjectionConsent consent = new IslandRuleProjectionConsent(file, directory -> {
            if (failSync.get()) throw new IOException("grant sync failed");
        });
        consent.approve(store, consent.preview(store));
        long baseline = store.revision();
        failSync.set(true);
        assertEquals(IslandRuleProjectionConsent.ReductionResult.SAVED_REAUTHORIZE,
                consent.reduce(store, baseline, entryId(store, 1), true));
        assertEquals(baseline + 1, store.revision());
        assertNull(consent.read(store));
        assertNull(consent(file).read(repository(root)));
        Files.write(file, new byte[] {1, 2, 3});
        assertEquals(IslandRuleProjectionConsent.ReductionResult.SAVED_REAUTHORIZE,
                consent(file).reduce(store, baseline + 1, entryId(store, 0), false));
        assertEquals(baseline + 2, store.revision());
    }

    private static String entryId(IslandRuleRepository store, int index) throws IOException {
        try (IslandRuleRepository.ReadView view = store.openRead()) { return view.entries.get(index).id; }
    }

    @Test public void mixedSelectionKeepsDisplayOrderAcrossFullBatchProjectionAndPortableMigration() throws Exception {
        String pkg = "com.test.first";
        String[] paths = {"/ticker", "/title", "/hintInfo/title", TITLE};
        byte[] original = rule(pkg, 0, paths);
        IslandRuleImportValidator.Environment environment = new IslandRuleImportValidator.Environment("test", 1L);
        IslandRuleRepository source = repository(folder.newFolder().toPath());
        save(source, original, true, 0);
        IslandRuleRepository migrated = repository(folder.newFolder().toPath());
        try (IslandRuleRepository.PendingBatch batch = migrated.prepareBatch(
                new ByteArrayInputStream(source.exportPack()), environment)) {
            migrated.commitBatch(batch, IslandRuleRepository.BatchConfirmation.REPLACE_ALL, environment);
        }
        for (IslandRuleRepository repository : Arrays.asList(source, migrated)) {
            try (IslandRuleRepository.ReadView view = repository.openRead()) {
                ByteArrayOutputStream full = new ByteArrayOutputStream();
                IslandRuleBatch.write(full, view);
                IslandRuleBatch received = IslandRuleBatch.read(new ByteArrayInputStream(full.toByteArray()), view.revision, environment);
                assertMixedExecution(pkg, received.sources);
            }
            IslandRuleProjectionConsent consent = consent(folder.newFolder().toPath().resolve("consent"));
            assertNull(consent.read(repository));
            consent.approve(repository, consent.preview(repository));
            byte[] projected = consent.read(repository);
            assertArrayEquals(wire(2, 1, paths), projected);
            IslandRuleProjection.Row decoded = IslandRuleProjection.decode(projected).get(0);
            assertEquals(Arrays.asList(paths), decoded.paths);
            byte[] rebuilt = rule(decoded.packageName, 1000, decoded.paths.toArray(new String[0]));
            assertMixedExecution(pkg, java.util.Collections.singletonList(new IslandRuleSetLoader.Source(
                    "projection-0", 1, true, () -> new ByteArrayInputStream(rebuilt))));
        }
    }

    private static void assertMixedExecution(String pkg, List<IslandRuleSetLoader.Source> sources) {
        IslandRuleSetLoader.Snapshot snapshot = new IslandRuleSetLoader().load(1, sources);
        assertTrue(snapshot.failures.isEmpty());
        assertEquals(1, snapshot.orderedActiveEntries.size());
        assertEquals("mixed", snapshot.orderedActiveEntries.get(0).rule.meta.templateType);
        IslandRuleExecutor.Result result = new IslandRuleExecutor(() -> 0).execute(snapshot.orderedActiveEntries,
                new IslandRuleContext(pkg, null, null, null), "{\"title\":\"raw\",\"param_v2\":{\"title\":\"inner\","
                        + "\"baseInfo\":{\"title\":\"base\"},\"ticker\":\"ticker\"}}");
        assertEquals(IslandRuleExecutor.Status.SUCCESS, result.status);
        assertEquals("ticker · raw · base", result.text);
    }

    @Test public void singleGroupProjectionKeepsOriginalWireBytes() throws Exception {
        IslandRuleRepository repository = repository(folder.newFolder().toPath());
        save(repository, rule("com.test.first", 0, CONTENT, TITLE), true, 0);
        try (IslandRuleRepository.ReadView view = repository.openRead()) {
            assertArrayEquals(wire(1, 1, CONTENT, TITLE), IslandRuleProjection.snapshot(view).payload());
        }
    }

    @Test public void decoderRejectsUnknownVersionsAndExtraUnregisteredOrOversizedFields() throws Exception {
        assertThrows(IOException.class, () -> IslandRuleProjection.decode(wire(3, 1, TITLE)));
        assertThrows(IOException.class, () -> IslandRuleProjection.decode(wire(2, 1, TITLE, TITLE)));
        assertThrows(IOException.class, () -> IslandRuleProjection.decode(wire(2, 1, TITLE, "/baseInfo/unknown")));
        assertThrows(IOException.class, () -> IslandRuleProjection.decode(wire(1, 51, TITLE)));
        assertThrows(IOException.class, () -> IslandRuleProjection.decode(wire(1, 1, "/unexpected")));
        assertThrows(IOException.class, () -> IslandRuleProjection.decode(wire(1, 1, TITLE, TITLE)));
        assertThrows(IOException.class, () -> IslandRuleProjection.decode(wire(1, 1, TITLE, "/hintInfo/title")));
        assertThrows(IOException.class, () -> IslandRuleProjection.decode(new byte[129 * 1024]));
        byte[] valid = wire(1, 1, TITLE);
        assertThrows(IOException.class, () -> IslandRuleProjection.decode(Arrays.copyOf(valid, valid.length + 1)));
        assertThrows(IOException.class, () -> IslandRuleProjection.decode(Arrays.copyOf(valid, valid.length - 1)));
        assertEquals(TITLE, IslandRuleProjection.decode(valid).get(0).paths.get(0));
    }

    private static byte[] wire(int version, int rows, String... paths) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x4652504a);
            out.writeByte(version);
            out.writeByte(rows);
            out.writeUTF("com.test.first");
            out.writeByte(paths.length);
            for (String path : paths) out.writeUTF(path);
        }
        return bytes.toByteArray();
    }

    private static IslandRuleRepository repository(Path root) throws IOException {
        return new IslandRuleRepository(root.resolve("rules"), ignored -> { });
    }

    private static IslandRuleProjectionConsent consent(Path file) {
        return new IslandRuleProjectionConsent(file, ignored -> { });
    }

    private static byte[] rule(String pkg, long timestamp, String... paths) {
        java.util.ArrayList<IslandRulePaths.Entry> registered = new java.util.ArrayList<>();
        for (String path : paths) registered.add(IslandRulePaths.find(path));
        return IslandCaptureEditor.candidate(pkg, registered, timestamp).bytes();
    }

    private static byte[] replace(byte[] source, String before, String after) {
        return new String(source, StandardCharsets.UTF_8).replace(before, after).getBytes(StandardCharsets.UTF_8);
    }

    private static void save(IslandRuleRepository repository, byte[] bytes, boolean enabled, int priority)
            throws IOException {
        try (IslandRuleRepository.Pending pending = repository.prepare(
                new ByteArrayInputStream(bytes), null, enabled, priority, null)) {
            repository.commit(pending, false, null);
        }
    }
}
