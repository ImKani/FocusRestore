/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import com.hyperos3.focusrestore.IslandRuleSelection.Event;
import com.hyperos3.focusrestore.IslandRuleSelection.Phase;
import static com.hyperos3.focusrestore.IslandRuleSelection.Code.*;
import static org.junit.Assert.*;

public class IslandRuleExecutorTest {
    private static final IslandRuleContext CONTEXT = new IslandRuleContext("test.app", "1", "train", "arrival");
    private static final String RAW = "{\"title\":\" \u200B\u3000\",\"desc1\":\" A \u3000 B \",\"desc2\":\"C\"}";
    private static final MatchClause MATCH = new MatchClause("packageName", "equals", "test.app");
    private final IslandRuleExecutor executor = new IslandRuleExecutor(() -> 0L);

    @Test public void consumesStablePriorityOrderFromLoader() {
        IslandRuleSetLoader.Snapshot snapshot = new IslandRuleSetLoader().load(1, Arrays.asList(
                source("low", Integer.MIN_VALUE, true, "/desc2"),
                source("disabled", Integer.MAX_VALUE, false, "/desc2"),
                source("high-empty", Integer.MAX_VALUE, true, "/title"),
                source("tie-first", 7, true, "/desc1"),
                source("tie-second", 7, true, "/desc2")));
        assertTrue(snapshot.failures.isEmpty());
        assertEquals(4, snapshot.orderedActiveEntries.size());
        assertEquals("high-empty", snapshot.orderedActiveEntries.get(0).id);
        IslandRuleExecutor.Result result = executor.execute(snapshot.orderedActiveEntries, CONTEXT, RAW);
        success(result, snapshot.orderedActiveEntries.get(1), "A B");
        assertEquals("tie-first", result.winningEntry.id);
        event(result, 0, Phase.POST_PROCESS, R_EMPTY_RESULT, "$.display");
        assertEquals(1, result.events.get(result.events.size() - 1).entryIndex);
    }

    @Test public void mismatchAndMissingContextContinueWithoutPreparingPayload() {
        IslandRuleSet.Entry mismatch = entry("mismatch", "/desc1", new MatchClause("business", "equals", "other"));
        IslandRuleSet.Entry missing = entry("missing", "/desc1", new MatchClause("scene", "equals", "arrival"));
        IslandRuleSet.Entry good = entry("good", "/desc1", MATCH);
        IslandRuleExecutor.Result result = executor.execute(Arrays.asList(mismatch, missing, good),
                new IslandRuleContext("test.app", "1", "train", null), RAW);
        success(result, good, "A B");
        event(result, 0, Phase.MATCH, FIELD_MISMATCH, "$.match.all[0].value");
        event(result, 1, Phase.MATCH, FIELD_MISSING, "$.match.all[0].field");
        IslandRuleExecutor.Result invalidPayload = executor.execute(Arrays.asList(mismatch, good), CONTEXT, "not JSON");
        fallback(invalidPayload);
        assertEquals(5, invalidPayload.events.size());
        event(invalidPayload, 1, Phase.PREPARATION, R_VALUE_TYPE, "$");
    }

    @Test public void semanticValidationStillPrecedesConditionShortCircuit() {
        IslandRuleSet.Entry bad = entry("semantic", "/desc1", new MatchClause("business", "equals", "other"),
                new MatchClause("protocolVersion", "contains", "1"));
        IslandRuleSet.Entry good = entry("next", "/desc1", MATCH);
        IslandRuleExecutor.Result result = executor.execute(Arrays.asList(bad, good), CONTEXT, RAW);
        success(result, good, "A B");
        event(result, 0, Phase.SEMANTIC, L3_OPERATOR_TYPE, "$.match.all[1].op");
    }

    @Test public void reusesD1LiteralAndConditions() {
        IslandRuleSet.Entry good = entry("literal", "/desc1", MATCH,
                new MatchClause("protocolVersion", "equals", "1"),
                new MatchClause("business", "contains", "rai"),
                new MatchClause("scene", "startsWith", "arr"));
        assertSame(good, new IslandRuleSelector().select(Collections.singletonList(good), CONTEXT));
        success(executor.execute(Collections.singletonList(good), CONTEXT, RAW), good, "A B");
    }

    @Test public void requiredMissingWrongTypeAndInvalidPathsContinue() {
        IslandRuleSet.Entry missing = entry("missing", "/desc2", MATCH);
        IslandRuleSet.Entry invalid = entry("invalid", "/other", MATCH);
        IslandRuleSet.Entry wrongType = entry("wrong", "/title", MATCH);
        IslandRuleSet.Entry good = entry("good", "/desc1", MATCH);
        IslandRuleExecutor.Result result = executor.execute(Arrays.asList(missing, invalid, wrongType, good), CONTEXT,
                "{\"title\":[],\"desc1\":\"good\"}");
        success(result, good, "good");
        event(result, 0, Phase.EXTRACTION, R_MISSING_FIELD, "$.extract[0].path");
        event(result, 1, Phase.EXTRACTION, L3_PATH_SCOPE, "$.extract[0].path");
        event(result, 2, Phase.EXTRACTION, R_VALUE_TYPE, "$.extract[0].path");
    }

    @Test public void oldSelectorReturnsFirstMatchWhileExecutorRequiresNonemptyText() {
        IslandRuleSet.Entry empty = entry("empty", "/title", MATCH);
        IslandRuleSet.Entry good = entry("good", "/desc1", MATCH);
        List<IslandRuleSet.Entry> entries = Arrays.asList(empty, good);
        assertSame(empty, new IslandRuleSelector().select(entries, CONTEXT));
        IslandRuleExecutor.Result result = executor.execute(entries, CONTEXT, RAW);
        success(result, good, "A B");
        event(result, 0, Phase.POST_PROCESS, R_EMPTY_RESULT, "$.display");
    }

    @Test public void successStopsBeforeReadingOrTimingLaterCandidate() {
        IslandRuleSet.Entry good = entry("good", "/desc1", MATCH);
        List<IslandRuleSet.Entry> entries = new AbstractList<IslandRuleSet.Entry>() {
            @Override public int size() { return 2; }
            @Override public IslandRuleSet.Entry get(int index) {
                if (index == 0) return good;
                throw new AssertionError("later candidate was inspected");
            }
        };
        IslandRuleExecutor.Result result = executor.execute(entries, CONTEXT, RAW);
        success(result, good, "A B");
        assertEquals(2, result.events.size());
        event(result, 0, Phase.EXECUTION, EXECUTED, "$");
    }

    @Test public void candidateAndMatchExceptionsContinueWithoutCopyingMessages() {
        String privateText = "private-token-not-for-diagnostics";
        IslandRuleSet.Entry badOp = entry("bad-op", "/desc1", new MatchClause("business", "regex", privateText));
        IslandRuleSet.Entry good = entry("good", "/desc1", MATCH);
        List<IslandRuleSet.Entry> entries = new AbstractList<IslandRuleSet.Entry>() {
            @Override public int size() { return 3; }
            @Override public IslandRuleSet.Entry get(int index) {
                if (index == 0) throw new IllegalStateException(privateText);
                return index == 1 ? badOp : good;
            }
        };
        IslandRuleExecutor.Result result = executor.execute(entries, CONTEXT, "{\"desc1\":\"" + privateText + "\"}");
        success(result, good, privateText);
        event(result, 0, Phase.SELECTION, R_EXCEPTION, "$");
        event(result, 1, Phase.MATCH, R_EXCEPTION, "$.match");
        for (Event event : result.events) {
            assertFalse(event.path.contains(privateText));
            assertTrue(event.ruleId == null || !event.ruleId.contains(privateText));
        }
    }

    @Test public void malformedPayloadIsRejectedForEveryMatchedCandidate() {
        IslandRuleSet.Entry first = entry("first", "/desc1", MATCH);
        IslandRuleSet.Entry second = entry("second", "/desc2", MATCH);
        IslandRuleExecutor.Result result = executor.execute(Arrays.asList(first, second), CONTEXT, "[0]");
        fallback(result);
        event(result, 0, Phase.PREPARATION, R_VALUE_TYPE, "$");
        event(result, 1, Phase.PREPARATION, R_VALUE_TYPE, "$");
    }

    @Test public void allInvalidReturnsSignalWithNoPartialBodyOrWinningEntry() {
        IslandRuleSet.Entry disabled = make("disabled", false, "legacy", Collections.singletonList(MATCH),
                Collections.singletonList(binding("a", "/desc1", 0)), "a");
        IslandRuleSet.Entry empty = entry("empty", "/title", MATCH);
        IslandRuleExecutor.Result result = executor.execute(Arrays.asList(null, disabled, empty), CONTEXT, RAW);
        fallback(result);
        event(result, 0, Phase.SELECTION, INVALID_RULE, "$");
        event(result, 1, Phase.SELECTION, SKIPPED_DISABLED, "$");
        event(result, 2, Phase.POST_PROCESS, R_EMPTY_RESULT, "$.display");
        fallback(executor.execute(Collections.emptyList(), CONTEXT, RAW));
        fallback(executor.execute(Collections.singletonList(empty), null, RAW));
        fallback(executor.execute(null, CONTEXT, RAW));
    }

    @Test public void extractionTreatsMatchReadsAsPartOfTheSameHundredReadLimit() {
        String deep = "/param_island/bigIslandArea/imageTextInfoLeft/textInfo/title";
        List<ExtractSpec> bindings = new ArrayList<>();
        for (int index = 0; index < 19; index++) bindings.add(binding("d" + index, deep, 0));
        String raw = "{\"param_v2\":{\"param_island\":{\"bigIslandArea\":{\"imageTextInfoLeft\":"
                + "{\"textInfo\":{\"title\":\"deep\"}}}}},\"desc1\":\"next\"}";
        // 1 次容器探测 + 19×5 次路径读取；4 条 match 恰好 100 次，5 条超限。
        IslandRuleSet.Entry atLimit = make("at-limit", true, "param_island", Collections.nCopies(4, MATCH), bindings, "d0");
        IslandRuleSet.Entry over = make("over", true, "param_island", Collections.nCopies(5, MATCH), bindings, "d0");
        IslandRuleSet.Entry good = entry("good", "/desc1", MATCH);
        success(executor.execute(Collections.singletonList(atLimit), CONTEXT, raw), atLimit, "deep");
        IslandRuleExecutor.Result result = executor.execute(Arrays.asList(over, good), CONTEXT, raw);
        success(result, good, "next");
        event(result, 0, Phase.EXTRACTION, R_BUDGET, "$.extract[18].path");
    }

    @Test public void stringBudgetSpansExtractionAndDisplayButResetsForEveryCandidate() {
        IslandRuleSet.Entry over = make("over", true, "legacy", Collections.singletonList(MATCH),
                Arrays.asList(binding("a", "/desc1", 8), binding("b", "/desc2", 2)), "a");
        IslandRuleSet.Entry atLimit = make("at-limit", true, "legacy", Collections.nCopies(16, MATCH),
                Arrays.asList(binding("a", "/desc1", 8), binding("b", "/desc2", 1)), "b");
        IslandRuleExecutor.Result result = executor.execute(Arrays.asList(over, atLimit), CONTEXT, RAW);
        success(result, atLimit, "C");
        event(result, 0, Phase.POST_PROCESS, R_BUDGET, "$.display");
    }

    @Test public void matchingInputBoundDoesNotChangeLegacySelectorBehavior() {
        String oversized = String.join("", Collections.nCopies(InputLimits.MAX_PAYLOAD_CHARS + 1, "a"));
        IslandRuleContext context = new IslandRuleContext("test.app", "1", oversized, null);
        IslandRuleSet.Entry over = entry("over", "/desc1", new MatchClause("business", "startsWith", "a"));
        IslandRuleSet.Entry good = entry("good", "/desc2", MATCH);
        assertSame(over, new IslandRuleSelector().select(Arrays.asList(over, good), context));
        IslandRuleExecutor.Result result = executor.execute(Arrays.asList(over, good), context, RAW);
        success(result, good, "C");
        event(result, 0, Phase.MATCH, R_INPUT_LIMIT, "$.match.all[0]");
    }

    @Test public void timeoutDuringContainsContinuesUnderFreshDeadline() {
        IslandRuleSet.Entry slow = entry("slow", "/desc1", new MatchClause("business", "contains", "z"));
        IslandRuleSet.Entry good = entry("good", "/desc2", MATCH);
        IslandRuleContext context = new IslandRuleContext("test.app", "1", String.join("", Collections.nCopies(512, "a")), null);
        IslandRuleExecutor.Result result = expiringExecutor(4).execute(Arrays.asList(slow, good), context, RAW);
        success(result, good, "C");
        event(result, 0, Phase.MATCH, R_TIMEOUT, "$.match");
    }

    @Test public void deadlineAlsoAppliesToPreparationExtractionAndPostProcessing() {
        IslandRuleSet.Entry first = entry("first", "/desc1", MATCH);
        IslandRuleSet.Entry second = entry("second", "/desc2", MATCH);
        // 扫描可控时钟的推进点，不依赖机器速度或把各模块检查点数量写死。
        List<Phase> pending = new ArrayList<>(Arrays.asList(Phase.PREPARATION, Phase.EXTRACTION, Phase.POST_PROCESS));
        for (int checkpoint = 2; checkpoint <= 100 && !pending.isEmpty(); checkpoint++) {
            IslandRuleExecutor.Result result = expiringExecutor(checkpoint).execute(Arrays.asList(first, second), CONTEXT, RAW);
            if (result.winningEntry == second) {
                success(result, second, "C");
                for (Event event : result.events) if (event.entryIndex == 0 && event.code == R_TIMEOUT)
                    pending.remove(event.phase);
            }
        }
        assertTrue("Unexercised deadlines: " + pending, pending.isEmpty());
    }

    @Test public void diagnosticsCapIsPerExecutionAndDoesNotDisableSuccessOrFallback() {
        List<IslandRuleSet.Entry> entries = new ArrayList<>();
        MatchClause invalid = new MatchClause("protocolVersion", "contains", "hidden-value");
        for (int index = 0; index < 8; index++) entries.add(make("bad" + index, true, "legacy",
                Collections.nCopies(16, invalid), Collections.singletonList(binding("a", "/desc1", 0)), "a"));
        IslandRuleExecutor.Result rejected = executor.execute(entries, CONTEXT, RAW);
        assertEquals(IslandRuleExecutor.Status.FALLBACK, rejected.status);
        assertNull(rejected.winningEntry);
        assertNull(rejected.text);
        assertEquals(128, rejected.events.size());
        assertEquals(9L, rejected.droppedEvents);
        assertFalse(rejected.events.stream().anyMatch(event -> event.code == FALLBACK));
        IslandRuleSet.Entry good = entry("good", "/desc2", MATCH);
        entries.add(good);
        IslandRuleExecutor.Result successful = executor.execute(entries, CONTEXT, RAW);
        success(successful, good, "C");
        assertEquals(128, successful.events.size());
        assertEquals(10L, successful.droppedEvents);
        try { successful.events.clear(); fail("mutable events"); }
        catch (UnsupportedOperationException expected) { }
        assertEquals(0L, executor.execute(Collections.singletonList(good), CONTEXT, RAW).droppedEvents);
    }

    @Test public void diagnosticIdsAndActiveCandidateBudgetStayBounded() {
        String longId = String.join("", Collections.nCopies(129, "i"));
        IslandRuleSet.Entry mismatch = entry(longId, "/desc1", new MatchClause("scene", "equals", "other"));
        IslandRuleSet.Entry good = entry("good", "/desc2", MATCH);
        List<IslandRuleSet.Entry> maximum = new ArrayList<>(Collections.nCopies(49, mismatch));
        maximum.add(good);
        IslandRuleExecutor.Result result = executor.execute(maximum, CONTEXT, RAW);
        success(result, good, "C");
        assertNull(result.events.get(0).ruleId);
        event(result, 49, Phase.EXECUTION, EXECUTED, "$");
        List<IslandRuleSet.Entry> oversized = new AbstractList<IslandRuleSet.Entry>() {
            @Override public int size() { return 51; }
            @Override public IslandRuleSet.Entry get(int index) {
                if (index < 50) return mismatch;
                throw new AssertionError("bypassed active candidate capacity");
            }
        };
        fallback(executor.execute(oversized, CONTEXT, RAW));
    }

    @Test public void cancellationAtMatchCheckpointStopsExecution() {
        assertCancellationAtCheckpoint(Phase.MATCH);
    }

    @Test public void cancellationAtPreparationCheckpointStopsExecution() {
        assertCancellationAtCheckpoint(Phase.PREPARATION);
    }

    @Test public void cancellationAtExtractionCheckpointStopsExecution() {
        assertCancellationAtCheckpoint(Phase.EXTRACTION);
    }

    @Test public void cancellationAtPostProcessCheckpointStopsExecution() {
        assertCancellationAtCheckpoint(Phase.POST_PROCESS);
    }

    @Test public void alreadyCancelledDoesNotReadCandidateList() {
        List<IslandRuleSet.Entry> unreadable = new AbstractList<IslandRuleSet.Entry>() {
            @Override public int size() { throw new AssertionError("cancelled list was sized"); }
            @Override public IslandRuleSet.Entry get(int index) { throw new AssertionError("cancelled list was read"); }
        };
        cancelled(executor.execute(unreadable, CONTEXT, RAW, () -> true));
    }

    @Test public void cancellationBetweenRulesStopsBeforeNextListAccess() {
        IslandRuleSet.Entry disabled = make("disabled", false, "legacy", Collections.singletonList(MATCH),
                Collections.singletonList(binding("a", "/desc1", 0)), "a");
        AtomicBoolean signal = new AtomicBoolean();
        List<IslandRuleSet.Entry> entries = new AbstractList<IslandRuleSet.Entry>() {
            @Override public int size() { return 2; }
            @Override public IslandRuleSet.Entry get(int index) {
                if (index != 0) throw new AssertionError("later candidate was inspected");
                signal.set(true);
                return disabled;
            }
        };
        cancelled(executor.execute(entries, CONTEXT, RAW, signal::get));
    }

    @Test public void cancellationBeforeFallbackIsNotRuleExhaustion() {
        AtomicBoolean signal = new AtomicBoolean();
        List<IslandRuleSet.Entry> empty = new AbstractList<IslandRuleSet.Entry>() {
            @Override public int size() {
                signal.set(true);
                return 0;
            }
            @Override public IslandRuleSet.Entry get(int index) { throw new AssertionError("empty list was read"); }
        };
        cancelled(executor.execute(empty, CONTEXT, RAW, signal::get));
    }

    @Test public void cancellationAfterLastBudgetCheckpointSuppressesCompletedText() {
        IslandRuleSet.Entry first = entry("first", "/desc1", MATCH);
        AtomicInteger clockReads = new AtomicInteger();
        IslandRuleExecutor fixedClockExecutor = new IslandRuleExecutor(() -> {
            clockReads.incrementAndGet();
            return 0L;
        });
        List<IslandRuleSet.Entry> entries = withoutLaterAccess(first);
        success(fixedClockExecutor.execute(entries, CONTEXT, RAW), first, "A B");
        int lastBudgetClockRead = clockReads.getAndSet(0);
        AtomicInteger finalPolls = new AtomicInteger();
        cancelled(fixedClockExecutor.execute(entries, CONTEXT, RAW,
                () -> clockReads.get() == lastBudgetClockRead && finalPolls.incrementAndGet() >= 2));
        assertEquals(2, finalPolls.get());
    }

    private static void assertCancellationAtCheckpoint(Phase phase) {
        IslandRuleSet.Entry first = entry("first", "/desc1", MATCH);
        IslandRuleSet.Entry second = entry("second", "/desc2", MATCH);
        List<IslandRuleSet.Entry> entries = Arrays.asList(first, second);
        // 用旧超时诊断定位真实阶段，避免把每阶段内部的检查点数量写死。
        for (int checkpoint = 2; checkpoint <= 100; checkpoint++) {
            IslandRuleExecutor.Result timeout = expiringExecutor(checkpoint).execute(entries, CONTEXT, RAW);
            boolean timedOutInPhase = timeout.events.stream().anyMatch(e ->
                    e.entryIndex == 0 && e.phase == phase && e.code == R_TIMEOUT);
            if (!timedOutInPhase) continue;
            success(timeout, second, "C");
            final int cancelAt = checkpoint;
            AtomicInteger clockReads = new AtomicInteger();
            IslandRuleExecutor cancellingExecutor = new IslandRuleExecutor(() ->
                    clockReads.incrementAndGet() >= cancelAt ? IslandRuleBudget.TIMEOUT_NANOS : 0L);
            // 在同一个已到期检查点同时观察到取消，必须立即以取消结束。
            cancelled(cancellingExecutor.execute(withoutLaterAccess(first), CONTEXT, RAW,
                    () -> clockReads.get() >= cancelAt));
            assertEquals(cancelAt, clockReads.get());
            return;
        }
        fail("No budget checkpoint for " + phase);
    }

    private static List<IslandRuleSet.Entry> withoutLaterAccess(IslandRuleSet.Entry first) {
        return new AbstractList<IslandRuleSet.Entry>() {
            @Override public int size() { return 2; }
            @Override public IslandRuleSet.Entry get(int index) {
                if (index != 0) throw new AssertionError("later candidate was inspected");
                return first;
            }
        };
    }

    private static IslandRuleExecutor expiringExecutor(int expiryCall) {
        return new IslandRuleExecutor(new IslandRuleBudget.Clock() {
            private int calls;
            @Override public long nanoTime() {
                return ++calls >= expiryCall ? IslandRuleBudget.TIMEOUT_NANOS : 0L;
            }
        });
    }

    private static ExtractSpec binding(String id, String path, int replacements) {
        return new ExtractSpec(id, path, true, Collections.nCopies(replacements,
                new ExtractSpec.Replacement("never matches", "unused")));
    }

    private static IslandRuleSet.Entry entry(String id, String path, MatchClause... conditions) {
        return make(id, true, "legacy", Arrays.asList(conditions), Collections.singletonList(binding("a", path, 0)), "a");
    }

    private static IslandRuleSet.Entry make(String id, boolean enabled, String template, List<MatchClause> conditions,
            List<ExtractSpec> bindings, String ref) {
        IslandRule.Metadata meta = new IslandRule.Metadata("test.app", null, null, null, null, null, null, null,
                null, template, null, null, "ImKani", "", "2026-11-18T00:00:00Z");
        IslandRule rule = new IslandRule(1, meta, conditions, bindings, new DisplaySpec(Collections.singletonList(
                new DisplaySpec.Projection("title", ref)), "·", false));
        return new IslandRuleSet.Entry(id, 0, enabled, rule, 1);
    }

    private static IslandRuleSetLoader.Source source(String id, int priority, boolean enabled, String path) {
        String json = "{\"ruleVersion\":1,\"meta\":{\"packageName\":\"test.app\",\"templateType\":\"legacy\","
                + "\"ruleAuthor\":\"ImKani\",\"ruleDescription\":\"\",\"createdAt\":\"2026-11-18T00:00:00Z\"},"
                + "\"match\":{\"all\":[{\"field\":\"packageName\",\"op\":\"equals\",\"value\":\"test.app\"}]},"
                + "\"extract\":[{\"id\":\"a\",\"path\":\"" + path + "\",\"required\":true}],"
                + "\"display\":{\"order\":[{\"slot\":\"title\",\"ref\":\"a\"}]}}";
        byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
        return new IslandRuleSetLoader.Source(id, 0, enabled, priority, () -> new ByteArrayInputStream(utf8));
    }

    private static void success(IslandRuleExecutor.Result result, IslandRuleSet.Entry winner, String text) {
        assertEquals(IslandRuleExecutor.Status.SUCCESS, result.status);
        assertSame(winner, result.winningEntry);
        assertEquals(text, result.text);
    }

    private static void cancelled(IslandRuleExecutor.Result result) {
        assertEquals(IslandRuleExecutor.Status.CANCELLED, result.status);
        assertNull(result.winningEntry);
        assertNull(result.text);
        assertFalse(result.events.stream().anyMatch(event -> event.code == FALLBACK || event.code == EXECUTED
                || event.code == R_TIMEOUT || event.code == R_BUDGET || event.code == R_EXCEPTION));
    }

    private static void fallback(IslandRuleExecutor.Result result) {
        assertEquals(IslandRuleExecutor.Status.FALLBACK, result.status);
        assertNull(result.winningEntry);
        assertNull(result.text);
        event(result, -1, Phase.EXECUTION, FALLBACK, "$");
    }

    private static void event(IslandRuleExecutor.Result result, int index, Phase phase,
            IslandRuleSelection.Code code, String path) {
        assertTrue("Missing event: " + index + " " + phase + " " + code + " " + path,
                result.events.stream().anyMatch(event -> event.entryIndex == index && event.phase == phase
                        && event.code == code && path.equals(event.path)));
    }
}
