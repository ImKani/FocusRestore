/* SPDX-License-Identifier: GPL-3.0-only
 * ImKani / FocusRestore / https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
import com.hyperos3.focusrestore.IslandExportDocument.Reason;
import com.hyperos3.focusrestore.IslandExportRequest.Mode;
import com.hyperos3.focusrestore.IslandExportState.ExportAuthorization;
import com.hyperos3.focusrestore.IslandExportState.FullConfirmation;
import com.hyperos3.focusrestore.IslandExportState.LockState;

/** 最终编码字节及严格 JSON 的独立边界，不改捕获器配额或构造 comparison。 */
public class IslandExportBoundaryTest {
    private final IslandExportGenerator generator = new IslandExportGenerator();
    private static final IslandExportState STATE = new IslandExportState(LockState.UNLOCKED,
            ExportAuthorization.AUTHORIZED, FullConfirmation.CONFIRMED, 42);
    private static String repeat(char c, int count) { char[] text = new char[count]; Arrays.fill(text, c); return new String(text); }
    private static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    private static IslandCaptureRecord record(long sequence, String body, String raw) {
        return new IslandCaptureRecord(42, sequence, 0, IslandCaptureRecord.State.CAPTURED, null,
                "test.app", 10, "key", body, raw, "{}", -999);
    }
    private IslandExportDocument generate(List<IslandCaptureRecord> records, Mode mode) {
        IslandExportGenerator.ExportPlan plan = generator.begin(new IslandExportRequest(records, null,
                Collections.singleton("test.app"), mode), STATE);
        while (plan.nextIndex < plan.frozenSamples.size()) {
            IslandExportGenerator.Step step = generator.renderNextSample(plan, STATE);
            // 分步不交付正文片段；后续超限不能留下被称作成功的部分文档。
            assertNull(step.jsonFragment); assertNull(step.markdownFragment);
            if (step.incomplete != null) return step.incomplete;
            plan = step.plan;
        }
        return generator.finish(plan, STATE);
    }
    private IslandExportDocument generate(IslandCaptureRecord record, Mode mode) {
        return generate(Collections.singletonList(record), mode);
    }

    @Test public void omittedModeDefaultsToRedactedWithoutFullConfirmation() {
        IslandExportRequest request = new IslandExportRequest(Collections.singletonList(record(1, "private", "{}")),
                null, Collections.singleton("test.app"));
        assertEquals(Mode.REDACTED, request.mode);
        IslandExportState state = new IslandExportState(LockState.UNLOCKED, ExportAuthorization.AUTHORIZED,
                FullConfirmation.NOT_CONFIRMED, 42);
        IslandExportGenerator.ExportPlan plan = generator.begin(request, state);
        plan = generator.renderNextSample(plan, state).plan;
        IslandExportDocument result = generator.finish(plan, state);
        assertTrue(result.complete); assertFalse(result.json.contains("private"));
    }
    @Test public void jsonEncoderExactTwoMiBAndOneByteOver() {
        int limit = IslandExportGenerator.MAX_JSON_BYTES;
        String exact = IslandExportJson.write(repeat('a', limit - 2), limit);
        assertEquals(limit, bytes(exact));
        try { IslandExportJson.write(repeat('a', limit - 1), limit); fail(); }
        catch (IslandExportJson.Capacity expected) { }
    }
    @Test public void markdownCounterExact512KiBAndMultibyteAccounting() {
        int limit = IslandExportGenerator.MAX_MARKDOWN_BYTES;
        IslandExportJson.Text writer = new IslandExportJson.Text(limit);
        writer.add(repeat('a', limit - 7)).add("中😀");
        assertEquals(limit, writer.bytes()); assertEquals(limit, bytes(writer.toString()));
        try { writer.add("a"); fail(); } catch (IslandExportJson.Capacity expected) { }
    }
    @Test public void escapedJsonBytesIncludeQuotesControlsAndSurrogates() {
        String value = "\"\\\u0000中😀\ud800";
        String encoded = IslandExportJson.write(value, 100);
        assertEquals(value, IslandExportJson.parse(encoded));
        int size = bytes(encoded);
        assertEquals(encoded, IslandExportJson.write(value, size));
        try { IslandExportJson.write(value, size - 1); fail(); } catch (IslandExportJson.Capacity expected) { }
    }
    @Test public void finalMarkdownAtExactCapThenOneByteOverRejectsBothDocuments() {
        // 八条真实单字段 65536 上界内的记录，前七条填满；最后一条按包头/转义后大小补齐。
        IslandCaptureRecord[] input = new IslandCaptureRecord[8];
        for (int i = 0; i < 7; i++) input[i] = record(i + 1, repeat('a', 65536), "{}");
        input[7] = record(8, "", "{}");
        IslandExportDocument base = generate(Arrays.asList(input), Mode.FULL);
        assertTrue(base.complete);
        int pad = IslandExportGenerator.MAX_MARKDOWN_BYTES - bytes(base.markdown);
        assertTrue(pad >= 0 && pad <= 65536);
        input[7] = record(8, repeat('a', pad), "{}");
        IslandExportDocument exact = generate(Arrays.asList(input), Mode.FULL);
        assertTrue(exact.complete); assertEquals(524288, bytes(exact.markdown));
        assertTrue(bytes(exact.json) < 2097152);
        input[7] = record(8, repeat('a', pad + 1), "{}");
        IslandExportDocument over = generate(Arrays.asList(input), Mode.FULL);
        assertFalse(over.complete); assertEquals(Reason.MARKDOWN_CAPACITY, over.reason);
        assertNull(over.json); assertNull(over.markdown);
    }

    private static String payload(int count, int pad) {
        StringBuilder raw = new StringBuilder("{\"title\":[");
        for (int i = 0; i < count; i++) { if (i > 0) raw.append(','); raw.append('0'); }
        return raw.append("],\"progressInfo\":{\"progress\":\"").append(repeat('a', pad)).append("\"}}").toString();
    }
    @Test public void finalJsonAtExactCapThenOneByteOverRejectsBothDocuments() {
        int low = 1, high = 10000;
        while (low + 1 < high) {
            int mid = (low + high) / 2;
            IslandExportDocument probe = generate(record(1, "", payload(mid, 0)), Mode.REDACTED);
            if (probe.complete) low = mid; else { assertEquals(Reason.JSON_CAPACITY, probe.reason); high = mid; }
        }
        IslandExportDocument base = generate(record(1, "", payload(low, 0)), Mode.REDACTED);
        assertTrue(base.complete);
        int pad = 2097152 - bytes(base.json);
        assertTrue(pad > 0 && pad < 1024);
        // structural 字符串原值保留，每个 ASCII 字符给最终 JSON 精确增加1字节。
        IslandExportDocument exact = generate(record(1, "", payload(low, pad)), Mode.REDACTED);
        assertTrue(exact.complete); assertEquals(2097152, bytes(exact.json));
        assertTrue(bytes(exact.markdown) < 524288);
        IslandExportDocument over = generate(record(1, "", payload(low, pad + 1)), Mode.REDACTED);
        assertFalse(over.complete); assertEquals(Reason.JSON_CAPACITY, over.reason);
        assertNull(over.json); assertNull(over.markdown);
    }
    @Test public void parserRejectsAmbiguousAndNonJsonGrammar() {
        for (String text : Arrays.asList("", "{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}",
                "[1,]", "{\"a\":1,}", "01", "-01", "1.", "1e", "NaN", "Infinity", "+1", ".1", "true false",
                "\u00a0{}", "\"bad\nline\"", "\"\\x01\"", "\"\\u１２３４\"", "[", "{]")) {
            try { IslandExportJson.parse(text); fail("accepted invalid JSON"); }
            catch (IslandExportJson.Invalid expected) { }
        }
    }
    @Test public void iterativeParserAndWriterHandleDeepContainersAndHugeNumericTokens() {
        String json = repeat('[', 12000) + "1e999999999999999999999999" + repeat(']', 12000);
        Object tree = IslandExportJson.parse(json);
        assertEquals(json, IslandExportJson.write(tree, 65536));
        assertEquals("-0.1e+123", IslandExportJson.write(IslandExportJson.parse("-0.1e+123"), 100));
    }
    @SuppressWarnings("unchecked")
    @Test public void allElevenStructuralPathsPreserveScalarButContainerLeavesAreNotExempt() {
        int count = 0;
        for (IslandRulePaths.Entry path : IslandRulePaths.all()) {
            if (path.securityLevel != IslandRulePaths.SecurityLevel.STRUCTURAL) continue;
            String raw = "7";
            String[] fields = path.path.substring(1).split("/");
            for (int i = fields.length - 1; i >= 0; i--) raw = "{\"" + fields[i] + "\":" + raw + "}";
            IslandExportDocument document = generate(record(1, "", raw), Mode.REDACTED);
            assertTrue(document.complete);
            Map<String, Object> container = (Map<String, Object>) IslandExportJson.parse(document.json);
            Map<String, Object> sample = (Map<String, Object>) ((List<?>) container.get("samples")).get(0);
            String value = (String) ((Map<?, ?>) sample.get("rawParamJSON")).get("value");
            assertTrue(value.contains(":7")); count++;
        }
        assertEquals(11, count);
        IslandExportDocument unsafe = generate(record(1, "", "{\"progressInfo\":{\"progress\":{\"secretName\":\"secretBody\"}}}"), Mode.REDACTED);
        assertTrue(unsafe.complete); assertFalse(unsafe.json.contains("secretName")); assertFalse(unsafe.json.contains("secretBody"));
    }
    @Test public void lockedRedactedRejectsOtherRecordStatesRatherThanSilentlyChangingSelection() {
        for (LockState lock : Arrays.asList(LockState.LOCKED, LockState.UNKNOWN)) {
            IslandExportState state = new IslandExportState(lock, ExportAuthorization.AUTHORIZED,
                    FullConfirmation.NOT_CONFIRMED, 42);
            for (IslandCaptureRecord.State kind : Arrays.asList(IslandCaptureRecord.State.REJECTED, IslandCaptureRecord.State.READ_ERROR)) {
                IslandCaptureRecord input = new IslandCaptureRecord(42, 1, 0, kind,
                        kind == IslandCaptureRecord.State.REJECTED ? IslandCaptureRecord.Reason.AUTH_UNVERIFIED : IslandCaptureRecord.Reason.READ_FAILED, null, null, null, null, null, null, 0);
                try {
                    generator.begin(new IslandExportRequest(Collections.singletonList(input), null,
                            Collections.singleton("test.app"), Mode.REDACTED), state);
                    fail();
                } catch (IslandExportGenerator.Rejected rejected) { assertEquals(Reason.INVALID_REQUEST, rejected.reason); }
            }
        }
    }
    @Test public void everyRecordStateAndBothModesHaveOnlyNotObservedComparison() {
        for (IslandCaptureRecord.State kind : IslandCaptureRecord.State.values()) {
            IslandCaptureRecord input = kind == IslandCaptureRecord.State.CAPTURED ? record(1, "", "{}")
                    : new IslandCaptureRecord(42, 1, 0, kind, kind == IslandCaptureRecord.State.LOCKED_PLACEHOLDER
                            ? IslandCaptureRecord.Reason.LOCKED : kind == IslandCaptureRecord.State.REJECTED
                            ? IslandCaptureRecord.Reason.AUTH_UNVERIFIED : IslandCaptureRecord.Reason.READ_FAILED,
                            null, null, null, null, null, null, 0);
            for (Mode mode : Mode.values()) {
                IslandExportDocument document = generate(input, mode);
                Map<?, ?> root = (Map<?, ?>) IslandExportJson.parse(document.json);
                Map<?, ?> sample = (Map<?, ?>) ((List<?>) root.get("samples")).get(0);
                Map<?, ?> comparison = (Map<?, ?>) sample.get("comparison");
                assertEquals(4, comparison.size());
                assertEquals("not-observed", comparison.get("status"));
                for (String key : Arrays.asList("before", "after", "changed")) {
                    assertTrue(comparison.containsKey(key)); assertNull(comparison.get(key));
                }
            }
        }
    }
    @Test public void lateCapacityFailureReturnsTerminalPlanWithoutEarlierReadableFragments() {
        IslandExportGenerator.ExportPlan plan = generator.begin(new IslandExportRequest(Arrays.asList(
                record(1, "first body", "{}"), record(2, "", payload(10000, 0))), null,
                Collections.singleton("test.app"), Mode.REDACTED), STATE);
        IslandExportGenerator.Step first = generator.renderNextSample(plan, STATE);
        assertNull(first.jsonFragment); assertNull(first.markdownFragment); assertNull(first.incomplete);
        IslandExportGenerator.Step second = generator.renderNextSample(first.plan, STATE);
        assertEquals(Reason.JSON_CAPACITY, second.incomplete.reason);
        assertNull(second.incomplete.json); assertNull(second.incomplete.markdown);
        assertEquals(1, second.plan.nextIndex);
        assertEquals(Reason.JSON_CAPACITY, generator.finish(second.plan, STATE).reason);
    }
}
