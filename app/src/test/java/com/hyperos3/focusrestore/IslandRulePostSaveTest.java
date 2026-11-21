/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static com.hyperos3.focusrestore.IslandRuleImportUi.Next.*;
import static com.hyperos3.focusrestore.IslandRuleImportUi.afterSave;
import static org.junit.Assert.*;

public class IslandRulePostSaveTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private IslandRuleRepository store;
    private IslandRuleProjectionConsent consent;

    @Before public void openStore() throws Exception {
        Path root = folder.newFolder().toPath();
        store = new IslandRuleRepository(root.resolve("rules"), directory -> { });
        consent = new IslandRuleProjectionConsent(root.resolve("consent"), directory -> { });
    }

    private long save(String pkg) throws Exception {
        byte[] bytes = IslandCaptureEditor.candidate(pkg,
                Collections.singletonList(IslandRulePaths.find("/baseInfo/title")), 0).bytes();
        try (IslandRuleRepository.Pending pending = store.prepare(
                new ByteArrayInputStream(bytes), null, true, 0, null)) {
            return store.commit(pending, false, null);
        }
    }

    @Test public void bothSaveRoutesOfferOneReviewAndWaitForExplicitConsent() throws Exception {
        long saved = save("com.test.first");
        IslandRuleProjectionConsent.Preview preview = consent.preview(store);
        assertEquals(REVIEW, afterSave(saved, saved, true, preview, false, false));
        assertEquals(REVIEW, afterSave(saved, saved, true, preview, false, true));
        assertNull(consent.read(store));
        consent.approve(store, preview);
        IslandRuleProjectionConsent.Preview approved = consent.preview(store);
        assertEquals(NONE, afterSave(saved, saved, true, approved, false, false));
        assertEquals(LIST, afterSave(saved, saved, true, approved, false, true));
        long next = save("com.test.next");
        assertNull(consent.read(store));
        assertEquals(REVIEW, afterSave(next, next, true, consent.preview(store), false, true));
    }

    @Test public void importedRevisionOffersReviewAndPublishesRulesAfterExplicitConsent() throws Exception {
        long previous = save("com.test.first");
        consent.approve(store, consent.preview(store));
        byte[] incoming = IslandCaptureEditor.candidate("com.test.imported",
                Collections.singletonList(IslandRulePaths.find("/baseInfo/title")), 0).bytes();
        byte[] pack = IslandRulePack.encode(Collections.singletonList(
                new IslandRulePack.Entry(UUID.randomUUID(), true, 0, incoming)), false);
        IslandRuleRepository.BatchResult result;
        try (IslandRuleRepository.PendingBatch pending = store.prepareBatch(new ByteArrayInputStream(pack), null)) {
            result = store.commitBatch(pending, Collections.emptySet(), null);
        }
        long revision = result.confirmableRevision();
        assertEquals(previous + 1, revision);
        assertNull(consent.read(store));
        IslandRuleProjectionConsent.Preview preview = consent.preview(store);
        assertEquals(REVIEW, afterSave(revision, store.revision(), true, preview, false, false));
        assertEquals(NONE, afterSave(revision, store.revision(), true, preview, true, false));
        assertEquals(NONE, afterSave(revision, store.revision(), false, preview, false, false));

        try (IslandRuleRepository.PendingBatch pending = store.prepareBatch(new ByteArrayInputStream(pack), null)) {
            IslandRuleRepository.BatchResult skipped = store.commitBatch(pending, Collections.emptySet(), null);
            assertEquals(0, skipped.confirmableRevision());
            assertEquals(NONE, afterSave(skipped.confirmableRevision(), revision, true, preview, false, false));
        }
        consent.approve(store, preview);
        List<IslandRuleProjection.Row> rows = IslandRuleProjection.decode(consent.read(store));
        assertEquals(2, rows.size());
        assertEquals("com.test.imported", rows.get(1).packageName);
        assertEquals(Collections.singletonList("/baseInfo/title"), rows.get(1).paths);
    }

    @Test public void strictReaderAndEmptyProjectionDoNotNeedReview() throws Exception {
        long saved = save("com.test.first");
        assertEquals(NONE, afterSave(saved, saved, true, null, false, false));
        assertEquals(LIST, afterSave(saved, saved, true, consent.preview(store), true, true));
        String id;
        try (IslandRuleRepository.ReadView view = store.openRead()) { id = view.entries.get(0).id; }
        long empty = store.delete(saved, id);
        assertEquals(LIST, afterSave(empty, empty, true, consent.preview(store), false, true));
    }

    @Test public void staleOrUncertainSaveNeverTakesOverANewerPage() throws Exception {
        long saved = save("com.test.first");
        IslandRuleProjectionConsent.Preview prior = consent.preview(store);
        assertEquals(NONE, afterSave(saved, saved, false, prior, false, true));
        assertEquals(NONE, afterSave(0, saved, true, prior, false, true));
        long next = save("com.test.next");
        assertEquals(NONE, afterSave(saved, next, true, consent.preview(store), false, true));
        assertEquals(NONE, afterSave(next, next, true, prior, false, false));
        assertNull(consent.read(store));
    }
}
