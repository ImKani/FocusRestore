/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.hyperos3.focusrestore.IslandRuleExtractor.Code.*;
import static org.junit.Assert.*;

/** C3-1 仅核对提取与共享预算；冻结时钟，不执行完整规则、IO 或后处理。 */
public class IslandRuleExtractorTest {
    private final IslandRuleExtractor extractor = new IslandRuleExtractor();
    private static final String TITLE = "/baseInfo/title";
    private static final String CONTENT = "/baseInfo/content";

    private static final class FixedClock implements IslandRuleBudget.Clock {
        long now;
        @Override public long nanoTime() { return now; }
    }
    private static final class Raw {
        final String text;
        Raw(String text) { this.text = text; }
    }
    private static IslandRuleBudget budget() {
        return new IslandRuleBudget(new FixedClock());
    }
    private static ExtractSpec binding(String id, String path, boolean required,
            ExtractSpec.Replacement... replacements) {
        return new ExtractSpec(id, path, required, Arrays.asList(replacements));
    }
    private static ExtractSpec.Replacement replacement(String from, String to) {
        return new ExtractSpec.Replacement(from, to);
    }
    private static IslandRule rule(String template, ExtractSpec... bindings) {
        return rule(template, Arrays.asList(bindings),
                Collections.singletonList(new DisplaySpec.Projection("title", bindings[0].id)));
    }
    private static IslandRule rule(String template, List<ExtractSpec> bindings,
            List<DisplaySpec.Projection> order) {
        IslandRule.Metadata metadata = new IslandRule.Metadata("test.package", null, null,
                null, null, null, null, null, null, template, null, null, "ImKani", "", "2026-11-18T00:00:00Z");
        return new IslandRule(IslandRule.CURRENT_VERSION, metadata,
                Collections.singletonList(new MatchClause("packageName", "equals", "test.package")),
                bindings, new DisplaySpec(order, "·", false));
    }
    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static Map<String, Object> tree(String path, Object leaf) {
        String[] parts = path.substring(1).split("/");
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Map<String, Object> child = new LinkedHashMap<String, Object>();
            cursor.put(parts[i], child);
            cursor = child;
        }
        cursor.put(parts[parts.length - 1], leaf);
        return root;
    }
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20 || Character.isSurrogate(c)) {
                out.append("\\u");
                String hex = Integer.toHexString(c);
                for (int j = hex.length(); j < 4; j++) out.append('0');
                out.append(hex);
            } else out.append(c);
        }
        return out.append('"').toString();
    }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Raw) return ((Raw) value).text;
        if (value instanceof String) return quote((String) value);
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Map) {
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (out.length() != 1) out.append(',');
                out.append(quote((String) entry.getKey())).append(':').append(json(entry.getValue()));
            }
            return out.append('}').toString();
        }
        throw new AssertionError("Unsupported fixture type");
    }
    private static String repeat(char character, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, character);
        return new String(chars);
    }
    private IslandRuleExtractor.Payload prepare(String raw) {
        IslandRuleExtractor.Preparation prepared = extractor.prepare(raw, budget());
        assertTrue(prepared.failure == null ? "preparation failed" : prepared.failure.code.name(), prepared.isSuccess());
        assertNotNull(prepared.payload);
        assertNull(prepared.failure);
        return prepared.payload;
    }
    private IslandRuleExtractor.Result extract(IslandRule rule, Object raw) {
        return extractor.extract(rule, prepare(json(raw)), budget());
    }
    private IslandRuleExtractor.Result text(String value, boolean required,
            ExtractSpec.Replacement... replacements) {
        return extract(rule("baseInfo", binding("a", TITLE, required, replacements)), tree(TITLE, value));
    }
    private static void failure(IslandRuleExtractor.Code expected, IslandRuleExtractor.Result result) {
        assertFalse(result.isSuccess());
        assertNotNull(result.failure);
        assertEquals(expected, result.failure.code);
        assertTrue("failed extraction must not expose partial bindings", result.bindings.isEmpty());
        assertNotNull(result.failure.path);
        assertNotNull(result.failure.reason);
    }
    private static void success(String expected, IslandRuleExtractor.Result result) {
        assertTrue(result.failure == null ? "extraction failed" : result.failure.code.name(), result.isSuccess());
        assertNull(result.failure);
        assertEquals(expected, result.bindings.get("a"));
    }
    private static void unsupported(Runnable action) {
        try { action.run(); fail("mutable result"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void allOneHundredSixCanonicalPathsReadExactLeaf() {
        assertEquals(106, IslandRulePaths.all().size());
        int checked = 0;
        for (IslandRulePaths.Entry entry : IslandRulePaths.all()) {
            IslandRuleExtractor.Result result = extract(rule(entry.templateGroup,
                    binding("a", entry.path, true)), tree(entry.path, "leaf-" + checked));
            success("leaf-" + checked, result);
            assertTrue(entry.path, result.omissions.isEmpty());
            checked++;
        }
        assertEquals(106, checked);
    }

    @Test public void everyCanonicalPathIsRejectedByAnotherTemplateGroup() {
        for (IslandRulePaths.Entry entry : IslandRulePaths.all()) {
            String wrong = "baseInfo".equals(entry.templateGroup) ? "highlightInfo" : "baseInfo";
            failure(L3_PATH_SCOPE, extract(rule(wrong, binding("a", entry.path, false)),
                    tree(entry.path, "not-readable")));
        }
    }

    @Test public void containerSelectionUsesFirstObjectInFrozenPriority() {
        Map<String, Object> root = tree(TITLE, "root");
        root.put("param_v2", tree(TITLE, "v2"));
        root.put("param_v3", tree(TITLE, "v3"));
        root.put("param_voip_v2", tree(TITLE, "voip"));
        success("v2", extract(rule("baseInfo", binding("a", TITLE, true)), root));
        root.remove("param_v2");
        success("v3", extract(rule("baseInfo", binding("a", TITLE, true)), root));
        root.remove("param_v3");
        success("voip", extract(rule("baseInfo", binding("a", TITLE, true)), root));
        root.remove("param_voip_v2");
        success("root", extract(rule("baseInfo", binding("a", TITLE, true)), root));
    }

    @Test public void containerCandidatesWithNullOrWrongTypeAreSkipped() {
        for (Object invalid : Arrays.asList(null, "text", Boolean.TRUE, new Raw("[]"), new Raw("5"))) {
            Map<String, Object> root = object("param_v2", invalid, "param_v3", tree(TITLE, "v3"));
            success("v3", extract(rule("baseInfo", binding("a", TITLE, true)), root));
        }
    }

    @Test public void selectedEmptyContainerNeverMergesAnotherContainerOrRoot() {
        Map<String, Object> root = tree(TITLE, "root");
        root.put("param_v2", object());
        root.put("param_v3", tree(TITLE, "v3"));
        failure(R_MISSING_FIELD, extract(rule("baseInfo", binding("a", TITLE, false)), root));
    }

    @Test public void mixedPathsKeepOrderAndReadOnlyTheirRegisteredContainer() {
        IslandRule model = rule("mixed", binding("a", "/title", true), binding("b", "/ticker", true),
                binding("c", "/hintInfo/title", false), binding("d", TITLE, true), binding("e", "/title", true));
        Map<String, Object> raw = object("title", "raw", "ticker", "wrong-root-ticker",
                "hintInfo", object("title", "wrong-root-hint"),
                "param_v2", object("title", "wrong-inner-title", "ticker", "selected", "baseInfo", object("title", "base")),
                "param_v3", object("hintInfo", object("title", "wrong-later-container")));
        IslandRuleBudget shared = budget();
        IslandRuleExtractor.Result result = extractor.extract(model, prepare(json(raw)), shared);
        assertTrue(result.isSuccess());
        assertEquals(Arrays.asList("raw", "selected", "base", "raw"),
                new java.util.ArrayList<>(result.bindings.values()));
        assertEquals(1, result.omissions.size());
        assertEquals("$.extract[2].path", result.omissions.get(0).path);
        assertEquals(7, shared.fieldReads());
        assertEquals(0, shared.stringOperations());
    }

    @Test public void mixedMissingGroupAndWrongGroupTypeAreBindingFailures() {
        for (boolean required : Arrays.asList(false, true)) {
            IslandRule model = rule("mixed", binding("a", "/title", true), binding("b", TITLE, required));
            for (Object value : Arrays.asList(null, "wrong")) {
                IslandRuleExtractor.Result result = extract(model, object("title", "kept", "baseInfo", value));
                IslandRuleExtractor.Code expected = value == null ? R_MISSING_FIELD : R_VALUE_TYPE;
                if (required) failure(expected, result);
                else {
                    success("kept", result);
                    assertEquals(expected, result.omissions.get(0).code);
                }
            }
        }
    }

    @Test public void mixedCannotBypassPathScopeOrResetReadBudgetAcrossGroups() {
        failure(L3_PATH_SCOPE, extract(rule("mixed", binding("a", "/paramIsland/title", false)), object()));
        IslandRule model = rule("mixed", binding("a", "/title", true), binding("b", TITLE, false));
        IslandRuleBudget shared = budget();
        for (int i = 0; i < 98; i++) shared.readField();
        failure(R_BUDGET, extractor.extract(model, prepare(json(object("title", "raw", "param_v2", tree(TITLE, "v2")))), shared));
        assertEquals(100, shared.fieldReads());
    }

    @Test public void legacyAlwaysUsesRawRootWithoutContainerReads() {
        Map<String, Object> root = object("title", "root", "param_v2", object("title", "nested"));
        IslandRuleBudget shared = budget();
        IslandRuleExtractor.Result result = extractor.extract(rule("legacy", binding("a", "/title", true)),
                prepare(json(root)), shared);
        success("root", result);
        assertEquals(1, shared.fieldReads());
    }

    @Test public void fallbackUsesSelectedContainerWithoutFallbackGroupObject() {
        Map<String, Object> root = object("ticker", "root", "param_v2", object("ticker", "nested"));
        IslandRuleBudget shared = budget();
        success("nested", extractor.extract(rule("fallback", binding("a", "/ticker", true)),
                prepare(json(root)), shared));
        assertEquals(2, shared.fieldReads());
        IslandRuleExtractor.Result absent = extract(rule("fallback", binding("a", "/ticker", false)), object());
        assertTrue(absent.isSuccess());
        assertTrue(absent.bindings.isEmpty());
        assertEquals(1, absent.omissions.size());
    }

    @Test public void groupFirstSegmentIsChargedOnceAndReusedOnlyForFirstBinding() {
        IslandRuleBudget shared = budget();
        IslandRule model = rule("baseInfo", binding("a", TITLE, true), binding("b", CONTENT, true));
        IslandRuleExtractor.Result result = extractor.extract(model,
                prepare(json(object("baseInfo", object("title", "A", "content", "B")))), shared);
        success("A", result);
        assertEquals("B", result.bindings.get("b"));
        assertEquals(7, shared.fieldReads());
        assertEquals(0, shared.stringOperations());
    }

    @Test public void repeatedDeepBindingReadsAreNotMemoizedAcrossBindings() {
        String path = "/param_island/bigIslandArea/imageTextInfoLeft/textInfo/title";
        IslandRuleBudget shared = budget();
        success("A", extractor.extract(rule("param_island", binding("a", path, true), binding("b", path, true)),
                prepare(json(tree(path, "A"))), shared));
        assertEquals(13, shared.fieldReads());
    }

    @Test public void requiredMissingDiscardsBindingsAlreadyProduced() {
        IslandRule model = rule("baseInfo", binding("a", TITLE, true), binding("b", CONTENT, true));
        failure(R_MISSING_FIELD, extract(model, tree(TITLE, "earlier-success")));
    }

    @Test public void optionalMissingIsAnOmissionAndOtherBindingsSurvive() {
        IslandRule model = rule("baseInfo", binding("a", CONTENT, false), binding("b", TITLE, true));
        IslandRuleExtractor.Result result = extract(model, tree(TITLE, "kept"));
        assertTrue(result.isSuccess());
        assertFalse(result.bindings.containsKey("a"));
        assertEquals("kept", result.bindings.get("b"));
        assertEquals(1, result.omissions.size());
        assertEquals(R_MISSING_FIELD, result.omissions.get(0).code);
    }

    @Test public void requiredNullFailsWithoutLiteralNullText() {
        failure(R_MISSING_FIELD, extract(rule("baseInfo", binding("a", TITLE, true)), tree(TITLE, null)));
    }

    @Test public void optionalNullIsOmittedAndCanProduceSuccessfulEmptyBindings() {
        IslandRuleExtractor.Result result = extract(rule("baseInfo", binding("a", TITLE, false)), tree(TITLE, null));
        assertTrue(result.isSuccess());
        assertTrue(result.bindings.isEmpty());
        assertEquals(R_MISSING_FIELD, result.omissions.get(0).code);
    }

    @Test public void objectAndArrayLeavesFailRequiredAndOmitOptional() {
        for (Object invalid : Arrays.asList(object("secret", "value"), new Raw("[1,2]"))) {
            failure(R_VALUE_TYPE, extract(rule("baseInfo", binding("a", TITLE, true)), tree(TITLE, invalid)));
            IslandRuleExtractor.Result optional = extract(rule("baseInfo", binding("a", TITLE, false)), tree(TITLE, invalid));
            assertTrue(optional.isSuccess());
            assertTrue(optional.bindings.isEmpty());
            assertEquals(R_VALUE_TYPE, optional.omissions.get(0).code);
        }
    }

    @Test public void wrongIntermediateTypeDiffersFromMissingOrNullMember() {
        String path = "/progressInfo/progressInfo/title";
        for (Object wrong : Arrays.asList("value", Boolean.FALSE, new Raw("[]"), new Raw("12"))) {
            Map<String, Object> raw = object("progressInfo", object("progressInfo", wrong));
            failure(R_VALUE_TYPE, extract(rule("progressInfo", binding("a", path, true)), raw));
            IslandRuleExtractor.Result result = extract(rule("progressInfo", binding("a", path, false)), raw);
            assertTrue(result.isSuccess());
            assertEquals(R_VALUE_TYPE, result.omissions.get(0).code);
        }
        for (Map<String, Object> body : Arrays.asList(object(), object("progressInfo", null))) {
            IslandRuleExtractor.Result result = extract(rule("progressInfo", binding("a", path, false)),
                    object("progressInfo", body));
            assertTrue(result.isSuccess());
            assertEquals(R_MISSING_FIELD, result.omissions.get(0).code);
        }
    }

    @Test public void absentNullOrWrongGroupEntryFailsEvenWhenEveryBindingOptional() {
        IslandRule model = rule("baseInfo", binding("a", TITLE, false));
        failure(R_MISSING_FIELD, extract(model, object()));
        failure(R_MISSING_FIELD, extract(model, object("baseInfo", null)));
        for (Object wrong : Arrays.asList("x", Boolean.FALSE, new Raw("[]"), new Raw("7")))
            failure(R_VALUE_TYPE, extract(model, object("baseInfo", wrong)));
    }

    @Test public void legitimateEmptyStringIsAValidBindingAndDoesNotTriggerEmptyResult() {
        success("", text("", true));
        success("", text("", false));
    }

    @Test public void extractorDoesNotPerformDisplaySanitationOrDedupe() {
        String input = " \tA\n\u200b\ufeff  ";
        IslandRule model = rule("baseInfo", binding("a", TITLE, true), binding("b", CONTENT, true));
        IslandRuleExtractor.Result result = extract(model, object("baseInfo", object("title", input, "content", input)));
        success(input, result);
        assertEquals(input, result.bindings.get("b"));
    }

    @Test public void thirtyTwoBindingsReachExactReadHundredAndNextReadFails() {
        ExtractSpec[] bindings = new ExtractSpec[32];
        for (int i = 0; i < bindings.length; i++) bindings[i] = binding("b" + i, TITLE, true);
        IslandRule model = rule("baseInfo", bindings);
        IslandRuleExtractor.Payload payload = prepare(json(tree(TITLE, "x")));
        IslandRuleBudget exact = budget();
        for (int i = 0; i < 33; i++) exact.readField();
        IslandRuleExtractor.Result accepted = extractor.extract(model, payload, exact);
        assertTrue(accepted.isSuccess());
        assertEquals(32, accepted.bindings.size());
        assertEquals(100, exact.fieldReads());
        IslandRuleBudget exceeded = budget();
        for (int i = 0; i < 34; i++) exceeded.readField();
        failure(R_BUDGET, extractor.extract(model, payload, exceeded));
        assertEquals(100, exceeded.fieldReads());
        String deepPath = "/param_island/bigIslandArea/imageTextInfoLeft/textInfo/title";
        for (int i = 0; i < bindings.length; i++) bindings[i] = binding("b" + i, deepPath, false);
        IslandRuleBudget deep = budget();
        deep.readField();
        failure(R_BUDGET, extractor.extract(rule("param_island", bindings), prepare(json(tree(deepPath, "x"))), deep));
        assertEquals(100, deep.fieldReads());
    }

    @Test public void optionalBindingCannotSuppressFieldReadBudgetFailure() {
        IslandRuleBudget shared = budget();
        for (int i = 0; i < 100; i++) shared.readField();
        failure(R_BUDGET, extractor.extract(rule("baseInfo", binding("a", TITLE, false)),
                prepare(json(tree(TITLE, "x"))), shared));
        assertEquals(100, shared.fieldReads());
    }

    @Test public void replacementTenAndElevenOperationsShareBudgetAcrossBindings() {
        ExtractSpec.Replacement[] eight = new ExtractSpec.Replacement[8];
        Arrays.fill(eight, replacement("absent", "still-absent"));
        ExtractSpec first = binding("a", TITLE, true, eight);
        IslandRuleBudget ten = budget();
        IslandRuleExtractor.Payload payload = prepare(json(object("baseInfo", object("title", "A", "content", "B"))));
        IslandRuleExtractor.Result result = extractor.extract(rule("baseInfo", first,
                binding("b", CONTENT, true, replacement("x", "y"), replacement("x", "y"))), payload, ten);
        assertTrue(result.isSuccess());
        assertEquals(10, ten.stringOperations());
        IslandRuleBudget eleven = budget();
        failure(R_BUDGET, extractor.extract(rule("baseInfo", first, binding("b", CONTENT, false,
                replacement("x", "y"), replacement("x", "y"), replacement("x", "y"))), payload, eleven));
        assertEquals(10, eleven.stringOperations());
    }

    @Test public void extractionCannotResetPreviouslyConsumedStringOperations() {
        IslandRuleBudget shared = budget();
        for (int i = 0; i < 10; i++) shared.stringOperation();
        failure(R_BUDGET, extractor.extract(rule("baseInfo", binding("a", TITLE, true, replacement("z", "q"))),
                prepare(json(tree(TITLE, "a"))), shared));
        assertEquals(10, shared.stringOperations());
    }

    @Test public void unmodifiedBindingAccepts4096AndRejects4097WithoutTruncation() {
        success(repeat('a', 4096), text(repeat('a', 4096), true));
        failure(R_BUDGET, text(repeat('a', 4097), true));
        failure(R_BUDGET, text(repeat('a', 4097), false));
    }

    @Test public void literalExpansionAcceptsExact4096ButRejectsNextIntermediateSize() {
        String replacement = repeat('b', 128);
        success(repeat('b', 4096), text(repeat('a', 32), true, replacement("a", replacement)));
        failure(R_BUDGET, text(repeat('a', 33), false, replacement("a", replacement)));
    }

    @Test public void expansionThenShrinkStillFailsAtFirstOversizedIntermediate() {
        failure(R_BUDGET, text(repeat('a', 33), true,
                replacement("a", repeat('b', 128)), replacement("b", "")));
    }

    @Test public void longOriginalLeafMayBeShortenedByFirstReplacement() {
        success("a", text(repeat('a', 4097), true, replacement("aa", "")));
        success("", text(repeat('a', 5000), true, replacement("a", "")));
    }

    @Test public void replacementIsLiteralAndNonOverlapping() {
        success("ba", text("aaa", true, replacement("aa", "b")));
        success("[$]", text("a.b$", true, replacement("a.b$", "[$]")));
    }

    @Test public void replacementDoesNotRescanInsertedTextWithinOneOperation() {
        success("aa", text("a", true, replacement("a", "aa")));
    }

    @Test public void replacementListOrderIsAppliedExactlyOncePerPair() {
        success("c", text("a", true, replacement("a", "b"), replacement("b", "c")));
        success("b", text("a", true, replacement("b", "c"), replacement("a", "b")));
    }

    @Test public void moreThanEightReplacementsInOneBindingIsInvalidModel() {
        ExtractSpec.Replacement[] nine = new ExtractSpec.Replacement[9];
        Arrays.fill(nine, replacement("x", "y"));
        failure(L3_QUOTA, text("a", true, nine));
    }

    @Test public void numericLeavesPreserveExactTokensWithoutFloatingPointConversion() {
        String[] tokens = {"1.0", "1e0", "-0", "1.00000000000000000001", "1e999999999999999999999999", "-2.5E-4000"};
        for (String token : tokens) success(token, extract(rule("baseInfo", binding("a", TITLE, true)), tree(TITLE, new Raw(token))));
        String giantToken = "1e" + repeat('9', 4094);
        success(giantToken, extract(rule("baseInfo", binding("a", TITLE, true)), tree(TITLE, new Raw(giantToken))));
        failure(R_BUDGET, extract(rule("baseInfo", binding("a", TITLE, true)), tree(TITLE, new Raw(giantToken + "9"))));
    }

    @Test public void booleanLeavesUseLowerCaseJsonText() {
        success("true", extract(rule("baseInfo", binding("a", TITLE, true)), tree(TITLE, Boolean.TRUE)));
        success("false", extract(rule("baseInfo", binding("a", TITLE, true)), tree(TITLE, Boolean.FALSE)));
    }

    @Test public void invalidSurrogateIsRejectedBeforeReplacementEvenForOptional() {
        for (String invalid : Arrays.asList("\ud800", "\udc00", "x\ud800y", "\udc00\ud800")) {
            for (boolean required : new boolean[] {true, false}) {
                IslandRuleBudget shared = budget();
                IslandRule model = rule("baseInfo", binding("a", TITLE, required, replacement("x", "")));
                failure(R_VALUE_TYPE, extractor.extract(model, prepare(json(tree(TITLE, invalid))), shared));
                assertEquals("leaf rejection must precede any replacement", 0, shared.stringOperations());
            }
        }
    }

    @Test public void validSupplementaryPairCountsAsTwoUtf16Units() {
        String face = "\ud83d\ude00";
        success(repeat('a', 4094) + face, text(repeat('a', 4094) + face, true));
        failure(R_BUDGET, text(repeat('a', 4095) + face, true));
    }

    @Test public void surrogateAlignmentCannotSkipUnicodeScanTimeChecks() {
        String face = "\ud83d\ude00";
        StringBuilder longValue = new StringBuilder(repeat('a', 255)).append(face);
        for (int i = 0; i < 24; i++) longValue.append(repeat('a', 254)).append(face);
        IslandRuleExtractor.Payload payload = prepare(json(tree(TITLE, longValue.toString())));
        IslandRuleBudget controlled = new IslandRuleBudget(new IslandRuleBudget.Clock() {
            int leafUnicodeChecks;
            @Override public long nanoTime() {
                boolean unicode = false;
                boolean modelValidation = false;
                for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                    if (!IslandRuleExtractor.class.getName().equals(frame.getClassName())) continue;
                    if ("requireUnicode".equals(frame.getMethodName())) unicode = true;
                    if ("validate".equals(frame.getMethodName())) modelValidation = true;
                }
                if (unicode && !modelValidation && ++leafUnicodeChecks >= 2) return 50000000L;
                return 0L;
            }
        });
        failure(R_TIMEOUT, extractor.extract(rule("baseInfo", binding("a", TITLE, true)), payload, controlled));
    }

    @Test public void elapsedExactlyFiftyMillisecondsTimesOutWithoutMachineTiming() {
        IslandRule model = rule("baseInfo", binding("a", TITLE, true));
        IslandRuleExtractor.Payload payload = prepare(json(tree(TITLE, "x")));
        FixedClock clock = new FixedClock();
        IslandRuleBudget shared = new IslandRuleBudget(clock);
        clock.now = 49999999L;
        success("x", extractor.extract(model, payload, shared));
        clock.now = 50000000L;
        failure(R_TIMEOUT, extractor.extract(model, payload, shared));
    }

    @Test public void optionalBindingCannotSuppressTimeout() {
        FixedClock clock = new FixedClock();
        IslandRuleBudget shared = new IslandRuleBudget(clock);
        clock.now = 50000000L;
        failure(R_TIMEOUT, extractor.extract(rule("baseInfo", binding("a", TITLE, false)),
                prepare(json(tree(TITLE, "x"))), shared));
        final IslandRuleBudget[] active = new IslandRuleBudget[1];
        IslandRuleBudget duringReplacement = new IslandRuleBudget(new IslandRuleBudget.Clock() {
            int replacementChecks;
            @Override public long nanoTime() {
                if (active[0] != null && active[0].stringOperations() != 0)
                    return ++replacementChecks >= 4 ? 50000000L : 0L;
                return 0L;
            }
        });
        active[0] = duringReplacement;
        failure(R_TIMEOUT, extractor.extract(rule("baseInfo", binding("a", TITLE, false, replacement("z", "q"))),
                prepare(json(tree(TITLE, repeat('a', 4096)))), duringReplacement));
        assertEquals(1, duringReplacement.stringOperations());
    }

    @Test public void ordinaryClockExceptionIsStructuredWithoutLeakingItsMessage() {
        final boolean[] broken = {false};
        IslandRuleBudget shared = new IslandRuleBudget(new IslandRuleBudget.Clock() {
            @Override public long nanoTime() {
                if (broken[0]) throw new IllegalStateException("private-input-must-not-leak");
                return 0L;
            }
        });
        IslandRuleExtractor.Payload payload = prepare(json(tree(TITLE, "A")));
        broken[0] = true;
        IslandRuleExtractor.Result result = extractor.extract(rule("baseInfo", binding("a", TITLE, true)), payload, shared);
        failure(R_EXCEPTION, result);
        assertFalse(result.failure.reason.contains("private-input"));
        IslandRuleExtractor.Preparation failedPreparation = extractor.prepare("{}", shared);
        assertFalse(failedPreparation.isSuccess());
        assertNull(failedPreparation.payload);
        assertEquals(R_EXCEPTION, failedPreparation.failure.code);
        assertFalse(failedPreparation.failure.reason.contains("private-input"));
    }

    @Test public void preparationChecksControlledTimeInsideIterativeParsing() {
        IslandRuleBudget shared = new IslandRuleBudget(new IslandRuleBudget.Clock() {
            long calls;
            @Override public long nanoTime() { return calls++ * 1000000L; }
        });
        IslandRuleExtractor.Preparation prepared = extractor.prepare(json(tree(TITLE, repeat('x', 20000))), shared);
        assertFalse(prepared.isSuccess());
        assertNull(prepared.payload);
        assertEquals(R_TIMEOUT, prepared.failure.code);
        assertEquals(0, shared.fieldReads());
        assertEquals(0, shared.stringOperations());
    }

    @Test public void preparationDoesNotConsumeFieldOrStringCounters() {
        IslandRuleBudget shared = budget();
        shared.readField(); shared.stringOperation();
        assertTrue(extractor.prepare(json(tree(TITLE, "x")), shared).isSuccess());
        assertEquals(1, shared.fieldReads());
        assertEquals(1, shared.stringOperations());
    }

    @Test public void payloadCharacterLimitIsCheckedBeforeParsing() {
        String prefix = "{\"unused\":\"";
        String exact = prefix + repeat('x', 262144 - prefix.length() - 2) + "\"}";
        assertEquals(262144, exact.length());
        assertTrue(extractor.prepare(exact, budget()).isSuccess());
        IslandRuleExtractor.Preparation failed = extractor.prepare(exact + " ", budget());
        assertFalse(failed.isSuccess());
        assertEquals(R_INPUT_LIMIT, failed.failure.code);
        assertNull(failed.payload);
    }

    @Test public void payloadUtf8LimitIsIndependentFromCharacterLimit() {
        String prefix = "{\"unused\":\"";
        int chinese = 174000;
        int filler = 524288 - prefix.length() - 2 - chinese * 3;
        String exact = prefix + repeat('中', chinese) + repeat('x', filler) + "\"}";
        assertTrue(exact.length() < 262144);
        assertTrue(extractor.prepare(exact, budget()).isSuccess());
        String overflow = exact.substring(0, exact.length() - 2) + "x\"}";
        assertTrue(overflow.length() < 262144);
        IslandRuleExtractor.Preparation failed = extractor.prepare(overflow, budget());
        assertFalse(failed.isSuccess());
        assertEquals(R_INPUT_LIMIT, failed.failure.code);
    }

    @Test public void preparationRejectsMissingMalformedDuplicateAndNonObjectRoots() {
        for (String raw : Arrays.asList(null, "")) {
            IslandRuleExtractor.Preparation result = extractor.prepare(raw, budget());
            assertFalse(result.isSuccess());
            assertNull(result.payload);
            assertEquals(R_MISSING_FIELD, result.failure.code);
        }
        for (String raw : Arrays.asList(" ", "{", "{\"x\":1,\"x\":2}", "{\"x\":01}", "NaN", "{} trailing",
                "[]", "null", "true", "1", "\"text\"")) {
            IslandRuleExtractor.Preparation result = extractor.prepare(raw, budget());
            assertFalse(raw, result.isSuccess());
            assertNull(result.payload);
            assertEquals(R_VALUE_TYPE, result.failure.code);
        }
    }

    @Test public void deeplyNestedUnknownPayloadRemainsIterativeAndDoesNotUseRuleDepthEight() {
        String raw = "{\"baseInfo\":{\"title\":\"kept\"},\"unknown\":"
                + repeat('[', 12000) + "0" + repeat(']', 12000) + "}";
        IslandRuleExtractor.Payload payload = prepare(raw);
        success("kept", extractor.extract(rule("baseInfo", binding("a", TITLE, true)), payload, budget()));
    }

    @Test public void duplicateBindingIdsAreRejectedBeforeExposingAnyResult() {
        failure(L3_REFERENCE, extract(rule("baseInfo", binding("a", TITLE, true), binding("a", CONTENT, false)),
                object("baseInfo", object("title", "A", "content", "B"))));
    }

    @Test public void danglingDisplayReferenceIsRejectedEvenThoughProjectionIsNotExecuted() {
        IslandRule model = rule("baseInfo", Collections.singletonList(binding("a", TITLE, true)),
                Collections.singletonList(new DisplaySpec.Projection("title", "missing")));
        failure(L3_REFERENCE, extract(model, tree(TITLE, "A")));
    }

    @Test public void oversizedDisplayReferenceIsRejectedAtFiniteModelBoundary() {
        IslandRule model = rule("baseInfo", Collections.singletonList(binding("a", TITLE, true)),
                Collections.singletonList(new DisplaySpec.Projection("title", repeat('a', 20000))));
        failure(L3_REFERENCE, extract(model, tree(TITLE, "A")));
    }

    @Test public void forbiddenDisplaySlotsAreRejectedByDefensiveModelBoundary() {
        for (String slot : Arrays.asList("icon", "timer", "html", "unknown")) {
            IslandRule model = rule("baseInfo", Collections.singletonList(binding("a", TITLE, true)),
                    Collections.singletonList(new DisplaySpec.Projection(slot, "a")));
            failure(L3_FORBIDDEN_CAPABILITY, extract(model, tree(TITLE, "A")));
        }
    }

    @Test public void aliasesUnknownPathsAndOverDepthNeverGainReadPermission() {
        String[] paths = {"/paramIsland/title", "/baseInfo/secret", "/baseInfo/*", "/baseInfo/../title",
                "/baseInfo/0/title", "/a/b/c/d/e/f/g/h/i", "/baseInfo/title/extra"};
        for (String path : paths) failure(L3_PATH_SCOPE, extract(rule("baseInfo", binding("a", path, false)), tree(TITLE, "A")));
    }

    @Test public void moreThanThirtyTwoBindingsIsRejectedInsteadOfPartlyExecuted() {
        ExtractSpec[] bindings = new ExtractSpec[33];
        for (int i = 0; i < bindings.length; i++) bindings[i] = binding("b" + i, TITLE, false);
        failure(L3_QUOTA, extract(rule("baseInfo", bindings), tree(TITLE, "A")));
    }

    @Test public void bindingsAndOmissionsAreImmutableSnapshots() {
        IslandRuleExtractor.Result result = extract(rule("baseInfo", binding("a", TITLE, true), binding("b", CONTENT, false)), tree(TITLE, "A"));
        assertTrue(result.isSuccess());
        unsupported(() -> result.bindings.put("x", "y"));
        unsupported(() -> result.bindings.clear());
        unsupported(() -> result.omissions.clear());
    }

    @Test public void thirtyTwoOptionalOmissionsRemainBoundedAndSuccessful() {
        ExtractSpec[] bindings = new ExtractSpec[32];
        for (int i = 0; i < bindings.length; i++) bindings[i] = binding("b" + i, TITLE, false);
        IslandRuleExtractor.Result result = extract(rule("baseInfo", bindings), object("baseInfo", object()));
        assertTrue(result.isSuccess());
        assertTrue(result.bindings.isEmpty());
        assertEquals(32, result.omissions.size());
        for (IslandRuleExtractor.Problem omission : result.omissions) {
            assertEquals(R_MISSING_FIELD, omission.code);
            assertNotNull(omission.path);
            assertNotNull(omission.reason);
        }
    }
}
