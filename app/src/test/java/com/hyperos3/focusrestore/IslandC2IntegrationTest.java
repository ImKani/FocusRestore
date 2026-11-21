/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.hyperos3.focusrestore.IslandCaptureSession.IdentityAuthorization;
import com.hyperos3.focusrestore.IslandExportDocument.Reason;
import com.hyperos3.focusrestore.IslandExportRequest.Mode;
import com.hyperos3.focusrestore.IslandExportState.ExportAuthorization;
import com.hyperos3.focusrestore.IslandExportState.FullConfirmation;
import com.hyperos3.focusrestore.IslandExportState.LockState;

import static org.junit.Assert.*;

/**
 * C2-5 纯 JVM API 组合回归。真实来源为 docs/samples 的只读规则/通知 fixture；
 * 不调用提取执行器或内置解析器，不把选条、捕获和导出组合误报为运行期转换。
 */
public class IslandC2IntegrationTest {
    private static final String PACKAGE = "com.miui.personalassistant";
    private static final long EXPORT_GENERATION = 1L;
    private static final File RULE_FILE = new File("docs/samples/rule-travel.valid.json");
    private static final File NOTIFICATION_FILE = new File("docs/samples/notification-travel.real.json");
    private final IslandRuleSetLoader loader = new IslandRuleSetLoader();
    private final IslandRuleSelector selector = new IslandRuleSelector();
    private final IslandExportGenerator generator = new IslandExportGenerator();

    @Test public void realRuleFileAndMemoryVariantUseTheSameStrictLoadAndSelectPath() throws Exception {
        String original = ruleText();
        IslandRuleSetLoader.Source memory = new IslandRuleSetLoader.Source("memory-default-priority", 2, true,
                () -> new ByteArrayInputStream(original.getBytes(StandardCharsets.UTF_8)));
        IslandRuleSetLoader.Snapshot snapshot = loader.load(4, Arrays.asList(
                realSource("real", true, 0), memory));
        assertTrue(snapshot.loaded); assertTrue(snapshot.failures.isEmpty());
        assertEquals(2, snapshot.ruleSet.activeRuleCount);
        assertEquals(Arrays.asList("real", "memory-default-priority"), ids(snapshot.orderedActiveEntries));
        assertEquals(0, snapshot.orderedActiveEntries.get(1).priority);
        assertEquals(original.getBytes(StandardCharsets.UTF_8).length, snapshot.ruleSet.entries.get(0).utf8Bytes);
        assertEquals("real", selector.select(snapshot.orderedActiveEntries, travelContext()).id);
        assertEquals("ImKani", snapshot.ruleSet.entries.get(0).rule.meta.ruleAuthor);
    }

    @Test public void stablePrioritySkipsBadJsonUnknownVersionAndDisabledWithoutReorderingAcceptedEntries() throws Exception {
        Map<String, Object> future = ruleTree(); future.put("ruleVersion", 3);
        IslandRuleSetLoader.Snapshot snapshot = loader.load(5, Arrays.asList(
                realSource("low", true, Integer.MIN_VALUE),
                memorySource("bad-json", true, Integer.MAX_VALUE, "{"),
                memorySource("future", true, Integer.MAX_VALUE, encode(future)),
                realSource("disabled", false, Integer.MAX_VALUE),
                memorySource("z-first-tie", true, 7, ruleText()),
                realSource("a-second-tie", true, 7)));
        assertEquals(Arrays.asList("low", "disabled", "z-first-tie", "a-second-tie"), ids(snapshot.ruleSet.entries));
        assertEquals(Arrays.asList("z-first-tie", "a-second-tie", "low"), ids(snapshot.orderedActiveEntries));
        assertEquals(2, snapshot.failures.size());
        assertEquals(IslandRuleSetLoader.Code.DECODE_REJECTED, snapshot.failures.get(0).code);
        assertEquals(IslandRuleCodec.Code.L1_JSON, snapshot.failures.get(0).decodeError.code);
        assertEquals(IslandRuleSetLoader.Code.DECODE_REJECTED, snapshot.failures.get(1).code);
        assertEquals(IslandRuleCodec.Code.L1_VERSION, snapshot.failures.get(1).decodeError.code);
        assertEquals("z-first-tie", selector.select(snapshot.orderedActiveEntries, travelContext()).id);
    }

    @Test public void loadedFourFieldAndRequiresEveryContextValueAndKeepsProtocolLiteral() throws Exception {
        IslandRuleSetLoader.Snapshot snapshot = loader.load(1, Collections.singletonList(
                memorySource("four-and", true, 0, fourClauseRule())));
        assertTrue(snapshot.failures.isEmpty());
        IslandRuleSet.Entry expected = snapshot.orderedActiveEntries.get(0);
        assertSame(expected, selector.select(snapshot.orderedActiveEntries, travelContext()));
        for (IslandRuleContext wrong : Arrays.asList(
                new IslandRuleContext("other.package", "1", "train", "personal-assistant-travel"),
                new IslandRuleContext(PACKAGE, "01", "train", "personal-assistant-travel"),
                new IslandRuleContext(PACKAGE, "1", "bus", "personal-assistant-travel"),
                new IslandRuleContext(PACKAGE, "1", "train", "other-scene"),
                new IslandRuleContext(PACKAGE, "1", "train", null))) {
            assertNull(selector.select(snapshot.orderedActiveEntries, wrong));
        }
    }

    @Test public void firstNullAndNonmatchAreSkippedButFirstMatchStopsBeforeLaterAccess() throws Exception {
        Map<String, Object> mismatch = ruleTree();
        mismatch.put("match", map("all", Collections.singletonList(map("field", "business", "op", "equals", "value", "bus"))));
        IslandRuleSetLoader.Snapshot snapshot = loader.load(1, Arrays.asList(
                memorySource("wrong", true, 2, encode(mismatch)), realSource("good", true, 1)));
        assertTrue(snapshot.failures.isEmpty());
        IslandRuleSet.Entry wrong = snapshot.orderedActiveEntries.get(0);
        IslandRuleSet.Entry good = snapshot.orderedActiveEntries.get(1);
        List<IslandRuleSet.Entry> ordered = new AbstractList<IslandRuleSet.Entry>() {
            @Override public int size() { return 4; }
            @Override public IslandRuleSet.Entry get(int index) {
                if (index == 0) return null;
                if (index == 1) return wrong;
                if (index == 2) return good;
                throw new AssertionError("selection touched entry after first match");
            }
        };
        assertSame(good, selector.select(ordered, travelContext()));
        assertNull(selector.select(Arrays.asList(null, wrong), travelContext()));
    }

    @Test public void malformedInMemoryMatchDoesNotBlockLaterLoadedRule() throws Exception {
        IslandRuleSetLoader.Snapshot snapshot = loader.load(1, Collections.singletonList(realSource("good", true, 0)));
        assertTrue(snapshot.failures.isEmpty());
        IslandRuleSet.Entry good = snapshot.orderedActiveEntries.get(0);
        IslandRule base = good.rule;
        // 文件入口仍严格解码；这里只在包内构造意外损坏的内存条目，触发真实匹配异常隔离。
        IslandRule badRule = new IslandRule(IslandRule.CURRENT_VERSION, base.meta, Collections.singletonList(
                new MatchClause("deviceModel", "equals", "not-a-runtime-field")), base.extract, base.display);
        IslandRuleSet.Entry broken = new IslandRuleSet.Entry("broken-memory", 0, true, 10, badRule, good.utf8Bytes);
        assertSame(good, selector.select(Arrays.asList(broken, good), travelContext()));
        assertNull(selector.select(Collections.singletonList(broken), travelContext()));
    }

    @Test public void realCaptureWorkerProducesAllFourStatesWithoutRetainingFailedIdentityOrReadingLockedBody() throws Exception {
        List<IslandCaptureRecord> records = captureFourStates();
        assertEquals(Arrays.asList(IslandCaptureRecord.State.CAPTURED, IslandCaptureRecord.State.LOCKED_PLACEHOLDER,
                IslandCaptureRecord.State.REJECTED, IslandCaptureRecord.State.READ_ERROR), states(records));
        assertEquals(IslandCaptureRecord.Reason.LOCKED, records.get(1).reason);
        assertEquals(IslandCaptureRecord.Reason.AUTH_UNVERIFIED, records.get(2).reason);
        assertEquals(IslandCaptureRecord.Reason.READ_FAILED, records.get(3).reason);
        for (int index = 1; index < records.size(); index++) {
            IslandCaptureRecord record = records.get(index);
            assertNull(record.packageName); assertNull(record.userId); assertNull(record.notificationKey);
            assertNull(record.bodyText); assertNull(record.rawParamJSON); assertNull(record.extrasJson);
        }
    }

    @Test public void fourRealCaptureStatesExportRedactedWithExplicitNotObservedComparison() throws Exception {
        List<IslandCaptureRecord> records = captureFourStates();
        IslandExportDocument document = export(records, null, Mode.REDACTED, authorized(LockState.UNLOCKED, false));
        List<Object> samples = samples(document);
        assertEquals(4, samples.size());
        assertFalse(document.meta.replayableOriginal);
        assertFalse(document.json.contains("D8396")); assertFalse(document.markdown.contains("D8396"));
        assertEquals("<redacted:string>", object(samples.get(0)).get("bodyText"));
        assertEquals(PACKAGE, object(object(samples.get(0)).get("sbn")).get("packageName"));
        assertEquals("<redacted:number>", object(object(samples.get(0)).get("sbn")).get("userId"));
        for (int index = 0; index < samples.size(); index++) {
            Map<String, Object> sample = object(samples.get(index));
            assertEquals(records.get(index).id, sample.get("id"));
            assertComparisonNotObserved(sample);
            if (index > 0) {
                assertNull(sample.get("bodyText")); assertNull(sample.get("rawParamJSON"));
                assertNull(sample.get("extras"));
                for (Object identity : object(sample.get("sbn")).values()) assertNull(identity);
            }
        }
    }

    @Test public void fourRealCaptureStatesExportFullOnlyWithIndependentConfirmationAndOriginalAllowedValues() throws Exception {
        List<IslandCaptureRecord> records = captureFourStates();
        IslandExportDocument document = export(records, null, Mode.FULL, authorized(LockState.UNLOCKED, true));
        List<Object> samples = samples(document);
        assertEquals(4, samples.size());
        Map<String, Object> captured = object(samples.get(0));
        assertEquals(records.get(0).bodyText, captured.get("bodyText"));
        assertEquals(records.get(0).rawParamJSON, object(captured.get("rawParamJSON")).get("value"));
        assertEquals(records.get(0).notificationKey, object(captured.get("sbn")).get("key"));
        assertTrue(document.redactions.isEmpty());
        assertFalse(object(captured.get("sbn")).containsKey("uid"));
        assertFalse(object(captured.get("sbn")).containsKey("postTime"));
        for (Object sample : samples) assertComparisonNotObserved(object(sample));
    }

    @Test public void captureIdentityConfirmationDoesNotSupplyFullExportConfirmationOrReadAuthorization() throws Exception {
        List<IslandCaptureRecord> records = captureFourStates();
        assertEquals(IslandCaptureRecord.State.CAPTURED, records.get(0).state);
        IslandExportRequest request = request(records, Collections.singletonList(0), Mode.FULL);
        reject(request, authorized(LockState.UNLOCKED, false), Reason.FULL_NOT_CONFIRMED);
        for (ExportAuthorization auth : Arrays.asList(ExportAuthorization.UNVERIFIED, ExportAuthorization.DENIED)) {
            reject(request, new IslandExportState(LockState.UNLOCKED, auth, FullConfirmation.CONFIRMED,
                    EXPORT_GENERATION), Reason.UNAUTHORIZED);
        }
        for (LockState lock : Arrays.asList(LockState.LOCKED, LockState.UNKNOWN)) {
            reject(request, authorized(lock, true), Reason.FULL_LOCKED);
        }
    }

    @Test public void lockedRedactedSelectionContainsOnlyActualPrelockCaptureAndLockedPlaceholder() throws Exception {
        List<IslandCaptureRecord> records = captureFourStates();
        for (LockState lock : Arrays.asList(LockState.LOCKED, LockState.UNKNOWN)) {
            IslandExportDocument document = export(records, Arrays.asList(0, 1), Mode.REDACTED, authorized(lock, false));
            assertEquals(2, document.meta.sampleCount);
            assertEquals("CAPTURED", object(samples(document).get(0)).get("state"));
            assertEquals("LOCKED_PLACEHOLDER", object(samples(document).get(1)).get("state"));
            reject(request(records, null, Mode.REDACTED), authorized(lock, false), Reason.INVALID_REQUEST);
        }
    }

    @Test public void fullReadonlyFixturePipelineLoadsSelectsCapturesAndExportsWithoutExecutingExtract() throws Exception {
        Map<String, Object> fixture = notificationFixture();
        Map<String, Object> parameter = object(IslandExportJson.parse((String) fixture.get("rawParam")));
        IslandRuleContext context = new IslandRuleContext((String) fixture.get("packageName"),
                ((IslandExportJson.NumberToken) parameter.get("protocol")).text,
                (String) parameter.get("business"), (String) parameter.get("scene"));
        IslandRuleSetLoader.Snapshot loaded = loader.load(1, Arrays.asList(
                memorySource("bad-before-real", true, 100, "{"), realSource("travel-selected", true, 5)));
        assertEquals(1, loaded.failures.size());
        IslandRuleSet.Entry selected = selector.select(loaded.orderedActiveEntries, context);
        assertNotNull(selected); assertEquals("travel-selected", selected.id);
        assertEquals("param_island", selected.rule.meta.templateType);
        // 正文来自原 fixture 的预期字段作为捕获输入，并非在 C2 阶段执行 extract/display 得到。
        String body = (String) object(fixture.get("expected")).get("builtinFullText");
        try (IslandCaptureSession session = enabledSession()) {
            assertEquals(IslandCaptureSession.Submission.QUEUED, session.offer(context.packageName, 0, "travel-key",
                    IdentityAuthorization.CONFIRMED, () -> new IslandCaptureSession.BodyFields(body, fixture.get("rawParam"), "{}")));
            idle(session);
            List<IslandCaptureRecord> records = session.records();
            assertEquals(1, records.size());
            IslandExportDocument full = export(records, null, Mode.FULL, authorized(LockState.UNLOCKED, true));
            assertEquals(body, object(samples(full).get(0)).get("bodyText"));
            assertEquals(fixture.get("rawParam"), object(object(samples(full).get(0)).get("rawParamJSON")).get("value"));
            IslandExportDocument redacted = export(records, null, Mode.REDACTED, authorized(LockState.UNLOCKED, false));
            assertFalse(redacted.json.contains("D8396")); assertFalse(redacted.markdown.contains("D8396"));
            for (int i = 1; i <= 2; i++) {
                final String nextBody = body + "-" + i;
                assertNotNull(selector.select(loaded.orderedActiveEntries, context));
                assertEquals(IslandCaptureSession.Submission.QUEUED, session.offer(context.packageName, 0, "travel-key-" + i,
                        IdentityAuthorization.CONFIRMED, () -> new IslandCaptureSession.BodyFields(nextBody, fixture.get("rawParam"), "{}")));
                idle(session);
            }
            records = session.records();
            IslandExportDocument reordered = export(records, Arrays.asList(2, 0, 1), Mode.REDACTED, authorized(LockState.UNLOCKED, false));
            for (int i = 0; i < 3; i++)
                assertEquals(records.get(Arrays.asList(2, 0, 1).get(i)).id, object(samples(reordered).get(i)).get("id"));
            assertEquals("travel-selected", selector.select(loaded.orderedActiveEntries, context).id);
        }
    }

    @Test public void explicitExportSelectionReordersActualCaptureRecordsButDoesNotMutateCaptureSnapshot() throws Exception {
        List<IslandCaptureRecord> records = captureFourStates();
        List<String> original = recordIds(records);
        List<Integer> selection = new ArrayList<>(Arrays.asList(3, 0, 2, 1));
        IslandExportRequest request = request(records, selection, Mode.FULL);
        selection.clear();
        IslandExportDocument document = export(request, authorized(LockState.UNLOCKED, true));
        List<Object> samples = samples(document);
        for (int index = 0; index < 4; index++) {
            int selected = Arrays.asList(3, 0, 2, 1).get(index);
            assertEquals(records.get(selected).id, object(samples.get(index)).get("id"));
        }
        assertEquals(original, recordIds(records));
        assertEquals(4, request.selection.size());
    }

    @Test public void actualCaptureExportTracksUtf16TypeStructureInvalidJsonAndEscapesMarkdown() throws Exception {
        String original = "A😀中";
        String attack = "first\n![remote](https://example.invalid/a)\n<script>bad</script>\n```\n# heading";
        String raw = "{\"title\":[12,true,null],\"progressInfo\":{\"progress\":37},\"person-secret\":{\"title\":\"hidden\"}}";
        try (IslandCaptureSession session = enabledSession()) {
            assertEquals(IslandCaptureSession.Submission.QUEUED,
                    session.offer(PACKAGE, 0, "typed-key", IdentityAuthorization.CONFIRMED,
                            () -> new IslandCaptureSession.BodyFields(original, raw, "{}")));
            assertEquals(IslandCaptureSession.Submission.QUEUED,
                    session.offer(PACKAGE, 0, "invalid-key", IdentityAuthorization.CONFIRMED,
                            () -> new IslandCaptureSession.BodyFields(attack, "invalid-json-private", "{}")));
            idle(session);
            List<IslandCaptureRecord> records = session.records();
            assertEquals(2, records.size());
            IslandExportDocument redacted = export(records, null, Mode.REDACTED, authorized(LockState.UNLOCKED, false));
            boolean utf16 = false, numberType = false, structure = false, invalid = false;
            for (IslandExportDocument.Redaction change : redacted.redactions) {
                if (change.path.equals("/samples/0/bodyText")) {
                    assertEquals(Integer.valueOf(4), change.originalUtf16Length); utf16 = true;
                }
                if (change.path.endsWith("/title/0")) {
                    assertEquals("number", change.originalType); assertEquals("string", change.exportedType); numberType = true;
                }
                if (change.reason.equals("unsafe-key")) { assertTrue(change.structureChanged); structure = true; }
                if (change.reason.equals("invalid-json")) { assertTrue(change.structureChanged); invalid = true; }
            }
            assertTrue(utf16); assertTrue(numberType); assertTrue(structure); assertTrue(invalid);
            Map<String, Object> projectedRaw = object(IslandExportJson.parse((String) object(
                    object(samples(redacted).get(0)).get("rawParamJSON")).get("value")));
            assertEquals("37", ((IslandExportJson.NumberToken) object(projectedRaw.get("progressInfo")).get("progress")).text);
            assertFalse(redacted.json.contains("person-secret")); assertFalse(redacted.json.contains("invalid-json-private"));
            assertFalse(redacted.markdown.contains("person-secret"));
            IslandExportDocument full = export(records, null, Mode.FULL, authorized(LockState.UNLOCKED, true));
            assertEquals(attack, object(samples(full).get(1)).get("bodyText"));
            assertFalse(full.markdown.contains("\n![remote]")); assertFalse(full.markdown.contains("\n<script>"));
            assertFalse(full.markdown.contains("\n```")); assertFalse(full.markdown.contains("\n# heading"));
            assertTrue(full.markdown.contains("\\u000a![remote]"));
        }
    }

    @Test public void lockDuringRealCaptureProducesOnlyPlaceholderAndCannotBackfillOnExport() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        try (IslandCaptureSession session = enabledSession()) {
            try {
                assertEquals(IslandCaptureSession.Submission.QUEUED,
                        session.offer(PACKAGE, 0, "inflight-key", IdentityAuthorization.CONFIRMED, () -> {
                            reads.incrementAndGet(); entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test latch timeout");
                            return new IslandCaptureSession.BodyFields("never-export", "{}", "{}");
                        }));
                assertTrue("worker did not enter source", entered.await(5, TimeUnit.SECONDS));
                session.setLockState(IslandCaptureSession.LockState.LOCKED);
            } finally { release.countDown(); }
            idle(session);
            assertEquals(1, reads.get());
            List<IslandCaptureRecord> records = session.records();
            assertEquals(IslandCaptureRecord.State.LOCKED_PLACEHOLDER, records.get(0).state);
            session.setLockState(IslandCaptureSession.LockState.UNLOCKED);
            IslandExportDocument full = export(records, null, Mode.FULL, authorized(LockState.UNLOCKED, true));
            assertNull(object(samples(full).get(0)).get("bodyText"));
            assertFalse(full.json.contains("never-export")); assertFalse(full.markdown.contains("never-export"));
        }
    }

    private static File fixtureFile(File file) {
        assertTrue("readonly fixture is missing", file.isFile()); return file;
    }
    private static String readFixture(File file) throws IOException {
        return new String(Files.readAllBytes(fixtureFile(file).toPath()), StandardCharsets.UTF_8);
    }
    private static String ruleText() throws IOException { return readFixture(RULE_FILE); }
    private static Map<String, Object> ruleTree() throws IOException { return object(IslandExportJson.parse(ruleText())); }
    private static Map<String, Object> notificationFixture() throws IOException {
        return object(IslandExportJson.parse(readFixture(NOTIFICATION_FILE)));
    }
    private static String encode(Object tree) { return IslandExportJson.write(tree, 65536); }
    private static IslandRuleSetLoader.Source realSource(String id, boolean enabled, int priority) {
        return IslandRuleSetLoader.Source.fromFile(id, 0, enabled, priority, fixtureFile(RULE_FILE));
    }
    private static IslandRuleSetLoader.Source memorySource(String id, boolean enabled, int priority, String json) {
        byte[] encoded = json.getBytes(StandardCharsets.UTF_8);
        return new IslandRuleSetLoader.Source(id, 0, enabled, priority, () -> new ByteArrayInputStream(encoded));
    }
    private static String fourClauseRule() throws IOException {
        Map<String, Object> tree = ruleTree();
        tree.put("match", map("all", Arrays.asList(
                map("field", "packageName", "op", "equals", "value", PACKAGE),
                map("field", "protocolVersion", "op", "equals", "value", "1"),
                map("field", "business", "op", "equals", "value", "train"),
                map("field", "scene", "op", "equals", "value", "personal-assistant-travel"))));
        return encode(tree);
    }
    private static IslandRuleContext travelContext() {
        return new IslandRuleContext(PACKAGE, "1", "train", "personal-assistant-travel");
    }
    private static List<String> ids(List<IslandRuleSet.Entry> entries) {
        List<String> ids = new ArrayList<>(); for (IslandRuleSet.Entry entry : entries) ids.add(entry.id); return ids;
    }
    private static List<String> recordIds(List<IslandCaptureRecord> records) {
        List<String> ids = new ArrayList<>(); for (IslandCaptureRecord record : records) ids.add(record.id); return ids;
    }
    private static List<IslandCaptureRecord.State> states(List<IslandCaptureRecord> records) {
        List<IslandCaptureRecord.State> states = new ArrayList<>();
        for (IslandCaptureRecord record : records) states.add(record.state); return states;
    }
    private static IslandCaptureSession enabledSession() {
        IslandCaptureSession session = new IslandCaptureSession();
        session.enable(Collections.singleton(PACKAGE)); session.setLockState(IslandCaptureSession.LockState.UNLOCKED);
        return session;
    }
    private static void idle(IslandCaptureSession session) throws InterruptedException {
        assertTrue("capture worker did not become idle", session.awaitIdle(5, TimeUnit.SECONDS));
    }
    private static List<IslandCaptureRecord> captureFourStates() throws Exception {
        Map<String, Object> fixture = notificationFixture();
        String raw = (String) fixture.get("rawParam");
        String body = (String) object(fixture.get("expected")).get("builtinFullText");
        AtomicInteger forbiddenReads = new AtomicInteger();
        try (IslandCaptureSession session = enabledSession()) {
            assertEquals(IslandCaptureSession.Submission.QUEUED, session.offer(PACKAGE, 0, "real-captured-key",
                    IdentityAuthorization.CONFIRMED, () -> new IslandCaptureSession.BodyFields(body, raw, "{}")));
            idle(session);
            session.setLockState(IslandCaptureSession.LockState.LOCKED);
            assertEquals(IslandCaptureSession.Submission.QUEUED, session.offer(PACKAGE, 0, "locked-key",
                    IdentityAuthorization.CONFIRMED, () -> {
                        forbiddenReads.incrementAndGet(); return new IslandCaptureSession.BodyFields("locked-secret", raw, "{}");
                    }));
            idle(session);
            session.setLockState(IslandCaptureSession.LockState.UNLOCKED);
            assertEquals(IslandCaptureSession.Submission.AUTH_UNVERIFIED, session.offer(PACKAGE, 0, "unverified-key",
                    IdentityAuthorization.UNVERIFIED, () -> {
                        forbiddenReads.incrementAndGet(); return new IslandCaptureSession.BodyFields("unverified-secret", raw, "{}");
                    }));
            assertEquals(IslandCaptureSession.Submission.QUEUED, session.offer(PACKAGE, 0, "read-error-key",
                    IdentityAuthorization.CONFIRMED, () -> { throw new IOException("private exception must not be exported"); }));
            idle(session);
            assertEquals(0, forbiddenReads.get());
            assertEquals(4, session.records().size());
            return session.records();
        }
    }
    private static IslandExportState authorized(LockState lock, boolean fullConfirmed) {
        return new IslandExportState(lock, ExportAuthorization.AUTHORIZED,
                fullConfirmed ? FullConfirmation.CONFIRMED : FullConfirmation.NOT_CONFIRMED, EXPORT_GENERATION);
    }
    private static IslandExportRequest request(List<IslandCaptureRecord> records, List<Integer> selection, Mode mode) {
        return new IslandExportRequest(records, selection, Collections.singleton(PACKAGE), mode);
    }
    private IslandExportDocument export(List<IslandCaptureRecord> records, List<Integer> selection, Mode mode,
            IslandExportState state) {
        return export(request(records, selection, mode), state);
    }
    private IslandExportDocument export(IslandExportRequest request, IslandExportState state) {
        IslandExportGenerator.ExportPlan plan = generator.begin(request, state);
        while (plan.nextIndex < plan.frozenSamples.size()) {
            IslandExportGenerator.Step step = generator.renderNextSample(plan, state);
            assertNull(step.incomplete); assertNull(step.jsonFragment); assertNull(step.markdownFragment);
            plan = step.plan;
        }
        IslandExportDocument document = generator.finish(plan, state);
        assertTrue(document.complete); assertTrue(document.meta.complete); assertEquals(Reason.NONE, document.reason);
        return document;
    }
    private void reject(IslandExportRequest request, IslandExportState state, Reason reason) {
        try { generator.begin(request, state); fail("expected fixed export rejection"); }
        catch (IslandExportGenerator.Rejected failure) { assertEquals(reason, failure.reason); }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Object> samples(IslandExportDocument document) {
        return (List<Object>) object(IslandExportJson.parse(document.json)).get("samples");
    }
    private static void assertComparisonNotObserved(Map<String, Object> sample) {
        Map<String, Object> comparison = object(sample.get("comparison"));
        assertEquals(4, comparison.size()); assertEquals("not-observed", comparison.get("status"));
        for (String field : Arrays.asList("before", "after", "changed")) {
            assertTrue(comparison.containsKey(field)); assertNull(comparison.get(field));
        }
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) map.put((String) pairs[index], pairs[index + 1]);
        return map;
    }
}
