/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

/** C2-2 纯 JVM 选条测试，不输入通知正文或调用提取/展示实现。 */
public class IslandRuleSelectorTest {
    private final IslandRuleSelector selector = new IslandRuleSelector();
    private static String json() {
        return "{\"ruleVersion\":1,\"meta\":{\"packageName\":\"com.example.app\","
                + "\"templateType\":\"baseInfo\",\"ruleAuthor\":\"ImKani\","
                + "\"ruleDescription\":\"\",\"createdAt\":\"2026-11-18T00:00:00+08:00\"},"
                + "\"match\":{\"all\":[{\"field\":\"business\",\"op\":\"equals\",\"value\":\"train\"}]},"
                + "\"extract\":[{\"id\":\"title\",\"path\":\"/baseInfo/title\"}],"
                + "\"display\":{\"order\":[{\"slot\":\"title\",\"ref\":\"title\"}]}}";
    }
    private static IslandRule base() {
        IslandRuleCodec.DecodeResult decoded = new IslandRuleCodec().decode(json().getBytes(StandardCharsets.UTF_8));
        assertTrue(decoded.isSuccess()); return decoded.rule;
    }
    private static MatchClause clause(String field, String op, String value) {
        return new MatchClause(field, op, value);
    }
    private static IslandRuleSet.Entry entry(String id, int priority, boolean enabled, MatchClause... clauses) {
        IslandRule base = base();
        IslandRule rule = new IslandRule(1, base.meta, Arrays.asList(clauses), base.extract, base.display);
        return new IslandRuleSet.Entry(id, 0L, enabled, priority, rule, json().getBytes(StandardCharsets.UTF_8).length);
    }
    private IslandRuleSet.Entry select(IslandRuleSet.Entry entry, IslandRuleContext context) {
        return selector.select(Collections.singletonList(entry), context);
    }
    private static IslandRuleContext context(String field, String actual) {
        return new IslandRuleContext("packageName".equals(field) ? actual : null,
                "protocolVersion".equals(field) ? actual : null,
                "business".equals(field) ? actual : null, "scene".equals(field) ? actual : null);
    }
    private static IslandRuleContext train() { return new IslandRuleContext("com.example.app", "1", "train", "arrival"); }
    private static IslandRuleSetLoader.Source source(String id, boolean enabled, int priority) {
        return new IslandRuleSetLoader.Source(id, 0L, enabled, priority,
                () -> new ByteArrayInputStream(json().getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void contextExposesOnlyFourImmutableStrings() {
        Set<String> names = new HashSet<>();
        for (Field field : IslandRuleContext.class.getDeclaredFields()) {
            assertEquals(String.class, field.getType());
            assertTrue(Modifier.isPublic(field.getModifiers())); assertTrue(Modifier.isFinal(field.getModifiers()));
            names.add(field.getName());
        }
        assertEquals(new HashSet<>(Arrays.asList("packageName", "protocolVersion", "business", "scene")), names);
    }

    @Test public void eachFieldMatchesAndRejectsIndependently() {
        String[] fields = {"packageName", "protocolVersion", "business", "scene"};
        String[] values = {"com.example.app", "1", "train", "arrival"};
        for (int index = 0; index < fields.length; index++) {
            IslandRuleSet.Entry rule = entry(fields[index], 0, true, clause(fields[index], "equals", values[index]));
            assertSame(rule, select(rule, context(fields[index], values[index])));
            assertNull(select(rule, context(fields[index], "different")));
            assertNull(select(rule, context(fields[index], null)));
        }
    }

    @Test public void fourConditionsAreAndRatherThanOr() {
        IslandRuleSet.Entry rule = entry("all", 0, true,
                clause("packageName", "equals", "com.example.app"), clause("protocolVersion", "equals", "1"),
                clause("business", "equals", "train"), clause("scene", "equals", "arrival"));
        assertSame(rule, select(rule, train()));
        assertNull(select(rule, new IslandRuleContext("other.pkg", "1", "train", "arrival")));
        assertNull(select(rule, new IslandRuleContext("com.example.app", "2", "train", "arrival")));
        assertNull(select(rule, new IslandRuleContext("com.example.app", "1", "other", "arrival")));
        assertNull(select(rule, new IslandRuleContext("com.example.app", "1", "train", "other")));
        assertNull(select(rule, new IslandRuleContext("com.example.app", "1", "train", null)));
    }

    @Test public void threeOperatorsAreLiteralAndAsteriskHasNoWildcardMeaning() {
        for (String field : Arrays.asList("packageName", "business", "scene")) {
            IslandRuleSet.Entry equals = entry("equals", 0, true, clause(field, "equals", "a.*"));
            assertSame(equals, select(equals, context(field, "a.*")));
            assertNull(select(equals, context(field, "abcdef")));
            IslandRuleSet.Entry contains = entry("contains", 0, true, clause(field, "contains", "*"));
            assertSame(contains, select(contains, context(field, "before*after")));
            assertNull(select(contains, context(field, "beforeAfter")));
            IslandRuleSet.Entry starts = entry("prefix", 0, true, clause(field, "startsWith", "a.*"));
            assertSame(starts, select(starts, context(field, "a.*suffix")));
            assertNull(select(starts, context(field, "abcsuffix")));
        }
    }

    @Test public void matchingIsCaseSensitiveWithoutTrimOrUnicodeNormalization() {
        for (String op : Arrays.asList("equals", "contains", "startsWith")) {
            IslandRuleSet.Entry rule = entry(op, 0, true, clause("business", op, "Train"));
            assertSame(rule, select(rule, context("business", "Train")));
            assertNull(select(rule, context("business", "train")));
        }
        IslandRuleSet.Entry exact = entry("space", 0, true, clause("business", "equals", "train"));
        assertNull(select(exact, context("business", " train")));
        assertNull(select(exact, context("business", "train ")));
        IslandRuleSet.Entry prefix = entry("prefix", 0, true, clause("business", "startsWith", "train"));
        assertNull(select(prefix, context("business", " train")));
        IslandRuleSet.Entry composed = entry("unicode", 0, true, clause("business", "equals", "\u00e9"));
        assertNull(select(composed, context("business", "e\u0301")));
    }

    @Test public void missingValuesNeverTurnIntoTextNullOrWildcard() {
        for (String op : Arrays.asList("equals", "contains", "startsWith")) {
            IslandRuleSet.Entry rule = entry(op, 0, true, clause("business", op, "null"));
            assertNull(select(rule, context("business", null)));
            assertNull(select(rule, context("business", "")));
        }
        assertNull(selector.select(Collections.emptyList(), train()));
        assertNull(selector.select(null, train()));
        assertNull(selector.select(Collections.singletonList(entry("x", 0, true, clause("business", "equals", "train"))), null));
    }

    @Test public void onlyDeclaredClausesParticipateWithoutImplicitMetaPredicates() {
        IslandRuleSet.Entry rule = entry("oneClause", 0, true, clause("business", "equals", "train"));
        assertSame(rule, select(rule, new IslandRuleContext("different.pkg", "999", "train", null)));
        assertSame(rule, select(rule, new IslandRuleContext(null, null, "train", null)));
    }

    @Test public void firstMatchStopsBeforeTouchingLaterEntry() {
        IslandRuleSet.Entry wrong = entry("wrong", 0, true, clause("business", "equals", "car"));
        IslandRuleSet.Entry first = entry("first", 0, true, clause("business", "equals", "train"));
        List<IslandRuleSet.Entry> ordered = new AbstractList<IslandRuleSet.Entry>() {
            @Override public int size() { return 3; }
            @Override public IslandRuleSet.Entry get(int index) {
                if (index == 0) return wrong;
                if (index == 1) return first;
                throw new AssertionError("later entry must not be visited");
            }
        };
        assertSame(first, selector.select(ordered, train()));
    }

    @Test public void noMatchReturnsNullWithoutChangingInput() {
        List<IslandRuleSet.Entry> rules = Collections.singletonList(entry("wrong", 0, true, clause("business", "equals", "car")));
        assertNull(selector.select(rules, train()));
        assertEquals(1, rules.size()); assertEquals("wrong", rules.get(0).id);
    }

    @Test public void disabledAndNullEntriesDoNotParticipateEvenForEquivalentInputList() {
        IslandRuleSet.Entry disabled = entry("disabled", Integer.MAX_VALUE, false, clause("business", "equals", "train"));
        IslandRuleSet.Entry enabled = entry("enabled", 0, true, clause("business", "equals", "train"));
        assertNull(select(disabled, train()));
        assertSame(enabled, selector.select(Arrays.asList(disabled, null, enabled), train()));
    }

    @Test public void loaderPriorityOrderIsUsedWithoutSelectingDisabledHighPriority() {
        IslandRuleSetLoader.Snapshot snapshot = new IslandRuleSetLoader().load(1L, Arrays.asList(
                source("low", true, -1), source("disabled", false, Integer.MAX_VALUE), source("high", true, 10)));
        assertTrue(snapshot.failures.isEmpty());
        assertSame(snapshot.orderedActiveEntries.get(0), selector.select(snapshot.orderedActiveEntries, train()));
        assertEquals("high", selector.select(snapshot.orderedActiveEntries, train()).id);
    }

    @Test public void tiedPriorityKeepsLoaderImportOrderRatherThanNames() {
        IslandRuleSetLoader.Snapshot snapshot = new IslandRuleSetLoader().load(1L, Arrays.asList(
                source("z-first", true, 5), source("a-second", true, 5)));
        assertTrue(snapshot.failures.isEmpty());
        assertEquals("z-first", selector.select(snapshot.orderedActiveEntries, train()).id);
    }

    @Test public void selectorDoesNotResortCallerSuppliedOrder() {
        IslandRuleSet.Entry first = entry("first", -10, true, clause("business", "equals", "train"));
        IslandRuleSet.Entry second = entry("second", 100, true, clause("business", "equals", "train"));
        assertSame(first, selector.select(Arrays.asList(first, second), train()));
    }

    @Test public void singleRuleMatchingExceptionContinuesAndAllExceptionsReturnNull() {
        // 包内模型构造模拟意外无效内存条目；公开文件路径仍须通过 Codec。
        IslandRuleSet.Entry badOperator = entry("badOp", 100, true, clause("business", "regex", "train"));
        IslandRuleSet.Entry badField = entry("badField", 90, true, clause("deviceModel", "equals", "test"));
        IslandRuleSet.Entry good = entry("good", 0, true, clause("business", "equals", "train"));
        assertSame(good, selector.select(Arrays.asList(badOperator, badField, good), train()));
        assertNull(selector.select(Arrays.asList(badOperator, badField), train()));
    }

    @Test public void protocolVersionIsExactStringWithNoNumericCoercion() {
        IslandRuleSet.Entry rule = entry("protocol", 0, true, clause("protocolVersion", "equals", "1"));
        assertSame(rule, select(rule, context("protocolVersion", "1")));
        for (String different : Arrays.asList("01", "1.0", "1e0", "+1", " 1", "1 ", "001")) {
            assertNull(select(rule, context("protocolVersion", different)));
        }
        IslandRuleSet.Entry literal = entry("literal", 0, true, clause("protocolVersion", "equals", "01"));
        assertSame(literal, select(literal, context("protocolVersion", "01")));
        assertNull(select(literal, context("protocolVersion", "1")));
    }

    @Test public void protocolVersionNonEqualsOperatorsAreNotApplied() {
        for (String op : Arrays.asList("contains", "startsWith")) {
            IslandRuleSet.Entry rule = entry(op, 0, true, clause("protocolVersion", op, "1"));
            assertNull(select(rule, context("protocolVersion", "1")));
            assertNull(select(rule, context("protocolVersion", "12")));
        }
    }

    @Test public void sceneUsesContextOnlyAndHasNoMetaField() throws Exception {
        try { IslandRule.Metadata.class.getDeclaredField("scene"); fail("scene must not be metadata"); }
        catch (NoSuchFieldException expected) { }
        IslandRuleSet.Entry rule = entry("scene", 0, true, clause("scene", "equals", "arrival"));
        assertSame(rule, select(rule, new IslandRuleContext(null, null, "different", "arrival")));
        assertNull(select(rule, new IslandRuleContext(null, null, "arrival", null)));
        assertNull(select(rule, new IslandRuleContext(null, null, "arrival", "different")));
    }

    @Test public void metadataTemplateAndUnverifiedDeclarationsNeverSelectOrReject() {
        IslandRule base = base();
        IslandRule.Metadata declarations = new IslandRule.Metadata("different.pkg", "name", "tag",
                new IslandRule.SystemUiVersion("unverified", 999L), "firmware", "model", "codename",
                999, "metadataOnly", "legacy", "channel", "category", "ImKani", "description", "2026-11-18T00:00:00Z");
        IslandRule rule = new IslandRule(1, declarations,
                Collections.singletonList(clause("business", "equals", "train")), base.extract, base.display);
        IslandRuleSet.Entry entry = new IslandRuleSet.Entry("metadata", 0L, true, rule, 1024);
        assertSame(entry, select(entry, train()));
        assertNull(select(entry, new IslandRuleContext("different.pkg", "999", "metadataOnly", "arrival")));
    }

    @Test public void selectionDoesNotEvaluateExtractDisplayOrPostprocess() {
        IslandRule base = base();
        // 故意放不可提取路径、空 from 和不存在的 ref；若执行这些声明就不应成功。
        ExtractSpec extraction = new ExtractSpec("poison", "/must-not-be-read", true,
                Collections.singletonList(new ExtractSpec.Replacement("", "never")));
        DisplaySpec display = new DisplaySpec(Collections.singletonList(
                new DisplaySpec.Projection("title", "missingRef")), "\ufeff\u200b separator ", true);
        IslandRule rule = new IslandRule(1, base.meta,
                Collections.singletonList(clause("business", "equals", "train")),
                Collections.singletonList(extraction), display);
        IslandRuleSet.Entry entry = new IslandRuleSet.Entry("selectionOnly", 0L, true, rule, 1024);
        assertSame(entry, select(entry, train()));
        assertSame(extraction, rule.extract.get(0)); assertSame(display, rule.display);
        assertEquals("", extraction.replace.get(0).from);
        assertEquals("\ufeff\u200b separator ", display.separator);
    }

    @Test public void emptyMatchAndUnsupportedVersionCannotBecomeImplicitMatchAll() {
        IslandRuleSet.Entry empty = entry("empty", 0, true);
        IslandRule base = base();
        IslandRule futureRule = new IslandRule(2, base.meta,
                Collections.singletonList(clause("business", "equals", "train")), base.extract, base.display);
        IslandRuleSet.Entry future = new IslandRuleSet.Entry("future", 0L, true, futureRule, 1024);
        assertNull(select(empty, train())); assertNull(select(future, train()));
        List<MatchClause> tooMany = new ArrayList<>();
        for (int i = 0; i < 17; i++) tooMany.add(clause("business", "equals", "train"));
        IslandRule oversized = new IslandRule(1, base.meta, tooMany, base.extract, base.display);
        assertNull(select(new IslandRuleSet.Entry("tooMany", 0L, true, oversized, 1024), train()));
    }
}
