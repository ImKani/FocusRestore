/* SPDX-License-Identifier: GPL-3.0-only
 * ImKani / FocusRestore / https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
import com.hyperos3.focusrestore.IslandCaptureInputAdapter.*;
import com.hyperos3.focusrestore.IslandCaptureSession.BodyFields;

/** 只验证受限纯输入；不把模拟值或类型标记当成真实来源认证。 */
public class IslandD2AdapterTest {
    private static final String PRIMARY = "miui.focus.param";
    private static final String CUSTOM = "miui.focus.param.custom";
    private static final String TICKER = "miui.focus.ticker";
    private static final List<String> KEYS = Arrays.asList("miui.focus.isFocus", PRIMARY, CUSTOM, TICKER,
            "miui.focus.rv", "miui.focus.rvNight", "miui.focus.rvBar", "miui.focus.rvBarNight");
    private static Builder base() { return new Builder().bodyText(Value.of("body")).extra(PRIMARY, Value.of("{}")); }
    private static String repeat(char c, int count) { char[] text = new char[count]; Arrays.fill(text, c); return new String(text); }
    private static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    @SuppressWarnings("unchecked") private static Map<String, Object> descriptors(BodyFields fields) {
        return (Map<String, Object>) IslandExportJson.parse((String) fields.extrasJson);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> descriptor(BodyFields fields, String key) {
        return (Map<String, Object>) descriptors(fields).get(key);
    }
    @Test public void constructionDoesNotSelectReadTrimOrEncode() {
        Source source = IslandCaptureInputAdapter.source(base().extra(PRIMARY, Value.readFailure()).build());
        assertEquals(0, source.readCount()); assertEquals(Code.NOT_READ, source.summary().code);
        assertNull(source.summary().selectedKey);
    }
    @Test public void primaryWinsAndOriginalWhitespaceIsPreserved() throws Exception {
        String original = " \t{\"title\":\"P\"}\n ";
        Source source = IslandCaptureInputAdapter.source(base().extra(PRIMARY, Value.of(original))
                .extra(CUSTOM, Value.of("{\"customOnly\":true}")).build());
        BodyFields fields = source.read();
        assertSame(original, fields.rawParamJSON); assertEquals(PRIMARY, source.summary().selectedKey);
        assertEquals("string", descriptor(fields, PRIMARY).get("valueType"));
        assertEquals(Integer.toString(original.length()),
                ((IslandExportJson.NumberToken) descriptor(fields, PRIMARY).get("length")).text);
        assertFalse(((String) fields.extrasJson).contains("customOnly"));
        assertFalse(descriptor(fields, PRIMARY).containsKey("value")); assertFalse(descriptor(fields, CUSTOM).containsKey("value"));
    }
    @Test public void trimOnlyControlsChoiceIncludingExistingAsciiTrimSemantics() throws Exception {
        for (String blank : Arrays.asList("", " ", "\t\n\r", "\u0000\u001f ")) {
            Source source = IslandCaptureInputAdapter.source(base().extra(PRIMARY, Value.of(blank))
                    .extra(CUSTOM, Value.of(" \t{} \n")).build());
            assertEquals(" \t{} \n", source.read().rawParamJSON); assertEquals(CUSTOM, source.summary().selectedKey);
        }
        Source nbsp = IslandCaptureInputAdapter.source(base().extra(PRIMARY, Value.of("\u00a0"))
                .extra(CUSTOM, Value.of("{}" )).build());
        assertEquals("\u00a0", nbsp.read().rawParamJSON); assertEquals(PRIMARY, nbsp.summary().selectedKey);
    }
    @Test public void choiceMatchesActualFocusParamKeysWithoutReplacingRawWithTrimmedText() throws Exception {
        for (Object primary : Arrays.<Object>asList(null, "", " \t ", " {} ", Boolean.TRUE, Integer.valueOf(1))) {
            final Object first = primary;
            FocusParamKeys.ValueSource actual = key -> key.equals(PRIMARY) ? first : " {\"custom\":1} ";
            Source source = IslandCaptureInputAdapter.source(base().extra(PRIMARY, Value.of(primary))
                    .extra(CUSTOM, Value.of(" {\"custom\":1} ")).build());
            BodyFields result = source.read();
            assertEquals(FocusParamKeys.pickKey(actual), source.summary().selectedKey);
            assertEquals(FocusParamKeys.pick(actual), ((String) result.rawParamJSON).trim());
        }
    }
    @Test public void invalidJsonPrimaryStillWinsWithoutParsingOrCombiningParameters() throws Exception {
        Source source = IslandCaptureInputAdapter.source(base().extra(PRIMARY, Value.of(" invalid-json "))
                .extra(CUSTOM, Value.of("{}" )).build());
        assertEquals(" invalid-json ", source.read().rawParamJSON); assertEquals(Code.READY, source.summary().code);
    }
    @Test public void wrongTypePrimaryCanFallThroughButDoesNotBecomeItsToString() throws Exception {
        Object poison = new Object() { @Override public String toString() { throw new AssertionError("no conversion"); } };
        Source source = IslandCaptureInputAdapter.source(base().extra(PRIMARY, Value.of(poison))
                .extra(CUSTOM, Value.of("{}" )).build());
        BodyFields fields = source.read(); assertEquals("{}", fields.rawParamJSON);
        assertEquals("wrong-type", descriptor(fields, PRIMARY).get("valueType"));
        assertEquals(Boolean.TRUE, descriptor(fields, PRIMARY).get("present"));
    }
    @Test public void absentNullBlankWrongAndReadErrorDescriptorsStayDistinct() throws Exception {
        for (Value value : Arrays.asList(Value.absent(), Value.of(null), Value.of(""), Value.of(3), Value.readFailure())) {
            BodyFields fields = IslandCaptureInputAdapter.source(base().extra(TICKER, value).build()).read();
            Map<String, Object> d = descriptor(fields, TICKER);
            assertNotNull(d.get("valueType"));
            if ("missing".equals(d.get("valueType"))) assertEquals(Boolean.FALSE, d.get("present"));
            if ("null".equals(d.get("valueType"))) assertEquals(Boolean.TRUE, d.get("present"));
            if ("read-error".equals(d.get("valueType"))) assertFalse(d.containsKey("present"));
            if ("string".equals(d.get("valueType"))) { assertEquals("", d.get("value")); assertEquals("0", ((IslandExportJson.NumberToken) d.get("length")).text); }
        }
    }
    @Test public void allEightDescriptorsHaveExactlyPermittedFields() throws Exception {
        Builder builder = base().extra("miui.focus.isFocus", Value.of(Boolean.FALSE))
                .extra(CUSTOM, Value.of("custom raw" )).extra(TICKER, Value.of("ticker text"));
        for (int i = 4; i < KEYS.size(); i++) builder.extra(KEYS.get(i), Value.remoteViewsMarker());
        BodyFields fields = IslandCaptureInputAdapter.source(builder.build()).read();
        Map<String, Object> all = descriptors(fields); assertEquals(KEYS, Arrays.asList(all.keySet().toArray(new String[0])));
        for (String key : KEYS) {
            Map<String, Object> d = descriptor(fields, key); assertEquals(Boolean.TRUE, d.get("present"));
            for (String field : d.keySet()) assertTrue(Arrays.asList("present", "valueType", "length", "value").contains(field));
            if (key.equals("miui.focus.isFocus")) { assertEquals(Boolean.FALSE, d.get("value")); assertEquals(3, d.size()); }
            else if (key.equals(TICKER)) { assertEquals("ticker text", d.get("value")); assertEquals(4, d.size()); }
            else if (key.equals(PRIMARY) || key.equals(CUSTOM)) { assertEquals(3, d.size()); assertFalse(d.containsKey("value")); }
            else { assertEquals("remote-views", d.get("valueType")); assertEquals(2, d.size()); }
        }
    }
    @Test public void unknownKeysAndCallerComposedJsonCannotEnterInput() {
        for (String key : Arrays.asList("miui.focus.other", "miui.focus.param.media", "miui.focus.*", "extrasJson", "bodyText", "uid", null)) {
            try { base().extra(key, Value.readFailure()); fail(); }
            catch (IllegalArgumentException expected) { assertEquals("unapproved capture key", expected.getMessage()); }
        }
        // 固定结构没有任意键读取接口，不能通过遍历未知键引入内容。
        for (Field field : Input.class.getDeclaredFields()) assertFalse(Map.class.isAssignableFrom(field.getType()));
    }
    @Test public void arbitraryForbiddenObjectsAreDiscardedNotRetained() throws Exception {
        Object object = new Object() { @Override public String toString() { throw new AssertionError("must never stringify"); } };
        Value value = Value.of(object);
        for (Field field : Value.class.getDeclaredFields()) {
            field.setAccessible(true); assertNotSame(object, field.get(value)); assertNotEquals(Object.class, field.getType());
        }
        Builder builder = base();
        for (String key : KEYS) if (!key.equals(PRIMARY)) builder.extra(key, value);
        BodyFields fields = IslandCaptureInputAdapter.source(builder.build()).read();
        assertFalse(((String) fields.extrasJson).contains(object.getClass().getName()));
        for (String key : KEYS) if (!key.equals(PRIMARY)) assertEquals("wrong-type", descriptor(fields, key).get("valueType"));
    }
    @Test public void requiredBodyFailuresMapToDifferentSessionFacingForms() throws Exception {
        for (Value value : Arrays.asList(Value.absent(), Value.of(null), Value.of(Boolean.TRUE))) {
            Source source = IslandCaptureInputAdapter.source(base().bodyText(value).build());
            BodyFields result = source.read();
            if (source.summary().code == Code.TYPE_MISMATCH) { assertNotNull(result.bodyText); assertFalse(result.bodyText instanceof String); }
            else { assertEquals(Code.MISSING_FIELD, source.summary().code); assertNull(result.bodyText); }
        }
        Source failed = IslandCaptureInputAdapter.source(base().bodyText(Value.readFailure()).build());
        try { failed.read(); fail(); } catch (Exception fixed) { assertEquals("capture input read failed", fixed.getMessage()); }
        assertEquals(Code.READ_FAILED, failed.summary().code);
    }
    @Test public void noSelectedRawIsMissingOrWrongTypeAndNeverFabricatesEmptySuccess() throws Exception {
        for (Value value : Arrays.asList(Value.absent(), Value.of(null), Value.of(""), Value.of(" \n"))) {
            Source source = IslandCaptureInputAdapter.source(base().extra(PRIMARY, value).build());
            assertNull(source.read().rawParamJSON); assertEquals(Code.MISSING_FIELD, source.summary().code);
        }
        Source wrong = IslandCaptureInputAdapter.source(base().extra(PRIMARY, Value.of(true)).build());
        assertFalse(wrong.read().bodyText instanceof String); assertEquals(Code.TYPE_MISMATCH, wrong.summary().code);
    }
    @Test public void primaryReadErrorAbortsInsteadOfSilentlySelectingCustom() throws Exception {
        Source source = IslandCaptureInputAdapter.source(base().extra(PRIMARY, Value.readFailure()).extra(CUSTOM, Value.of("{}" )).build());
        try { source.read(); fail(); } catch (Exception fixed) { assertEquals("capture input read failed", fixed.getMessage()); }
        assertEquals(Code.READ_FAILED, source.summary().code); assertNull(source.summary().selectedKey);
    }
    @Test public void unselectedCustomReadErrorIsOnlyOptionalDescriptorWhenPrimaryWon() throws Exception {
        Source source = IslandCaptureInputAdapter.source(base().extra(CUSTOM, Value.readFailure()).build());
        BodyFields fields = source.read(); assertEquals("{}", fields.rawParamJSON); assertEquals(Code.READY, source.summary().code);
        Map<String, Object> d = descriptor(fields, CUSTOM); assertEquals("read-error", d.get("valueType")); assertFalse(d.containsKey("present"));
    }
    @Test public void inputIsFrozenAgainstBuilderReassignmentAndValueContainsNoCallback() throws Exception {
        Builder builder = base(); Input original = builder.build();
        builder.bodyText(Value.of("later body")).extra(PRIMARY, Value.of("later raw"));
        BodyFields fields = IslandCaptureInputAdapter.source(original).read();
        assertEquals("body", fields.bodyText); assertEquals("{}", fields.rawParamJSON);
        for (Field f : Source.class.getDeclaredFields()) assertFalse(f.getType().isInterface());
    }
    @Test public void utf16LengthsAreOriginalNotTrimmedOrUtf8Bytes() throws Exception {
        String original = "  中😀\n";
        BodyFields fields = IslandCaptureInputAdapter.source(base().extra(PRIMARY, Value.of(original)).extra(TICKER, Value.of(original)).build()).read();
        assertEquals(Integer.toString(original.length()), ((IslandExportJson.NumberToken) descriptor(fields, PRIMARY).get("length")).text);
        assertEquals(original, descriptor(fields, TICKER).get("value")); assertTrue(bytes(original) > original.length());
    }
    @Test public void exactEncodedExtrasCapAndOneByteOverUseWholeRecordRejectionMarker() throws Exception {
        int low = 0, high = 65536;
        while (low + 1 < high) {
            int mid = (low + high) / 2;
            Source candidate = IslandCaptureInputAdapter.source(base().extra(TICKER, Value.of(repeat('a', mid))).build());
            candidate.read(); if (candidate.summary().code == Code.READY) low = mid; else high = mid;
        }
        Source exact = IslandCaptureInputAdapter.source(base().extra(TICKER, Value.of(repeat('a', low))).build());
        BodyFields at = exact.read(); assertEquals(65536, bytes((String) at.extrasJson)); assertEquals(Code.READY, exact.summary().code);
        Source over = IslandCaptureInputAdapter.source(base().extra(TICKER, Value.of(repeat('a', low + 1))).build());
        BodyFields rejected = over.read(); assertEquals(Code.OVERSIZE, over.summary().code);
        assertEquals("$.extrasJson", over.summary().path); assertEquals(65537, bytes((String) rejected.extrasJson));
    }
    @Test public void escapedAndMultibyteTickerCountFinalEncodedBytes() throws Exception {
        for (String ticker : Arrays.asList(repeat('\u0001', 11000), repeat('中', 22000))) {
            Source source = IslandCaptureInputAdapter.source(base().extra(TICKER, Value.of(ticker)).build());
            assertEquals(65537, bytes((String) source.read().extrasJson)); assertEquals(Code.OVERSIZE, source.summary().code);
        }
    }
    @Test public void perFieldUtf8ChecksMatchSessionForSurrogatesAndSupplementaryCharacters() throws Exception {
        String exact = repeat('a', 65532) + "😀";
        Source source = IslandCaptureInputAdapter.source(base().bodyText(Value.of(exact)).build());
        assertEquals(exact, source.read().bodyText); assertEquals(Code.READY, source.summary().code);
        Source over = IslandCaptureInputAdapter.source(base().bodyText(Value.of(exact + "a")).build());
        over.read(); assertEquals(Code.OVERSIZE, over.summary().code); assertEquals("$.bodyText", over.summary().path);
        Source isolated = IslandCaptureInputAdapter.source(base().bodyText(Value.of(repeat('a', 65535) + "\ud800")).build());
        isolated.read(); assertEquals(Code.READY, isolated.summary().code);
    }
    @Test public void summariesAreFixedNoBodyAndCountExplicitReadsOnly() throws Exception {
        Source source = IslandCaptureInputAdapter.source(base().extra(PRIMARY, Value.of("SECRET raw")).build());
        Summary initial = source.summary(); source.read(); source.read();
        assertEquals(Code.NOT_READ, initial.code); assertEquals(2, source.readCount());
        Summary finalState = source.summary(); assertEquals(Code.READY, finalState.code);
        assertEquals("$", finalState.path); assertEquals(PRIMARY, finalState.selectedKey);
        for (Field field : Summary.class.getDeclaredFields()) { field.setAccessible(true); Object value = field.get(finalState); assertFalse(String.valueOf(value).contains("SECRET")); }
    }
}
