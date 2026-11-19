/* SPDX-License-Identifier: GPL-3.0-only
 * ImKani / FocusRestore / https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static com.hyperos3.focusrestore.IslandRulePostProcessor.Code.*;
import static org.junit.Assert.*;

public class IslandRulePostProcessorTest {
    private static final String PAIR = "\uD83D\uDE00";
    private final IslandRuleExtractor extractor = new IslandRuleExtractor();
    private final IslandRulePostProcessor processor = new IslandRulePostProcessor();

    private static final class TestClock implements IslandRuleBudget.Clock {
        long now;
        int checksUntilTimeout = -1;

        @Override public long nanoTime() {
            return checksUntilTimeout >= 0 && checksUntilTimeout-- == 0
                    ? (now = IslandRuleBudget.TIMEOUT_NANOS) : now;
        }
    }

    @Test public void filtersExactlyTheFrozenControlAndZeroWidthSet() {
        StringBuilder removed = new StringBuilder("\u200B\u2060\uFEFF");
        for (char c = 0; c <= 0x1F; c++) if (c != '\t' && c != '\n') removed.append(c);
        for (char c = 0x80; c <= 0x9F; c++) removed.append(c);
        assertEquals("ab c", text(render(display("|", false, "a"),
                "a" + removed + "b\t\nc", null, null)));
    }

    @Test public void preservesJoinersVariationSelectorsKeycapAndUnlistedCharacters() {
        String kept = "\u200C\u200D\uFE0E\uFE0F\u20E3\u007F\u061C\u200E\u200F"
                + "\u202A\u202E\u2066\u2069\u180E" + PAIR;
        assertEquals(kept, text(render(display("|", false, "a"), kept, null, null)));
        assertEquals("打开", text(render(display("|", false, "a"), "打开", null, null)));
    }

    @Test public void normalizesEveryRetainedWhiteSpaceAndTrimsEdges() {
        String spaces = "\t\n \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006"
                + "\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000";
        StringBuilder raw = new StringBuilder(spaces).append('a');
        StringBuilder expected = new StringBuilder("a");
        for (int i = 0; i < spaces.length(); i++) {
            raw.append(spaces.charAt(i)).append('\u200B').append(spaces.charAt(i)).append('b');
            expected.append(" b");
        }
        assertEquals(expected.toString(), text(render(display("|", false, "a"),
                raw.append(spaces).toString(), null, null)));
    }

    @Test public void projectsInDisplayOrderNotBindingOrSlotOrder() {
        DisplaySpec display = new DisplaySpec(Arrays.asList(new DisplaySpec.Projection("extra", "c"),
                new DisplaySpec.Projection("title", "a"), new DisplaySpec.Projection("subtitle", "b")),
                "·", false);
        assertEquals("3·1·2", text(render(display, "1", "2", "3")));
    }

    @Test public void missingEmptyAndFilteredFragmentsDoNotAddSeparators() {
        assertEquals("first|last", text(render(display("|", false, "b", "a", "b", "c", "b"),
                "first", null, "last")));
        assertEquals("first|last", text(render(display("|", false, "b", "a", "b", "c", "b"),
                "first", "", "last")));
        assertEquals("first|last", text(render(display("|", false, "b", "a", "b", "c", "b"),
                "first", "\uFEFF\t\u3000", "last")));
    }

    @Test public void fullStringPassNormalizesAndFiltersSeparator() {
        assertEquals("A / B", text(render(display("\u200B\u00A0\n/\uFEFF\u3000\t", false, "a", "b"),
                " A ", " B ", null)));
        assertEquals("A B", text(render(display("\n\u00A0", false, "a", "b"), "A", "B", null)));
        assertEquals("AB", text(render(display("\u200B\u0085", false, "a", "b"), "A", "B", null)));
        assertEquals("AB", text(render(display("", false, "a", "b"), "A", "B", null)));
    }

    @Test public void exactNormalizedDedupeKeepsFirstAndCaseDifferences() {
        assertEquals("A B|a b", text(render(display("|", true, "b", "c", "a", "c", "b"),
                "\tA\u200B\u3000B", "A B", "a b")));
        assertEquals("a b|A B", text(render(display("|", true, "c", "a", "b", "c"),
                "\tA\u200B\u3000B", "A B", "a b")));
    }

    @Test public void disabledDedupeKeepsRepeatedRefsAndEqualValues() {
        assertEquals("A|A|A", text(render(display("|", false, "a", "b", "a"), "A", "\tA", null)));
    }

    @Test public void emptyResultIsFixedSignalWithNoPartialText() {
        assertFailure(R_EMPTY_RESULT, "EMPTY_RESULT", render(display("visible", false, "a", "b", "c"),
                null, "", "\t\uFEFF"));
    }

    @Test public void emptyJoinAndEnabledDedupeAreStillSinglePasses() {
        IslandRuleBudget budget = budget();
        DisplaySpec display = display("|", true, "a");
        IslandRuleExtractor.Result extraction = extract(display, budget, 0, null, null, null);
        assertFailure(R_EMPTY_RESULT, "EMPTY_RESULT", processor.process(extraction, display, budget));
        assertEquals(1, budget.stringOperations());
        assertEquals(1, budget.dedupeOperations());
    }

    @Test public void reusesReadsAndSharesReplacementAndJoinBudget() {
        DisplaySpec display = display("|", false, "b", "a", "b");
        IslandRuleBudget budget = budget();
        IslandRuleExtractor.Result extraction = extract(display, budget, 9, "A", "B", null);
        assertEquals(9, budget.stringOperations());
        while (budget.fieldReads() < IslandRuleBudget.MAX_FIELD_READS) budget.readField();
        assertEquals("B|A|B", text(processor.process(extraction, display, budget)));
        assertEquals(10, budget.stringOperations());
        assertEquals(100, budget.fieldReads());
        assertEquals(0, budget.dedupeOperations());
    }

    @Test public void rejectsJoinAfterAllTenOperationsWereSpentOnReplacements() {
        IslandRuleBudget budget = budget();
        DisplaySpec display = display("|", true, "a", "b");
        IslandRuleExtractor.Result extraction = extract(display, budget, 10, "A", "B", null);
        assertFailure(R_BUDGET, "STRING_OPERATION_LIMIT", processor.process(extraction, display, budget));
        assertEquals(10, budget.stringOperations());
        assertEquals(0, budget.dedupeOperations());
    }

    @Test public void dedupeDoesNotConsumeStringBudgetOrChargePerComparison() {
        IslandRuleBudget budget = budget();
        DisplaySpec display = display("|", true, "b", "a", "b", "a", "b");
        IslandRuleExtractor.Result extraction = extract(display, budget, 9, " A", "A\t", null);
        assertEquals("A", text(processor.process(extraction, display, budget)));
        assertEquals(1, budget.dedupeOperations());
        assertEquals(10, budget.stringOperations());
    }

    @Test public void rejectsSecondDedupeOnSameBudget() {
        IslandRuleBudget budget = budget();
        DisplaySpec display = display("|", true, "a", "a");
        IslandRuleExtractor.Result extraction = extract(display, budget, 0, "A", null, null);
        assertEquals("A", text(processor.process(extraction, display, budget)));
        assertFailure(R_BUDGET, "DEDUPE_LIMIT", processor.process(extraction, display, budget));
        assertEquals(1, budget.dedupeOperations());
    }

    @Test public void exactly4096IsRetainedAnd4097Truncates() {
        String prefix = repeat("a", 4095);
        assertEquals(prefix + "b", text(render(display("", false, "a", "b"), prefix, "b", null)));
        assertEquals(prefix + "b", text(render(display("", false, "a", "b"), prefix, "bc", null)));
    }

    @Test public void truncationKeepsWholePairThatFitsAndDropsSplitPair() {
        String fits = repeat("a", 4094);
        String split = fits + "a";
        assertEquals(fits + PAIR, text(render(display("", false, "a", "b"), fits, PAIR + "b", null)));
        assertEquals(split, text(render(display("", false, "a", "b"), split, PAIR + "b", null)));
        assertEquals(split, text(render(display(PAIR, false, "a", "b"), split, "b", null)));
    }

    @Test public void trimsWhitespaceExposedByTruncation() {
        String prefix = repeat("a", 4095);
        assertEquals(prefix, text(render(display("\u3000", false, "a", "b"), prefix, "b", null)));
        String beforePair = repeat("a", 4094);
        assertEquals(beforePair, text(render(display(" ", false, "a", "b"), beforePair, PAIR, null)));
    }

    @Test public void acceptsMaximumBoundedProjectionAndSeparatorLengths() {
        String[] refs = new String[16];
        Arrays.fill(refs, "a");
        String value = repeat("a", 4096);
        assertEquals(value, text(render(display(repeat("-", 32), false, refs), value, null, null)));
    }

    @Test public void reusesDeadlineAtExactThreshold() {
        TestClock clock = new TestClock();
        IslandRuleBudget budget = new IslandRuleBudget(clock);
        DisplaySpec display = display("|", false, "a");
        IslandRuleExtractor.Result extraction = extract(display, budget, 0, "A", null, null);
        clock.now = IslandRuleBudget.TIMEOUT_NANOS - 1;
        assertEquals("A", text(processor.process(extraction, display, budget)));
        clock.now++;
        assertFailure(R_TIMEOUT, "TIME_LIMIT", processor.process(extraction, display, budget));
        assertEquals(1, budget.stringOperations());
    }

    @Test public void cooperativelyTimesOutInsideFragmentScanWithoutPartialText() {
        TestClock clock = new TestClock();
        IslandRuleBudget budget = new IslandRuleBudget(clock);
        DisplaySpec display = display("", false, "a");
        IslandRuleExtractor.Result extraction = extract(display, budget, 0, repeat(PAIR, 2048), null, null);
        clock.checksUntilTimeout = 6;
        assertFailure(R_TIMEOUT, "TIME_LIMIT", processor.process(extraction, display, budget));
        assertEquals(1, budget.stringOperations());
    }

    @Test public void cooperativelyTimesOutInsideFullStringScanWithoutPartialText() {
        TestClock clock = new TestClock();
        IslandRuleBudget budget = new IslandRuleBudget(clock);
        DisplaySpec display = display("", false, "a");
        IslandRuleExtractor.Result extraction = extract(display, budget, 0, repeat("a", 4096), null, null);
        // 3 处扫描前检查，加上第一遍 16 个检查点；在第二遍推进到期限。
        clock.checksUntilTimeout = 22;
        assertFailure(R_TIMEOUT, "TIME_LIMIT", processor.process(extraction, display, budget));
        assertEquals(1, budget.stringOperations());
    }

    @Test public void rejectedExtractionIsNotTreatedAsAnEmptySuccess() {
        IslandRuleBudget budget = budget();
        DisplaySpec display = display("|", false, "a");
        IslandRuleExtractor.Result extraction = extractor.extract(rule(display, 0), null, budget);
        assertFalse(extraction.isSuccess());
        assertFailure(R_EXCEPTION, "POST_PROCESS_INPUT_REQUIRED", processor.process(extraction, display, budget));
        assertEquals(0, budget.stringOperations());
    }

    private IslandRulePostProcessor.Result render(DisplaySpec display, String a, String b, String c) {
        IslandRuleBudget budget = budget();
        return processor.process(extract(display, budget, 0, a, b, c), display, budget);
    }

    private IslandRuleExtractor.Result extract(DisplaySpec display, IslandRuleBudget budget,
            int replacements, String a, String b, String c) {
        StringBuilder raw = new StringBuilder("{");
        member(raw, "title", a);
        member(raw, "desc1", b);
        member(raw, "desc2", c);
        IslandRuleExtractor.Preparation prepared = extractor.prepare(raw.append('}').toString(), budget);
        assertTrue(prepared.isSuccess());
        IslandRuleExtractor.Result result = extractor.extract(rule(display, replacements), prepared.payload, budget);
        assertTrue(result.failure == null ? "" : result.failure.reason, result.isSuccess());
        return result;
    }

    private static IslandRule rule(DisplaySpec display, int replacements) {
        IslandRule.Metadata meta = new IslandRule.Metadata("test.package", null, null, null, null,
                null, null, null, null, "legacy", null, null, "ImKani", "", "2026-11-18T00:00:00Z");
        return new IslandRule(1, meta,
                Collections.singletonList(new MatchClause("packageName", "equals", "test.package")),
                Arrays.asList(binding("a", "/title", Math.min(8, replacements)),
                        binding("b", "/desc1", Math.max(0, replacements - 8)), binding("c", "/desc2", 0)),
                display);
    }

    private static ExtractSpec binding(String id, String path, int operations) {
        return new ExtractSpec(id, path, false, Collections.nCopies(operations,
                new ExtractSpec.Replacement("no-match", "unused")));
    }

    private static DisplaySpec display(String separator, boolean dedupe, String... refs) {
        List<DisplaySpec.Projection> order = new ArrayList<DisplaySpec.Projection>();
        for (String ref : refs) order.add(new DisplaySpec.Projection("title", ref));
        return new DisplaySpec(order, separator, dedupe);
    }

    private static void member(StringBuilder raw, String key, String value) {
        if (value == null) return;
        if (raw.length() > 1) raw.append(',');
        raw.append('"').append(key).append("\":\"");
        for (int i = 0; i < value.length(); i++) {
            String hex = Integer.toHexString(value.charAt(i));
            raw.append("\\u").append("0000", 0, 4 - hex.length()).append(hex);
        }
        raw.append('"');
    }

    private static IslandRuleBudget budget() { return new IslandRuleBudget(new TestClock()); }
    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
    private static String text(IslandRulePostProcessor.Result result) {
        assertTrue(result.reason, result.isSuccess());
        assertNull(result.reason);
        return result.text;
    }
    private static void assertFailure(IslandRulePostProcessor.Code code, String reason,
            IslandRulePostProcessor.Result result) {
        assertFalse(result.isSuccess());
        assertEquals(code, result.code);
        assertEquals(reason, result.reason);
        assertNull(result.text);
    }
}
