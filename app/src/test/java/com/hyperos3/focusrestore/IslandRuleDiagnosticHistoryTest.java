/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import com.hyperos3.focusrestore.IslandRuleDiagnosticHistory.Entry;
import com.hyperos3.focusrestore.IslandRuleDiagnosticHistory.Snapshot;
import com.hyperos3.focusrestore.IslandRuleExecutor.Result;
import com.hyperos3.focusrestore.IslandRuleExecutor.Status;
import com.hyperos3.focusrestore.IslandRuleSelection.Code;
import com.hyperos3.focusrestore.IslandRuleSelection.Phase;

import static java.util.Collections.*;
import static org.junit.Assert.*;

public class IslandRuleDiagnosticHistoryTest {
    private final IslandRuleExecutor executor = new IslandRuleExecutor(() -> 0L);
    private final IslandRuleDiagnosticHistory history = new IslandRuleDiagnosticHistory();
    private static final IslandRuleContext CONTEXT = new IslandRuleContext("test.app", "1", "train", null);
    private static final MatchClause MATCH = new MatchClause("packageName", "equals", "test.app");

    @Test public void mergesSameFixedLocationAcrossExecutions() {
        Result first = execute(singletonList(rule("external-id-one", singletonList(MATCH))), "first-body");
        Result second = execute(singletonList(rule("external-id-two", singletonList(MATCH))), "second-body");
        history.record(first);
        history.record(second);

        Snapshot current = history.snapshot();
        assertEquals(2, current.entries.size());
        assertEntry(current.entries.get(0), 0, Phase.SELECTION, Code.CANDIDATE, "$", 2);
        assertEntry(current.entries.get(1), 0, Phase.EXECUTION, Code.EXECUTED, "$", 2);
        assertEquals(0, current.evictions);
        assertEquals(0, current.droppedExecutionEvents);
        assertEquals("first-body", first.text);
        assertEquals("second-body", second.text);
    }

    @Test public void keepsDifferentLocationsAndCodesSeparateWithoutInputValues() {
        MatchClause wrongBusiness = new MatchClause("business", "equals", "private-condition-value");
        MatchClause missingScene = new MatchClause("scene", "equals", "other-private-value");
        Result result = execute(Arrays.asList(rule("external-id", singletonList(missingScene)),
                rule("secret-id", Arrays.asList(MATCH, wrongBusiness)), rule("next", singletonList(missingScene))),
                "private-body");
        assertEquals(Status.FALLBACK, result.status);
        history.record(result);
        List<Entry> entries = history.snapshot().entries;
        assertEquals(7, entries.size());
        assertEntry(entries.get(1), 0, Phase.MATCH, Code.FIELD_MISSING, "$.match.all[0].field", 1);
        assertEntry(entries.get(3), 1, Phase.MATCH, Code.FIELD_MISMATCH, "$.match.all[1].value", 1);
        assertEntry(entries.get(5), 2, Phase.MATCH, Code.FIELD_MISSING, "$.match.all[0].field", 1);
        assertEntry(entries.get(6), -1, Phase.EXECUTION, Code.FALLBACK, "$", 1);
    }

    @Test public void separatesMatchAndPreparationPathsEvenForSameCandidate() {
        Result mismatch = executor.execute(singletonList(rule("rule", singletonList(MATCH))),
                new IslandRuleContext("different", "1", "train", null), "{}");
        Result invalidPayload = executor.execute(singletonList(rule("rule", singletonList(MATCH))),
                CONTEXT, "invalid json private input");
        history.record(mismatch);
        history.record(invalidPayload);

        List<Entry> entries = history.snapshot().entries;
        assertEquals(4, entries.size());
        assertEntry(entries.get(0), 0, Phase.MATCH, Code.FIELD_MISMATCH, "$.match.all[0].value", 1);
        assertEntry(entries.get(1), 0, Phase.SELECTION, Code.CANDIDATE, "$", 2);
        assertEntry(entries.get(2), 0, Phase.PREPARATION, Code.R_VALUE_TYPE, "$", 1);
        assertEntry(entries.get(3), -1, Phase.EXECUTION, Code.FALLBACK, "$", 2);
    }

    @Test public void evictsLeastRecentlyObservedAtCapacity() {
        Result fallback = execute(emptyList(), "ignored");
        history.record(fallback);
        List<IslandRuleSet.Entry> rules = invalidRules(3, 16);
        rules.add(invalidRule("partial", 9));
        rules.add(rule("winner", singletonList(MATCH)));
        history.record(execute(rules, "body"));
        Snapshot full = history.snapshot();
        assertEquals(64, full.entries.size());
        assertEquals(0, full.evictions);
        assertEntry(full.entries.get(0), -1, Phase.EXECUTION, Code.FALLBACK, "$", 1);
        assertEntry(full.entries.get(1), 0, Phase.SELECTION, Code.CANDIDATE, "$", 1);

        history.record(fallback);
        history.record(execute(singletonList(null), "ignored"));
        Snapshot updated = history.snapshot();
        assertEquals(64, updated.entries.size());
        assertEquals(1, updated.evictions);
        assertEntry(updated.entries.get(0), 0, Phase.SEMANTIC, Code.L3_OPERATOR_TYPE, "$.match.all[0].op", 1);
        assertEntry(updated.entries.get(62), 0, Phase.SELECTION, Code.INVALID_RULE, "$", 1);
        assertEntry(updated.entries.get(63), -1, Phase.EXECUTION, Code.FALLBACK, "$", 3);
    }

    @Test public void preservesPerExecutionEventLimitAndTerminalResults() {
        List<IslandRuleSet.Entry> rules = invalidRules(8, 16);
        Result fallback = execute(rules, "hidden");
        IslandRuleSet.Entry winner = rule("winner", singletonList(MATCH));
        rules.add(winner);
        Result success = execute(rules, "visible");
        history.record(fallback);
        history.record(success);

        assertEquals(128, fallback.events.size());
        assertEquals(128, success.events.size());
        assertEquals(9, fallback.droppedEvents);
        assertEquals(10, success.droppedEvents);
        assertEquals(Status.FALLBACK, fallback.status);
        assertNull(fallback.text);
        assertEquals(Status.SUCCESS, success.status);
        assertSame(winner, success.winningEntry);
        assertEquals("visible", success.text);
        Snapshot current = history.snapshot();
        assertEquals(64, current.entries.size());
        assertEquals(19, current.droppedExecutionEvents);
        assertTrue(current.entries.stream().noneMatch(e -> e.code == Code.FALLBACK || e.code == Code.EXECUTED));
    }

    @Test public void clearResetsEntriesAndCountersWithoutChangingOldSnapshot() {
        history.record(execute(invalidRules(8, 16), "ignored"));
        Snapshot saved = history.snapshot();
        assertTrue(saved.evictions > 0);
        assertEquals(9, saved.droppedExecutionEvents);
        try {
            saved.entries.clear();
            fail("expected immutable snapshot");
        } catch (UnsupportedOperationException expected) {
            assertEquals(64, saved.entries.size());
        }
        history.clear();
        Snapshot empty = history.snapshot();
        assertTrue(empty.entries.isEmpty());
        assertEquals(0, empty.evictions);
        assertEquals(0, empty.droppedExecutionEvents);
        assertEquals(64, saved.entries.size());
        assertEquals(9, saved.droppedExecutionEvents);
    }

    @Test public void countersSaturateWithoutWrapping() {
        assertEquals(Long.MAX_VALUE - 1, IslandRuleDiagnosticHistory.addSaturating(Long.MAX_VALUE - 2, 1));
        assertEquals(Long.MAX_VALUE, IslandRuleDiagnosticHistory.addSaturating(Long.MAX_VALUE - 1, 1));
        assertEquals(Long.MAX_VALUE, IslandRuleDiagnosticHistory.addSaturating(Long.MAX_VALUE - 1, 2));
        assertEquals(Long.MAX_VALUE, IslandRuleDiagnosticHistory.addSaturating(Long.MAX_VALUE, 1));
    }

    private Result execute(List<IslandRuleSet.Entry> rules, String text) {
        return executor.execute(rules, CONTEXT, "{\"title\":\"" + text + "\"}");
    }

    private static List<IslandRuleSet.Entry> invalidRules(int count, int conditions) {
        List<IslandRuleSet.Entry> rules = new ArrayList<>();
        for (int i = 0; i < count; i++) rules.add(invalidRule("invalid-" + i, conditions));
        return rules;
    }

    private static IslandRuleSet.Entry invalidRule(String id, int conditions) {
        return rule(id, nCopies(conditions, new MatchClause("protocolVersion", "contains", "private")));
    }

    private static IslandRuleSet.Entry rule(String id, List<MatchClause> clauses) {
        IslandRule.Metadata metadata = new IslandRule.Metadata("test.app", null, null, null, null,
                null, null, null, null, "legacy", null, null, "ImKani", "", "2026-11-18T00:00:00Z");
        IslandRule rule = new IslandRule(IslandRule.CURRENT_VERSION, metadata, clauses,
                singletonList(new ExtractSpec("a", "/title", true, emptyList())),
                new DisplaySpec(singletonList(new DisplaySpec.Projection("title", "a")), "·", false));
        return new IslandRuleSet.Entry(id, 0, true, rule, 1);
    }

    private static void assertEntry(Entry entry, int index, Phase phase, Code code, String path,
            long occurrences) {
        assertEquals(index, entry.entryIndex);
        assertEquals(phase, entry.phase);
        assertEquals(code, entry.code);
        assertEquals(path, entry.path);
        assertEquals(occurrences, entry.occurrences);
    }
}
