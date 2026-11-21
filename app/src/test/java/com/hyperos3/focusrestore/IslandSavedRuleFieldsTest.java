/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class IslandSavedRuleFieldsTest {
    private static final IslandRulePaths.Entry TITLE = IslandRulePaths.find("/baseInfo/title");
    private static final IslandRulePaths.Entry CONTENT = IslandRulePaths.find("/baseInfo/content");
    private static final IslandRulePaths.Entry ISLAND = IslandRulePaths.find("/param_island/content");
    private static final List<IslandRulePaths.Entry> INITIAL = Arrays.asList(TITLE, CONTENT, ISLAND);

    @Test public void reorderKeepsBindingIdsMetadataAndTypeAndRemainsProjectable() throws Exception {
        IslandNotificationType type = IslandNotificationType.create("1", "sample", "arrival",
                Arrays.asList(TITLE.path, CONTENT.path, ISLAND.path));
        byte[] original = IslandCaptureEditor.candidate("com.test.alpha", INITIAL, 43L, type).bytes();
        IslandRule before = parse(original);
        IslandSavedRuleFields editor = new IslandSavedRuleFields(original, IslandRuleProjection.captureRow(original, before));
        byte[] reordered = editor.update(Arrays.asList(ISLAND, TITLE, CONTENT));
        assertNull(IslandSavedRuleFields.validate(reordered));
        IslandRule after = parse(reordered);
        assertEquals("field3", after.extract.get(0).id);
        assertEquals("field1", after.extract.get(1).id);
        assertEquals("field2", after.extract.get(2).id);
        assertEquals("field3", after.display.order.get(0).ref);
        assertEquals(before.meta.createdAt, after.meta.createdAt);
        assertEquals(before.meta.ruleAuthor, after.meta.ruleAuthor);
        assertEquals(before.notificationType, after.notificationType);
        assertEquals(Arrays.asList(ISLAND.path, TITLE.path, CONTENT.path),
                IslandRuleProjection.captureRow(reordered, after).paths);
        assertNotNull(IslandPortableRule.rebuild(reordered, after));
    }

    @Test public void removeAndAddFieldKeepsRetainedBindingAndRecomputesTemplate() throws Exception {
        byte[] original = IslandCaptureEditor.candidate("com.test.alpha", Arrays.asList(TITLE, CONTENT), 43L).bytes();
        IslandRule before = parse(original);
        IslandSavedRuleFields editor = new IslandSavedRuleFields(original, IslandRuleProjection.captureRow(original, before));
        byte[] edited = editor.update(Arrays.asList(CONTENT, ISLAND));
        IslandRule rule = parse(edited);
        assertEquals("mixed", rule.meta.templateType);
        assertEquals("field2", rule.extract.get(0).id);
        assertEquals("field1", rule.extract.get(1).id);
        assertEquals(CONTENT.path, rule.extract.get(0).path);
        assertEquals(ISLAND.path, rule.extract.get(1).path);
        assertNotNull(IslandRuleProjection.captureRow(edited, rule));
        assertEquals("baseInfo", parse(editor.update(Collections.singletonList(CONTENT))).meta.templateType);
        assertNull(editor.update(Arrays.asList(CONTENT, CONTENT)));
        assertNotNull(IslandSavedRuleFields.validate(editor.update(Collections.emptyList())));
    }

    @Test public void projectionStillRejectsRepeatedReferencesTransformAndExtraMatch() throws Exception {
        byte[] original = IslandCaptureEditor.candidate("com.test.alpha", Arrays.asList(TITLE, CONTENT), 43L).bytes();
        String json = new String(original, StandardCharsets.UTF_8);
        String[] excluded = {
                json.replace("\"ref\":\"field2\"", "\"ref\":\"field1\""),
                json.replace("\"ref\":\"field1\"", "\"ref\":\"field2\"").replace("\"ref\":\"field2\"}]", "\"ref\":\"field1\"}]"),
                json.replace("\"id\":\"field1\"", "\"id\":\"field1\",\"replace\":[{\"from\":\"a\",\"to\":\"b\"}]"),
                json.replace("]},\"extract\"", ",{\"field\":\"business\",\"op\":\"equals\",\"value\":\"train\"}]},\"extract\"")
        };
        for (String rejected : excluded) {
            assertNotEquals(json, rejected);
            byte[] bytes = rejected.getBytes(StandardCharsets.UTF_8);
            IslandRule rule = new IslandRuleCodec().decode(bytes).rule;
            if (rule != null) assertNull(IslandRuleProjection.captureRow(bytes, rule));
        }
    }

    @Test public void separatedChecksGroupMoveAndDeleteUndoPreserveOrder() {
        IslandRuleFieldDraft draft = new IslandRuleFieldDraft(INITIAL, INITIAL);
        draft.check(TITLE, true);
        draft.check(ISLAND, true);
        assertEquals(INITIAL, draft.order());
        assertFalse(draft.dirty());
        assertFalse(draft.move(Arrays.asList(TITLE, ISLAND), 2));
        assertEquals(INITIAL, draft.order());
        assertTrue(draft.move(Arrays.asList(ISLAND, TITLE), 1));
        assertEquals(Arrays.asList(CONTENT, TITLE, ISLAND), draft.order());
        assertTrue(draft.undo());
        assertEquals(INITIAL, draft.order());
        assertTrue(draft.move(Arrays.asList(TITLE, ISLAND), 0));
        assertEquals(Arrays.asList(TITLE, ISLAND, CONTENT), draft.order());
        assertTrue(draft.deleteChecked());
        assertEquals(Collections.singletonList(CONTENT), draft.order());
        assertTrue(draft.checked().isEmpty());
        assertTrue(draft.undo());
        assertEquals(Arrays.asList(TITLE, ISLAND, CONTENT), draft.order());
        assertTrue(draft.restore());
        assertFalse(draft.dirty());
        assertTrue(draft.undo());
        assertEquals(Arrays.asList(TITLE, ISLAND, CONTENT), draft.order());
    }

    @Test public void selectAllHonorsLimitKeepsExistingOrderAndUndoesTogether() {
        List<IslandRulePaths.Entry> candidates = IslandRulePaths.all();
        List<IslandRulePaths.Entry> initial = Collections.singletonList(ISLAND);
        IslandRuleFieldDraft draft = new IslandRuleFieldDraft(initial, candidates);
        draft.check(ISLAND, true);
        assertTrue(draft.selectAll());
        assertEquals(IslandCaptureEditor.MAX_SELECTION, draft.order().size());
        assertSame(ISLAND, draft.order().get(0));
        assertEquals(candidates.subList(0, IslandCaptureEditor.MAX_SELECTION - 1),
                draft.order().subList(1, draft.order().size()));
        assertFalse(draft.selectAll());
        assertTrue(draft.undo());
        assertEquals(initial, draft.order());
        assertEquals(Collections.singleton(ISLAND), draft.checked());
    }

    @Test public void invertAndClearKeepCandidateOrderAndDoNotReviveBatchChecks() {
        List<IslandRulePaths.Entry> initial = Collections.singletonList(CONTENT);
        IslandRuleFieldDraft draft = new IslandRuleFieldDraft(initial, INITIAL);
        draft.check(CONTENT, true);
        assertTrue(draft.invert());
        assertEquals(Arrays.asList(TITLE, ISLAND), draft.order());
        assertTrue(draft.checked().isEmpty());
        assertTrue(draft.clear());
        assertTrue(draft.order().isEmpty());
        assertFalse(draft.clear());
        assertTrue(draft.undo());
        assertEquals(Arrays.asList(TITLE, ISLAND), draft.order());
        assertTrue(draft.checked().isEmpty());
    }

    private static IslandRule parse(byte[] bytes) {
        assertNotNull(bytes);
        IslandRule rule = new IslandRuleCodec().decode(bytes).rule;
        assertNotNull(rule);
        return rule;
    }
}
