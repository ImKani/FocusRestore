/* SPDX-License-Identifier: GPL-3.0-only
 * ImKani / FocusRestore / https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import com.hyperos3.focusrestore.IslandRuleContextAdapter.Code;
import com.hyperos3.focusrestore.IslandRuleContextAdapter.Result;

/** D1 裁决：原始精确整数 -> 有界十进制 Context；不会执行提取/展示。 */
public class IslandD1ContextTest {
    private final IslandRuleContextAdapter adapter = new IslandRuleContextAdapter();
    private Result raw(String token) {
        return adapter.adapt("notify.pkg", "{\"protocol\":" + token + ",\"business\":\"train\",\"scene\":\"gate\"}");
    }
    private void valid(String expected, String... tokens) {
        for (String token : tokens) {
            Result r = raw(token); assertEquals(token, expected, r.context.protocolVersion);
            assertTrue(r.findings.isEmpty());
        }
    }
    private void invalid(Code code, String... tokens) {
        for (String token : tokens) {
            Result r = raw(token); assertNull(token, r.context.protocolVersion);
            assertEquals(1, r.findings.size()); assertEquals(code, r.findings.get(0).code);
            assertEquals("$.protocol", r.findings.get(0).path);
            assertEquals("train", r.context.business); assertEquals("gate", r.context.scene);
        }
    }
    private static String repeat(char ch, int count) { char[] chars = new char[count]; Arrays.fill(chars, ch); return new String(chars); }
    private static String json(String value) {
        return "{\"ruleVersion\":1,\"meta\":{\"packageName\":\"other.pkg\",\"templateType\":\"baseInfo\","
                + "\"ruleAuthor\":\"ImKani\",\"ruleDescription\":\"\",\"createdAt\":\"2026-11-18T00:00:00Z\"},"
                + "\"match\":{\"all\":[{\"field\":\"protocolVersion\",\"op\":\"equals\",\"value\":" + value + "}]},"
                + "\"extract\":[{\"id\":\"t\",\"path\":\"/baseInfo/title\"}],\"display\":{\"order\":[{\"slot\":\"title\",\"ref\":\"t\"}]}}";
    }
    private static IslandRuleSet.Entry entry(String value) {
        IslandRuleCodec.DecodeResult decoded = new IslandRuleCodec().decode(json(value).getBytes(StandardCharsets.UTF_8));
        assertTrue(decoded.isSuccess());
        return new IslandRuleSet.Entry("rule", 0, true, decoded.rule, decoded.utf8Bytes);
    }
    @Test public void acceptedIntegerFormsNormalizePrecisely() { valid("1", "1", "1.0", "1.000", "1e0", "1E+0", "100e-2", "0.01e2"); }
    @Test public void signedAndExponentZeroAreExactlyZero() { valid("0", "0", "-0", "-0.0", "0e99999999999999999999", "-0e-99999999999999999999"); }
    @Test public void intMaximumEquivalentFormsAreAccepted() { valid("2147483647", "2147483647", "2147483647.0", "2.147483647e9", "214748364700e-2"); }
    @Test public void intMaximumPlusOneAndHugeExponentsAreOutOfRange() { invalid(Code.PROTOCOL_RANGE, "2147483648", "2147483648.0", "2.147483648e9", "1e9999999999999999999999"); }
    @Test public void preciseFractionsNeverRoundIntoIntegerOrMaximum() {
        invalid(Code.PROTOCOL_NON_INTEGER, "1.00000000000000000001", "2147483647.000000000000001", "2147483646.999999999999999", "1e-9999999999999999999", "0.1");
    }
    @Test public void negativesAreNotConvertedOrClamped() { invalid(Code.PROTOCOL_NEGATIVE, "-1", "-0.01", "-2147483648", "-1e99999999"); }
    @Test public void numericStringBooleanAndContainersAreNotNumbers() { invalid(Code.FIELD_TYPE, "\"1\"", "true", "false", "{}", "[]"); }
    @Test public void nullAndMissingHaveDifferentFindingsAndDoNotFabricateZero() {
        invalid(Code.FIELD_NULL, "null");
        Result missing = adapter.adapt("pkg", "{\"business\":\"train\",\"scene\":\"gate\"}");
        assertNull(missing.context.protocolVersion); assertEquals(Code.FIELD_MISSING, missing.findings.get(0).code);
    }
    @Test public void packageComesOnlyFromCallerEvenWhenRootAndMetaHaveOthers() {
        Result r = adapter.adapt("  caller.Pkg  ", "{\"packageName\":\"root\",\"meta\":{\"packageName\":\"meta\"},\"protocol\":1,\"business\":\" a \",\"scene\":\"X\"}");
        assertEquals("  caller.Pkg  ", r.context.packageName); assertEquals(" a ", r.context.business); assertEquals("X", r.context.scene);
    }
    @Test public void absentPackageDoesNotUsePayloadOrRuleMeta() {
        Result r = adapter.adapt(null, "{\"packageName\":\"root\",\"protocol\":1,\"business\":\"a\",\"scene\":\"b\"}");
        assertNull(r.context.packageName); assertEquals("1", r.context.protocolVersion);
        assertEquals(Code.FIELD_MISSING, r.findings.get(0).code); assertEquals("notification.packageName", r.findings.get(0).path);
    }
    @Test public void nestedAndMetaFieldsNeverFillMissingRoots() {
        Result r = adapter.adapt("p", "{\"meta\":{\"protocolVersion\":1,\"business\":\"train\"},\"param_v2\":{\"protocol\":1,\"business\":\"train\",\"scene\":\"gate\"}}");
        assertNull(r.context.protocolVersion); assertNull(r.context.business); assertNull(r.context.scene);
        assertEquals(3, r.findings.size());
    }
    @Test public void invalidProtocolDoesNotEraseOtherValidFieldsOrRequireProtocolPredicate() {
        Result r = raw("2147483648");
        IslandRuleSet.Entry protocol = entry("\"1\"");
        IslandRule base = protocol.rule;
        IslandRule businessRule = new IslandRule(1, base.meta,
                Collections.singletonList(new MatchClause("business", "equals", "train")), base.extract, base.display);
        IslandRuleSet.Entry business = new IslandRuleSet.Entry("business", 0, true, businessRule, 100);
        assertSame(business, new IslandRuleSelector().select(Arrays.asList(protocol, business), r.context));
    }
    @Test public void businessAndSceneRequireRealStringsWithoutTrimOrToString() {
        for (String type : Arrays.asList("1", "true", "{}", "[]")) {
            Result r = adapter.adapt("p", "{\"protocol\":1,\"business\":" + type + ",\"scene\":" + type + "}");
            assertEquals("1", r.context.protocolVersion); assertNull(r.context.business); assertNull(r.context.scene);
            assertEquals(2, r.findings.size()); assertEquals(Code.FIELD_TYPE, r.findings.get(0).code);
        }
        Result empty = adapter.adapt("p", "{\"protocol\":1,\"business\":\"\",\"scene\":\" \"}");
        assertEquals("", empty.context.business); assertEquals(" ", empty.context.scene);
    }
    @Test public void ordinaryUnknownPayloadKeysAndLargeArraysArePermitted() {
        String many = repeat('0', 1);
        StringBuilder array = new StringBuilder();
        for (int i = 0; i < 100; i++) { if (i > 0) array.append(','); array.append(many); }
        Result r = adapter.adapt("p", "{\"protocol\":1,\"business\":\"train\",\"scene\":\"gate\",\"script\":{\"arbitrary\":[" + array + "]}}");
        assertEquals("1", r.context.protocolVersion); assertTrue(r.findings.isEmpty());
    }
    @Test public void duplicateKeysAndMalformedDocumentsAreRejectedAsWholePayload() {
        for (String text : Arrays.asList("{\"protocol\":1,\"protocol\":2}", "{\"x\":1,\"\\u0078\":2}", "{\"protocol\":01}", "{\"protocol\":1,}", "{", "NaN")) {
            Result r = adapter.adapt("p", text); assertEquals(Code.INVALID_JSON, r.findings.get(0).code);
            assertEquals("p", r.context.packageName); assertNull(r.context.protocolVersion);
        }
    }
    @Test public void rootMustBeObjectAndNoRawIsDifferentFromInvalidSyntax() {
        for (String text : Arrays.asList("[]", "1", "null", "\"x\""))
            assertEquals(Code.ROOT_TYPE, adapter.adapt("p", text).findings.get(0).code);
        assertEquals(Code.INPUT_MISSING, adapter.adapt("p", null).findings.get(0).code);
        assertEquals(Code.INPUT_MISSING, adapter.adapt("p", "").findings.get(0).code);
        assertEquals(Code.INVALID_JSON, adapter.adapt("p", "  ").findings.get(0).code);
    }
    @Test public void payloadCharacterLimitIsInclusiveAndCheckedBeforeTreeConstruction() {
        String prefix = "{\"protocol\":1,\"business\":\"t\",\"scene\":\"s\",\"ignored\":\"";
        String at = prefix + repeat('a', InputLimits.MAX_PAYLOAD_CHARS - prefix.length() - 2) + "\"}";
        assertEquals("1", adapter.adapt("p", at).context.protocolVersion);
        assertEquals(Code.INPUT_CAPACITY, adapter.adapt("p", at + " ").findings.get(0).code);
    }
    @Test public void payloadUtf8LimitCountsActualBytesIndependently() {
        String prefix = "{\"protocol\":1,\"business\":\"t\",\"scene\":\"s\",\"ignored\":\"";
        int room = InputLimits.MAX_PAYLOAD_UTF8_BYTES - prefix.length() - 2;
        String at = prefix + repeat('中', room / 3) + repeat('a', room % 3) + "\"}";
        assertTrue(at.length() < InputLimits.MAX_PAYLOAD_CHARS);
        assertEquals("1", adapter.adapt("p", at).context.protocolVersion);
        assertEquals(Code.INPUT_CAPACITY, adapter.adapt("p", at + " ").findings.get(0).code);
    }
    @Test public void iterativePayloadParserDoesNotAdoptRuleDepthEight() {
        String text = "{\"protocol\":1,\"business\":\"t\",\"scene\":\"s\",\"ignored\":" + repeat('[', 12000) + "0" + repeat(']', 12000) + "}";
        assertTrue(adapter.adapt("p", text).findings.isEmpty());
    }
    @Test public void exactArithmeticCanCancelLongFractionAndExponentWithoutExpansion() {
        valid("1", "0." + repeat('0', 5000) + "1e5001", "1" + repeat('0', 5000) + "e-5000");
        invalid(Code.PROTOCOL_NON_INTEGER, "1e-" + repeat('9', 20000));
        invalid(Code.PROTOCOL_RANGE, "1e" + repeat('9', 20000));
    }
    @Test public void matchValueNumbersRemainL1TypeEvenThoughRawNumericFormsNormalize() {
        for (String value : Arrays.asList("1", "1.0", "1e0", "-0")) {
            IslandRuleCodec.DecodeResult decoded = new IslandRuleCodec().decode(json(value).getBytes(StandardCharsets.UTF_8));
            assertFalse(decoded.isSuccess()); assertEquals(IslandRuleCodec.Code.L1_TYPE, decoded.error.code);
            assertEquals("$.match.all[0].value", decoded.error.path);
        }
    }
    @Test public void rawNormalizationDoesNotNormalizeLiteralMatchValueOrChangeRawString() {
        String source = "{\"protocol\":1.0,\"business\":\"train\",\"scene\":\"gate\"}";
        Result r = adapter.adapt("p", source);
        assertEquals("{\"protocol\":1.0,\"business\":\"train\",\"scene\":\"gate\"}", source);
        IslandRuleSelector selector = new IslandRuleSelector();
        assertNotNull(selector.select(Collections.singletonList(entry("\"1\"")), r.context));
        assertNull(selector.select(Collections.singletonList(entry("\"1.0\"")), r.context));
        assertNull(selector.select(Collections.singletonList(entry("\"1e0\"")), r.context));
    }
    @Test public void findingsAreImmutableFixedAndBoundedWithoutPayloadOrExceptionText() {
        Result r = adapter.adapt(null, "{\"protocol\":\"SECRET\",\"business\":{},\"scene\":[]}");
        assertEquals(4, r.findings.size());
        for (IslandRuleContextAdapter.Finding f : r.findings) {
            assertFalse(f.path.contains("SECRET")); assertFalse(f.code.name().contains("SECRET"));
        }
        try { r.findings.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }
}
