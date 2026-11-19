/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class IslandCaptureEditorTest {
    private static final String PACKAGE = "example.island";
    private static final long CREATED_AT = 1_800_000_000_000L;
    private static final IslandRulePaths.Entry TITLE = IslandRulePaths.find("/baseInfo/title");
    private static final IslandRulePaths.Entry CONTENT = IslandRulePaths.find("/baseInfo/content");
    private static final IslandRulePaths.Entry LEGACY_TITLE = IslandRulePaths.find("/title");
    private static final String SAMPLE = "{\"baseInfo\":{\"title\":\" first\\ncard \",\"content\":\"second card\"},"
            + "\"title\":\"legacy card\",\"private\":\"never selected\"}";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void reorderingChangesPreviewAndRuleWithoutPersistingSamples() throws Exception {
        Fixture f = new Fixture();
        IslandCaptureEditor editor = f.editor();
        assertTrue(editor.toggle(TITLE));
        assertTrue(editor.toggle(CONTENT));
        assertEquals("first card · second card", editor.preview());
        IslandCaptureEditor.Candidate initial = editor.candidate(PACKAGE, CREATED_AT);
        assertNotNull(initial);
        assertTrue(editor.move(1, 0));
        assertEquals("second card · first card", editor.preview());
        IslandCaptureEditor.Candidate ordered = editor.candidate(PACKAGE, CREATED_AT);
        assertNotNull(ordered);
        assertNotEquals(initial.digest, ordered.digest);
        assertEquals(Arrays.asList(CONTENT, TITLE), ordered.paths);
        byte[] bytes = ordered.bytes();
        IslandRuleImportValidator.Result check = new IslandRuleImportValidator().validate(bytes, null);
        assertTrue(check.canApprove(false));
        IslandRule rule = check.rule;
        assertEquals("baseInfo", rule.meta.templateType);
        assertEquals(1, rule.match.size());
        assertEquals("packageName", rule.match.get(0).field);
        assertEquals("equals", rule.match.get(0).op);
        assertEquals(PACKAGE, rule.match.get(0).value);
        assertEquals(CONTENT.path, rule.extract.get(0).path);
        assertEquals(TITLE.path, rule.extract.get(1).path);
        assertEquals(rule.extract.get(0).id, rule.display.order.get(0).ref);
        assertEquals(rule.extract.get(1).id, rule.display.order.get(1).ref);
        String candidateText = new String(bytes, StandardCharsets.UTF_8);
        for (String raw : Arrays.asList("first", "second card", "legacy card", "never selected", "sample-key")) {
            assertFalse(candidateText.contains(raw));
        }
        bytes[0] = ' ';
        assertEquals(ordered.digest, IslandRuleRepository.digest(ordered.bytes()));
    }

    @Test public void mixedGroupsRetainOrderAndRejectDuplicatesOverflowAndStaleSelection() throws Exception {
        IslandCaptureEditor editor = new Fixture().editor();
        assertTrue(editor.toggle(TITLE));
        assertTrue(editor.toggle(LEGACY_TITLE));
        assertTrue(editor.move(1, 0));
        assertEquals("legacy card · first card", editor.preview());
        IslandCaptureEditor.Candidate mixed = editor.candidate(PACKAGE, CREATED_AT);
        assertNotNull(mixed);
        assertEquals(Arrays.asList(LEGACY_TITLE, TITLE), mixed.paths);
        assertEquals(IslandRulePaths.MIXED_TEMPLATE, new IslandRuleCodec().decode(mixed.bytes()).rule.meta.templateType);
        assertFalse(new String(mixed.bytes(), StandardCharsets.UTF_8).contains("legacy card"));
        assertNull(IslandCaptureEditor.candidate(PACKAGE, Arrays.asList(TITLE, TITLE), CREATED_AT));
        assertTrue(editor.toggle(TITLE));
        assertTrue(editor.toggle(LEGACY_TITLE));
        editor.clear();
        assertTrue(editor.fields().isEmpty());
        assertTrue(editor.order().isEmpty());
        assertEquals("", editor.preview());
        assertNull(editor.candidate(PACKAGE, CREATED_AT));
        assertFalse(editor.toggle(LEGACY_TITLE));

        List<IslandCapturePreview.Field> available = new ArrayList<>();
        for (IslandRulePaths.Entry path : IslandRulePaths.all()) {
            if ("param_island".equals(path.templateGroup) && IslandRulePaths.isRuntimeAllowed(path)) {
                available.add(IslandCapturePreview.Field.create(path, IslandCapturePreview.Type.STRING, "demo", null));
            }
        }
        // 任选同组达到上限时，剩余路径仍可浏览但无法再选。
        assertTrue(available.size() > IslandCaptureEditor.MAX_SELECTION);
        for (int offset = 0; offset < available.size();) {
            int next = Math.min(offset + IslandCapturePreview.MAX_PAGE_FIELDS, available.size());
            assertTrue(editor.append(new IslandCapturePreview.Page(next == available.size() ? -1 : next,
                    available.subList(offset, next))));
            offset = next;
        }
        for (int i = 0; i < IslandCaptureEditor.MAX_SELECTION; i++) assertTrue(editor.toggle(available.get(i).path));
        assertFalse(editor.toggle(available.get(IslandCaptureEditor.MAX_SELECTION).path));
        assertNotNull(editor.candidate(PACKAGE, CREATED_AT));
    }

    @Test public void savePrimitiveRejectsWrongDigestBaselineAndReplay() throws Exception {
        Fixture f = new Fixture();
        IslandCaptureEditor.Candidate candidate = f.candidate();
        IslandCaptureEditor.Candidate reordered = IslandCaptureEditor.candidate(PACKAGE, Arrays.asList(CONTENT, TITLE), CREATED_AT);
        assertNotNull(reordered);
        assertEquals(0, f.runtime.issueSave(new Object(), f.generation, f.handle, f.preview, 3,
                candidate.digest, CREATED_AT, candidate.paths));
        long digestBound = f.issue(candidate, 3);
        assertFalse(f.consume(digestBound, 3, reordered));
        assertFalse(f.consume(digestBound, 3, candidate));
        long baselineBound = f.issue(candidate, 3);
        assertFalse(f.consume(baselineBound, 4, candidate));
        assertFalse(f.consume(baselineBound, 3, candidate));
        long authorized = f.issue(candidate, 3);
        assertFalse(f.runtime.consumeSave(f.connection, f.generation, f.handle + 1, authorized, 3, candidate.digest));
        assertTrue(f.consume(authorized, 3, candidate));
        assertFalse(f.consume(authorized, 3, candidate));
    }

    @Test public void revocationBeforeConsumptionAndExpiryDenySave() throws Exception {
        Fixture f = new Fixture();
        IslandCaptureEditor.Candidate candidate = f.candidate();
        long authorization = f.issue(candidate, 0);
        f.runtime.revokePreviewAccess();
        assertFalse(f.consume(authorization, 0, candidate));
        assertNull(f.runtime.previewPage(f.connection, f.generation, f.handle, f.preview, 0));

        Fixture expires = new Fixture();
        long expired = expires.issue(candidate, 0);
        expires.clock.set(60_000);
        assertFalse(expires.consume(expired, 0, candidate));
        assertFalse(expires.consume(expired, 0, candidate));
    }

    @Test public void snippetsExposeBoundedRegisteredValuesWithoutReplacingPreview() throws Exception {
        Fixture f = new Fixture();
        assertEquals(Arrays.asList("first card · second card"),
                f.runtime.visibleSnippets(f.connection, f.generation, new long[] { f.handle }));
        assertNull(f.runtime.visibleSnippets(f.connection, f.generation, new long[] { f.handle + 1 }));
        assertNull(f.runtime.visibleSnippets(new Object(), f.generation, new long[] { f.handle }));
        assertNull(f.runtime.visibleSnippets(f.connection, f.generation, new long[IslandCaptureEditor.MAX_VISIBLE_BATCH + 1]));
        assertNotNull(f.runtime.previewPage(f.connection, f.generation, f.handle, f.preview, 0));
        f.runtime.revokePreviewAccess();
        assertNull(f.runtime.visibleSnippets(f.connection, f.generation, new long[] { f.handle }));
    }

    @Test public void snippetLimitPreservesUnicodeAndRemovesControlCharacters() {
        String almostFull = String.join("", java.util.Collections.nCopies(95, "字"));
        IslandCapturePreview.Field longTitle = IslandCapturePreview.Field.create(TITLE,
                IslandCapturePreview.Type.STRING, almostFull + "🚆尾部", null);
        assertEquals(almostFull, IslandCaptureEditor.snippet(Arrays.asList(longTitle)));
        IslandCapturePreview.Field controls = IslandCapturePreview.Field.create(CONTENT,
                IslandCapturePreview.Type.STRING, "首行\n\u202e次行", null);
        assertEquals("首行 次行", IslandCaptureEditor.snippet(Arrays.asList(controls)));
    }

    @Test public void revokedEditorClearsRawFieldsAndDropsLatePages() throws Exception {
        Fixture f = new Fixture();
        IslandCapturePreview.Page page = f.runtime.previewPage(f.connection, f.generation, f.handle, f.preview, 0);
        IslandCaptureEditor.Lifetime life = new IslandCaptureEditor.Lifetime(100);
        assertTrue(life.acceptPage(99, page));
        assertTrue(life.editor.toggle(TITLE));
        assertEquals("first card", life.editor.preview());
        assertFalse(new IslandCaptureEditor.Lifetime(100).acceptPage(100, page));
        life.revoke();
        assertFalse(life.acceptPage(99, page));
        assertTrue(life.editor.fields().isEmpty());
        assertEquals("", life.editor.preview());
        assertNull(life.editor.candidate(PACKAGE, CREATED_AT));
    }

    @Test public void atomicCommitConsumesExactlyOnceAndChecksDigest() throws Exception {
        Fixture f = new Fixture();
        IslandCaptureEditor.Candidate candidate = f.candidate();
        IslandCaptureEditor.Candidate changed = IslandCaptureEditor.candidate(PACKAGE, Arrays.asList(CONTENT, TITLE), CREATED_AT);
        IslandRuleRepository repository = new IslandRuleRepository(temporary.newFolder().toPath(), directory -> { });
        try (IslandRuleRepository.Pending wrong = prepare(repository, changed);
                IslandRuleRepository.Pending right = prepare(repository, candidate)) {
            long wrongToken = f.issue(candidate, wrong.baselineRevision);
            assertThrows(java.io.IOException.class, () -> repository.commitCapture(wrong, null, () -> {
                if (!f.consume(wrongToken, wrong.baselineRevision, changed)) throw new java.io.IOException("SAVE_DENIED");
            }));
            assertEquals(0, repository.revision());
            assertFalse(f.consume(wrongToken, wrong.baselineRevision, candidate));
            long token = f.issue(candidate, right.baselineRevision);
            assertEquals(1, repository.commitCapture(right, null, () -> {
                if (!f.consume(token, right.baselineRevision, candidate)) throw new java.io.IOException("SAVE_DENIED");
            }));
            assertFalse(f.consume(token, right.baselineRevision, candidate));
            assertEquals(1, repository.revision());
        }
    }

    @Test public void observedRevocationLeavesCommittedManifestUntouched() throws Exception {
        Fixture f = new Fixture();
        IslandCaptureEditor.Candidate candidate = f.candidate();
        IslandRuleRepository repository = new IslandRuleRepository(temporary.newFolder().toPath(), directory -> { });
        try (IslandRuleRepository.Pending initial = prepare(repository, candidate)) {
            assertEquals(1, repository.commit(initial, false, null));
        }
        try (IslandRuleRepository.Pending pending = prepare(repository, candidate)) {
            long token = f.issue(candidate, pending.baselineRevision);
            f.eligible.set(false);
            assertThrows(java.io.IOException.class, () -> repository.commitCapture(pending, null, () -> {
                if (!f.consume(token, pending.baselineRevision, candidate)) throw new java.io.IOException("SAVE_DENIED");
            }));
            assertEquals(1, repository.revision());
        }
    }

    @Test public void localRevocationAfterConsumptionPreventsPublication() throws Exception {
        Fixture f = new Fixture();
        IslandCaptureEditor.Candidate candidate = f.candidate();
        IslandCaptureEditor.Lifetime life = new IslandCaptureEditor.Lifetime(100);
        IslandRuleRepository repository = new IslandRuleRepository(temporary.newFolder().toPath(), directory -> { });
        try (IslandRuleRepository.Pending pending = prepare(repository, candidate)) {
            long token = f.issue(candidate, pending.baselineRevision);
            assertThrows(java.io.IOException.class, () -> repository.commitCapture(pending, null,
                    new IslandRuleRepository.CommitAuthorization() {
                        @Override public void consume() {
                            assertTrue(f.consume(token, pending.baselineRevision, candidate));
                            life.revoke();
                        }

                        @Override public void publish(IslandRuleRepository.ManifestPublication publication)
                                throws java.io.IOException {
                            life.publish(() -> 99, publication);
                        }
                    }));
            assertEquals(0, repository.revision());
            assertFalse(f.consume(token, pending.baselineRevision, candidate));
        }
    }

    @Test public void repositoryIndependentlyRechecksRevisionAtAtomicCommit() throws Exception {
        Fixture f = new Fixture();
        IslandCaptureEditor.Candidate candidate = f.candidate();
        IslandRuleRepository repository = new IslandRuleRepository(temporary.newFolder().toPath(), directory -> { });
        try (IslandRuleRepository.Pending pending = prepare(repository, candidate);
                IslandRuleRepository.Pending other = prepare(repository, candidate)) {
            assertEquals(candidate.digest, pending.entry.sha256);
            long authorization = f.issue(candidate, pending.baselineRevision);
            assertEquals(1, repository.commit(other, false, null));
            assertEquals(IslandRuleRepository.Code.BASELINE_CHANGED,
                    assertThrows(IslandRuleRepository.Failure.class, () -> repository.commitCapture(pending, null,
                            () -> fail("仓库基线变化后不应消费确认票据"))).code);
            assertTrue(f.consume(authorization, pending.baselineRevision, candidate));
        }
    }

    @Test public void appApprovalPublishesOnlyTheConfirmedRuleOnceWithoutSystemUiSave() throws Exception {
        Fixture f = new Fixture();
        IslandCaptureEditor.Candidate candidate = f.candidate();
        IslandRuleRepository repository = new IslandRuleRepository(temporary.newFolder().toPath(), directory -> { });
        Object ticket = new Object();
        IslandCaptureEditor.LocalApproval approval = new IslandCaptureEditor.LocalApproval(ticket, repository.revision(), candidate);
        try (IslandRuleRepository.Pending pending = prepare(repository, candidate)) {
            assertEquals(1, repository.commitCapture(pending, null, () -> approval.consume(ticket, pending, f::checkPreview)));
            assertEquals("CAPTURE_SAVE_APPROVAL_USED", assertThrows(java.io.IOException.class,
                    () -> approval.consume(ticket, pending, () -> fail("已经使用的批准不得再检查预览"))).getMessage());
        }
        try (IslandRuleRepository.ReadView saved = repository.openRead()) {
            assertEquals(1, saved.revision);
            assertEquals(1, saved.entries.size());
            assertFalse(saved.entries.get(0).forced);
            assertArrayEquals(candidate.bytes(), saved.read(saved.entries.get(0)));
        }
    }

    @Test public void appApprovalRejectsDifferentTicketDigestOrBaselineAndCannotBeReplayed() throws Exception {
        Fixture f = new Fixture();
        IslandCaptureEditor.Candidate candidate = f.candidate();
        IslandCaptureEditor.Candidate reordered = IslandCaptureEditor.candidate(PACKAGE, Arrays.asList(CONTENT, TITLE), CREATED_AT);
        IslandRuleRepository repository = new IslandRuleRepository(temporary.newFolder().toPath(), directory -> { });
        Object ticket = new Object();
        try (IslandRuleRepository.Pending right = prepare(repository, candidate);
                IslandRuleRepository.Pending wrong = prepare(repository, reordered)) {
            assertAppApprovalMismatch(new IslandCaptureEditor.LocalApproval(ticket, 0, candidate), new Object(), right);
            assertAppApprovalMismatch(new IslandCaptureEditor.LocalApproval(ticket, 0, candidate), ticket, wrong);
            assertAppApprovalMismatch(new IslandCaptureEditor.LocalApproval(ticket, 1, candidate), ticket, right);
            assertEquals(0, repository.revision());
        }
    }

    @Test public void appApprovalRejectsLockEndConnectionChangeAndInvalidPreview() throws Exception {
        assertAppApprovalDenied(f -> f.eligible.set(false));
        assertAppApprovalDenied(f -> assertTrue(f.runtime.end(f.generation)));
        assertAppApprovalDenied(f -> f.activeConnection.set(new Object()));
        assertAppApprovalDenied(f -> assertTrue(f.runtime.closePreview(f.connection, f.generation, f.handle, f.preview)));
        assertAppApprovalDenied(f -> f.clock.set(60_000));
    }

    @Test public void appApprovalCannotPublishAfterLocalPreviewRevocation() throws Exception {
        Fixture f = new Fixture();
        IslandCaptureEditor.Candidate candidate = f.candidate();
        IslandCaptureEditor.Lifetime life = new IslandCaptureEditor.Lifetime(100);
        IslandRuleRepository repository = new IslandRuleRepository(temporary.newFolder().toPath(), directory -> { });
        Object ticket = new Object();
        IslandCaptureEditor.LocalApproval approval = new IslandCaptureEditor.LocalApproval(ticket, 0, candidate);
        try (IslandRuleRepository.Pending pending = prepare(repository, candidate)) {
            assertEquals("CAPTURE_SAVE_REVOKED", assertThrows(java.io.IOException.class,
                    () -> repository.commitCapture(pending, null, new IslandRuleRepository.CommitAuthorization() {
                        @Override public void consume() throws java.io.IOException {
                            approval.consume(ticket, pending, f::checkPreview);
                            life.revoke();
                        }

                        @Override public void publish(IslandRuleRepository.ManifestPublication publication)
                                throws java.io.IOException {
                            life.publish(() -> 99, publication);
                        }
                    })).getMessage());
            assertEquals(0, repository.revision());
        }
    }

    @Test public void appApprovalCannotReplaceAnInterveningRepositoryRevision() throws Exception {
        Fixture f = new Fixture();
        IslandCaptureEditor.Candidate candidate = f.candidate();
        IslandCaptureEditor.Candidate other = IslandCaptureEditor.candidate(PACKAGE, Arrays.asList(CONTENT), CREATED_AT);
        IslandRuleRepository repository = new IslandRuleRepository(temporary.newFolder().toPath(), directory -> { });
        Object ticket = new Object();
        IslandCaptureEditor.LocalApproval approval = new IslandCaptureEditor.LocalApproval(ticket, 0, candidate);
        try (IslandRuleRepository.Pending stale = prepare(repository, candidate);
                IslandRuleRepository.Pending newer = prepare(repository, other)) {
            assertEquals(1, repository.commit(newer, false, null));
            assertEquals(IslandRuleRepository.Code.BASELINE_CHANGED,
                    assertThrows(IslandRuleRepository.Failure.class, () -> repository.commitCapture(stale, null,
                            () -> approval.consume(ticket, stale, () -> fail("仓库基线变化后不应消费批准")))).code);
            try (IslandRuleRepository.ReadView saved = repository.openRead()) {
                assertEquals(1, saved.revision);
                assertEquals(1, saved.entries.size());
                assertArrayEquals(other.bytes(), saved.read(saved.entries.get(0)));
            }
        }
    }

    private static void assertAppApprovalMismatch(IslandCaptureEditor.LocalApproval approval, Object ticket,
            IslandRuleRepository.Pending pending) {
        assertEquals("CAPTURE_SAVE_APPROVAL_MISMATCH", assertThrows(java.io.IOException.class,
                () -> approval.consume(ticket, pending, () -> fail("绑定不符不得检查预览"))).getMessage());
        assertEquals("CAPTURE_SAVE_APPROVAL_USED", assertThrows(java.io.IOException.class,
                () -> approval.consume(ticket, pending, () -> fail("拒绝后不得重放批准"))).getMessage());
    }

    private void assertAppApprovalDenied(java.util.function.Consumer<Fixture> invalidate) throws Exception {
        Fixture f = new Fixture();
        IslandCaptureEditor.Candidate candidate = f.candidate();
        IslandRuleRepository repository = new IslandRuleRepository(temporary.newFolder().toPath(), directory -> { });
        Object ticket = new Object();
        IslandCaptureEditor.LocalApproval approval = new IslandCaptureEditor.LocalApproval(ticket, 0, candidate);
        try (IslandRuleRepository.Pending pending = prepare(repository, candidate)) {
            f.checkPreview();
            invalidate.accept(f);
            assertEquals("CAPTURE_PREVIEW_REVOKED", assertThrows(java.io.IOException.class,
                    () -> repository.commitCapture(pending, null, () -> approval.consume(ticket, pending, f::checkPreview))).getMessage());
            assertEquals(0, repository.revision());
        }
    }

    private static IslandRuleRepository.Pending prepare(IslandRuleRepository repository, IslandCaptureEditor.Candidate candidate)
            throws Exception {
        return repository.prepare(new ByteArrayInputStream(candidate.bytes()), null, true, 0, null);
    }

    private static final class Fixture {
        final Object connection = new Object();
        final AtomicBoolean eligible = new AtomicBoolean(true);
        final AtomicLong clock = new AtomicLong();
        final AtomicReference<Object> activeConnection = new AtomicReference<>(connection);
        final IslandCaptureRuntime runtime = new IslandCaptureRuntime(eligible::get,
                () -> new IslandCaptureRuntime.PreviewAccess(activeConnection.get(), 0), clock::get,
                name -> PACKAGE.equals(name) ? 10045 : -1);
        final long generation;
        final long handle;
        final long preview;

        Fixture() throws Exception {
            IslandCaptureRuntime.Grant grant = runtime.start(runtime.status().epoch, 0);
            assertNotNull(grant);
            generation = grant.generation;
            IslandCaptureInputAdapter.Input input = IslandCaptureInputAdapter.readNotification(
                    key -> FocusParamKeys.PRIMARY.equals(key) ? SAMPLE : null, "android.text", value -> false);
            assertTrue(runtime.offer(grant, PACKAGE, 0, 10045, "sample-key", () -> input));
            assertTrue(runtime.awaitIdle(5, TimeUnit.SECONDS));
            handle = runtime.previewList(connection, generation).get(0).handle;
            preview = runtime.openPreview(connection, generation, handle);
            assertTrue(preview < 0);
        }

        IslandCaptureEditor editor() {
            IslandCaptureEditor editor = new IslandCaptureEditor();
            IslandCapturePreview.Page page = runtime.previewPage(connection, generation, handle, preview, 0);
            assertNotNull(page);
            assertTrue(editor.append(page));
            return editor;
        }

        IslandCaptureEditor.Candidate candidate() {
            IslandCaptureEditor editor = editor();
            assertTrue(editor.toggle(TITLE));
            assertTrue(editor.toggle(CONTENT));
            IslandCaptureEditor.Candidate candidate = editor.candidate(PACKAGE, CREATED_AT);
            assertNotNull(candidate);
            return candidate;
        }

        void checkPreview() throws java.io.IOException {
            if (!runtime.previewUsable(connection, generation, handle, preview)) {
                throw new java.io.IOException("CAPTURE_PREVIEW_REVOKED");
            }
        }

        long issue(IslandCaptureEditor.Candidate candidate, long baseline) {
            long id = runtime.issueSave(connection, generation, handle, preview,
                    baseline, candidate.digest, CREATED_AT, candidate.paths);
            assertTrue(id > 0);
            return id;
        }

        boolean consume(long id, long baseline, IslandCaptureEditor.Candidate candidate) {
            return runtime.consumeSave(connection, generation, handle, id, baseline, candidate.digest);
        }
    }
}
