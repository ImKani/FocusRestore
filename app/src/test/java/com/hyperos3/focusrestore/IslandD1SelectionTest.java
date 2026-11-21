/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.lang.reflect.Field;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import com.hyperos3.focusrestore.IslandRuleSelection.Code;
import com.hyperos3.focusrestore.IslandRuleSelection.Event;
import com.hyperos3.focusrestore.IslandRuleSelection.Phase;

/** D1 纯选条语义及单次诊断边界；不构造 raw 通知、不调用提取或展示。 */
public class IslandD1SelectionTest {
    private final IslandRuleSelector selector = new IslandRuleSelector();
    private static final IslandRuleContext CONTEXT = new IslandRuleContext("test.app", "1", "train", "arrival");

    private static MatchClause clause(String field, String op, String text) {
        return new MatchClause(field, op, text);
    }
    private static IslandRule rule(List<MatchClause> clauses) {
        IslandRule.Metadata meta = new IslandRule.Metadata("not-a-match-source", null, null, null,
                null, null, null, null, null, "baseInfo", null, null, "ImKani", "", "2026-11-18T00:00:00Z");
        return new IslandRule(IslandRule.CURRENT_VERSION, meta, clauses, Collections.singletonList(new ExtractSpec(
                "poison", "/not-readable", true, Collections.singletonList(new ExtractSpec.Replacement("", "bad")))),
                new DisplaySpec(Collections.singletonList(new DisplaySpec.Projection("title", "absent")), "DO-NOT-EXECUTE", true));
    }
    private static IslandRuleSet.Entry entry(String id, boolean enabled, MatchClause... clauses) {
        return new IslandRuleSet.Entry(id, 0, enabled, 0, rule(Arrays.asList(clauses)), 1);
    }
    private static List<Code> codes(IslandRuleSelection selection) {
        List<Code> result = new ArrayList<>();
        for (Event event : selection.events) result.add(event.code);
        return result;
    }

    @Test public void protocolSemanticScanRunsBeforeEarlierMismatchedCondition() {
        IslandRuleSet.Entry invalid = entry("invalid", true,
                clause("business", "equals", "car"), clause("protocolVersion", "contains", "1"));
        IslandRuleSet.Entry next = entry("next", true, clause("business", "equals", "train"));
        IslandRuleSelection result = selector.selectDetailed(Arrays.asList(invalid, next), CONTEXT);
        assertSame(next, result.selectedEntry);
        assertEquals(Arrays.asList(Code.CANDIDATE, Code.L3_OPERATOR_TYPE, Code.CANDIDATE, Code.SELECTED), codes(result));
        Event error = result.events.get(1);
        assertEquals(Phase.SEMANTIC, error.phase); assertEquals(0, error.entryIndex);
        assertEquals("invalid", error.ruleId); assertEquals("$.match.all[1].op", error.path);
        assertSame(next, selector.select(Arrays.asList(invalid, next), CONTEXT));
    }

    @Test public void validatorFindsEveryProtocolOperationRegardlessOfContextAndReturnsImmutableProblems() {
        IslandRule input = rule(Arrays.asList(clause("scene", "equals", "absent"),
                clause("protocolVersion", "contains", "1"), clause("protocolVersion", "startsWith", "1"),
                clause("protocolVersion", "equals", "1")));
        List<IslandRuleMatchValidator.Problem> problems = new IslandRuleMatchValidator().validate(input);
        assertEquals(2, problems.size());
        assertEquals(Code.L3_OPERATOR_TYPE, problems.get(0).code);
        assertEquals("$.match.all[1].op", problems.get(0).path);
        assertEquals("$.match.all[2].op", problems.get(1).path);
        try { problems.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        assertTrue(new IslandRuleMatchValidator().validate(rule(Collections.singletonList(
                clause("business", "contains", "train")))).isEmpty());
    }

    @Test public void sixteenSemanticProblemsAreBoundedAndOversizedMatchIsInvalidRule() {
        MatchClause invalid = clause("protocolVersion", "startsWith", "1");
        IslandRuleMatchValidator validator = new IslandRuleMatchValidator();
        assertEquals(16, validator.validate(rule(Collections.nCopies(16, invalid))).size());
        IslandRule oversized = rule(Collections.nCopies(17, invalid));
        assertTrue(validator.validate(oversized).isEmpty());
        IslandRuleSet.Entry entry = new IslandRuleSet.Entry("oversized", 0, true, oversized, 1);
        assertEquals(Arrays.asList(Code.CANDIDATE, Code.INVALID_RULE, Code.END_NO_SELECTION),
                codes(selector.selectDetailed(Collections.singletonList(entry), CONTEXT)));
    }

    @Test public void diagnosticsCapCountsDroppedEventsWithoutDedupeAndResetsEachCall() {
        IslandRuleSet.Entry mismatch = entry("same-id", true, clause("business", "equals", "car"));
        IslandRuleSelection result = selector.selectDetailed(Collections.nCopies(70, mismatch), CONTEXT);
        assertNull(result.selectedEntry); assertEquals(128, result.events.size());
        assertEquals(13L, result.droppedEvents); // 70×(candidate+mismatch)+end = 141。
        assertEquals(Code.CANDIDATE, result.events.get(0).code);
        assertEquals(Code.CANDIDATE, result.events.get(2).code);
        try { result.events.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        IslandRuleSet.Entry good = entry("good", true, clause("business", "equals", "train"));
        IslandRuleSelection next = selector.selectDetailed(Collections.singletonList(good), CONTEXT);
        assertSame(good, next.selectedEntry); assertEquals(2, next.events.size()); assertEquals(0L, next.droppedEvents);
        assertEquals(128, result.events.size()); assertEquals(13L, result.droppedEvents);
    }

    @Test public void longExternalRuleIdsAreOmittedWithoutLosingEntryIndexOrSelection() {
        String exact = String.join("", Collections.nCopies(128, "r"));
        String over = exact + "r";
        IslandRuleSet.Entry first = entry(exact, true, clause("business", "equals", "car"));
        IslandRuleSet.Entry selected = entry(over, true, clause("business", "equals", "train"));
        IslandRuleSelection result = selector.selectDetailed(Arrays.asList(first, selected), CONTEXT);
        assertEquals(128, IslandRuleSelection.MAX_RULE_ID_UNITS);
        assertSame(selected, result.selectedEntry); assertEquals(over, result.selectedEntry.id);
        assertEquals(exact, result.events.get(0).ruleId);
        assertEquals(exact, result.events.get(1).ruleId);
        for (int i = 2; i < result.events.size(); i++) {
            assertNull(result.events.get(i).ruleId);
            assertEquals(1, result.events.get(i).entryIndex);
        }
        assertEquals(Code.SELECTED, result.events.get(3).code);
    }

    @Test public void droppedEventCounterSaturatesInsteadOfOverflowing() throws Exception {
        IslandRuleSelection.Collector collector = new IslandRuleSelection.Collector();
        for (int i = 0; i < IslandRuleSelection.MAX_EVENTS; i++)
            collector.add(i, null, Phase.SELECTION, Code.CANDIDATE, "$");
        Field dropped = IslandRuleSelection.Collector.class.getDeclaredField("dropped");
        dropped.setAccessible(true); dropped.setLong(collector, Long.MAX_VALUE - 1);
        collector.add(128, null, Phase.SELECTION, Code.CANDIDATE, "$");
        collector.add(129, null, Phase.SELECTION, Code.CANDIDATE, "$");
        assertEquals(Long.MAX_VALUE, collector.finish(null).droppedEvents);
    }

    @Test public void diagnosticsHaveFixedPathsAndNeverCopyConditionContextOrExceptionMessage() {
        final String secret = "private-condition-value-and-exception-message";
        final IslandRuleSet.Entry mismatch = entry("safe-rule-id", true, clause("business", "equals", secret));
        List<IslandRuleSet.Entry> input = new AbstractList<IslandRuleSet.Entry>() {
            @Override public int size() { return 2; }
            @Override public IslandRuleSet.Entry get(int index) {
                if (index == 0) throw new IllegalArgumentException(secret);
                return mismatch;
            }
        };
        IslandRuleSelection result = selector.selectDetailed(input, CONTEXT);
        assertEquals(Code.RULE_EXCEPTION, result.events.get(0).code);
        assertNull(result.events.get(0).ruleId);
        for (Event event : result.events) {
            assertFalse(event.path.contains(secret));
            assertTrue(event.ruleId == null || !event.ruleId.contains(secret));
            assertTrue(event.path.equals("$") || event.path.equals("$.match") || event.path.equals("$.match.all[0].value"));
        }
        assertEquals(Code.FIELD_MISMATCH, result.events.get(2).code);
    }

    @Test public void throwingGetAndInvalidInMemoryFieldOrOperatorDoNotBlockNextEntry() {
        final IslandRuleSet.Entry badField = entry("badField", true, clause("deviceModel", "equals", "x"));
        final IslandRuleSet.Entry badOperator = entry("badOp", true, clause("business", "regex", "train"));
        final IslandRuleSet.Entry good = entry("good", true, clause("business", "equals", "train"));
        List<IslandRuleSet.Entry> input = new AbstractList<IslandRuleSet.Entry>() {
            @Override public int size() { return 4; }
            @Override public IslandRuleSet.Entry get(int index) {
                if (index == 0) throw new IllegalStateException("never-published");
                if (index == 1) return badField;
                if (index == 2) return badOperator;
                return good;
            }
        };
        IslandRuleSelection result = selector.selectDetailed(input, CONTEXT);
        assertSame(good, result.selectedEntry);
        assertEquals(3, Collections.frequency(codes(result), Code.RULE_EXCEPTION));
        assertEquals(Phase.SELECTION, result.events.get(0).phase);
        assertEquals(Phase.MATCH, result.events.get(2).phase);
        assertEquals(Phase.MATCH, result.events.get(4).phase);
        assertEquals(Code.SELECTED, result.events.get(result.events.size() - 1).code);
        assertEquals(Arrays.asList(Code.CANDIDATE, Code.RULE_EXCEPTION, Code.END_NO_SELECTION),
                codes(selector.selectDetailed(Collections.singletonList(badOperator),
                        new IslandRuleContext(null, null, null, null))));
    }

    @Test public void firstSuccessStopsBeforeLaterEntryEvenForDiagnosticCollection() {
        final IslandRuleSet.Entry good = entry("good", true, clause("business", "equals", "train"));
        List<IslandRuleSet.Entry> input = new AbstractList<IslandRuleSet.Entry>() {
            @Override public int size() { return 2; }
            @Override public IslandRuleSet.Entry get(int index) {
                if (index == 0) return good;
                throw new AssertionError("later entry must not be inspected");
            }
        };
        IslandRuleSelection result = selector.selectDetailed(input, CONTEXT);
        assertSame(good, result.selectedEntry);
        assertEquals(Arrays.asList(Code.CANDIDATE, Code.SELECTED), codes(result));
    }

    @Test public void missingAndMismatchedFieldsHaveDistinctFixedDiagnosticCodes() {
        IslandRuleSet.Entry missing = entry("missing", true, clause("scene", "equals", "arrival"));
        IslandRuleSelection result = selector.selectDetailed(Collections.singletonList(missing),
                new IslandRuleContext("test.app", "1", "train", null));
        assertEquals(Arrays.asList(Code.CANDIDATE, Code.FIELD_MISSING, Code.END_NO_SELECTION), codes(result));
        assertEquals("$.match.all[0].field", result.events.get(1).path);
        assertEquals(Arrays.asList(Code.CANDIDATE, Code.FIELD_MISMATCH, Code.END_NO_SELECTION),
                codes(selector.selectDetailed(Collections.singletonList(missing),
                        new IslandRuleContext("test.app", "1", "train", "other"))));
    }

    @Test public void nullDisabledEmptyAndWrongVersionStayDefensiveWithoutImplicitMatchAll() {
        IslandRuleSet.Entry disabled = entry("disabled", false, clause("business", "equals", "train"));
        IslandRuleSet.Entry empty = entry("empty", true);
        IslandRule original = rule(Collections.singletonList(clause("business", "equals", "train")));
        IslandRuleSet.Entry future = new IslandRuleSet.Entry("future", 0, true,
                new IslandRule(3, original.meta, original.match, original.extract, original.display), 1);
        IslandRuleSelection result = selector.selectDetailed(Arrays.asList(null, disabled, empty, future), CONTEXT);
        assertNull(result.selectedEntry);
        assertEquals(Arrays.asList(Code.INVALID_RULE, Code.SKIPPED_DISABLED, Code.CANDIDATE, Code.INVALID_RULE,
                Code.CANDIDATE, Code.INVALID_RULE, Code.END_NO_SELECTION), codes(result));
        assertEquals(Collections.singletonList(Code.END_NO_SELECTION), codes(selector.selectDetailed(null, CONTEXT)));
        assertEquals(Collections.singletonList(Code.END_NO_SELECTION), codes(selector.selectDetailed(Collections.singletonList(future), null)));
        assertNull(selector.select(null, null));
    }
}
