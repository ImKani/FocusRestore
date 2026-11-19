/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hyperos3.focusrestore.IslandExportDocument.Reason;
import com.hyperos3.focusrestore.IslandExportDocument.Redaction;
import com.hyperos3.focusrestore.IslandExportGenerator.ExportPlan;
import com.hyperos3.focusrestore.IslandExportGenerator.Step;
import com.hyperos3.focusrestore.IslandExportRequest.Mode;
import com.hyperos3.focusrestore.IslandExportState.ExportAuthorization;
import com.hyperos3.focusrestore.IslandExportState.FullConfirmation;
import com.hyperos3.focusrestore.IslandExportState.LockState;

import static org.junit.Assert.*;

/** C2-4 仅内存合同测试；不调用捕获会话、IO、线程或 Android。 */
public class IslandExportGeneratorTest {
    private static final String PACKAGE = "example.app";
    private static final String PLACEHOLDER = "<redacted:string>";
    private static final String[] STRUCTURAL = {
            "/stepInfo/step", "/progressInfo/progress", "/progressInfo/progressInfo/progress",
            "/multiProgressInfo/progress", "/multiProgressInfo/progressInfo/progress",
            "/param_island/bigIslandArea/progressTextInfo/progress",
            "/param_island/bigIslandArea/progressTextInfo/progressInfo/progress",
            "/param_island/bigIslandArea/fixedWidthDigitInfo/progress",
            "/param_island/bigIslandArea/fixedWidthDigitInfo/progressInfo/progress",
            "/param_island/bigIslandArea/sameWidthDigitInfo/progress",
            "/param_island/bigIslandArea/sameWidthDigitInfo/progressInfo/progress"
    };
    private final IslandExportGenerator generator = new IslandExportGenerator();

    @Test public void redactedPreservesEveryExactStructuralPath() {
        for (String path : STRUCTURAL) {
            Map<String, Object> tree = new LinkedHashMap<String, Object>();
            putPath(tree, path, 37);
            IslandExportDocument document = export(Mode.REDACTED, captured("{}", json(tree), "{}"));
            assertEquals(path, "37", number(at(raw(sample(document, 0)), path)));
            assertFalse(hasRedaction(document, "/rawParamJSON/value" + path));
        }
    }

    @Test public void paramV2WrapperRetainsProgressButRedactsTitle() {
        String raw = "{\"param_v2\":{\"progressInfo\":{\"progress\":12,\"title\":\"secret-title\"}}}";
        IslandExportDocument document = export(Mode.REDACTED, captured("{}", raw, "{}"));
        Map<String, Object> progress = object(at(raw(sample(document, 0)), "/param_v2/progressInfo"));
        assertEquals("12", number(progress.get("progress")));
        assertEquals(PLACEHOLDER, progress.get("title"));
        assertFalse(document.json.contains("secret-title"));
        assertFalse(document.markdown.contains("secret-title"));
    }

    @Test public void unregisteredProgressNameDoesNotAcquireStructuralPrivilege() {
        IslandExportDocument document = export(Mode.REDACTED,
                captured("{}", "{\"progress\":99,\"title\":\"private\"}", "{}"));
        Map<String, Object> tree = object(raw(sample(document, 0)));
        assertFalse(tree.containsKey("progress"));
        assertEquals(PLACEHOLDER, tree.get("title"));
        assertTrue(document.redactions.stream().anyMatch(change -> change.structureChanged));
    }

    @Test public void unsafeDynamicKeyAndItsSubtreeDoNotLeakIntoRedactionPaths() {
        String secretKey = "private-person-123@example.invalid";
        IslandExportDocument document = export(Mode.REDACTED,
                captured("{}", "{\"" + secretKey + "\":{\"title\":\"hidden-value\"},\"title\":\"ok\"}", "{}"));
        assertFalse(document.json.contains(secretKey));
        assertFalse(document.markdown.contains(secretKey));
        assertFalse(document.json.contains("hidden-value"));
        Redaction removed = null;
        for (Redaction change : document.redactions) {
            assertFalse(change.path.contains(secretKey));
            if (change.action.equals("remove-subtree")) removed = change;
        }
        assertNotNull(removed);
        assertEquals("/samples/0/rawParamJSON/value", removed.path);
        assertTrue(removed.structureChanged);
        assertEquals("object", removed.originalType);
        assertNull(removed.originalUtf16Length);
    }

    @Test public void arraysKeepPositionsAndNullWhileReplacingScalarTypes() {
        IslandExportDocument document = export(Mode.REDACTED,
                captured("{}", "{\"title\":[\"private\",12,true,null]}", "{}"));
        List<Object> title = list(object(raw(sample(document, 0))).get("title"));
        assertEquals(4, title.size());
        assertEquals(PLACEHOLDER, title.get(0));
        assertEquals("<redacted:number>", title.get(1));
        assertEquals("<redacted:boolean>", title.get(2));
        assertNull(title.get(3));
        Redaction number = redaction(document, "/samples/0/rawParamJSON/value/title/1");
        assertEquals("number", number.originalType);
        assertEquals("string", number.exportedType);
        assertEquals(Integer.valueOf(2), number.originalUtf16Length);
        assertEquals(Integer.valueOf("<redacted:number>".length()), number.exportedUtf16Length);
    }

    @Test public void allowedPackageRetainedIdentityFieldsReplacedAndAbsentFieldsNotInvented() {
        IslandExportDocument document = export(Mode.REDACTED, captured("private", "{}", "{}"));
        Map<String, Object> sbn = object(sample(document, 0).get("sbn"));
        assertEquals(PACKAGE, sbn.get("packageName"));
        assertEquals("<redacted:number>", sbn.get("userId"));
        assertEquals(PLACEHOLDER, sbn.get("key"));
        assertFalse(sbn.containsKey("uid"));
        assertFalse(sbn.containsKey("postTime"));
        assertFalse(document.json.contains("bodyBytes"));
        assertEquals(PLACEHOLDER, sample(document, 0).get("bodyText"));
    }

    @Test public void packageOutsideExportAllowlistIsRedacted() {
        IslandExportRequest request = new IslandExportRequest(Collections.singletonList(captured()), null,
                Collections.<String>emptySet(), Mode.REDACTED);
        IslandExportDocument document = finish(request, authorized());
        assertEquals(PLACEHOLDER, object(sample(document, 0).get("sbn")).get("packageName"));
        assertEquals("package-not-allowlisted", redaction(document, "/samples/0/sbn/packageName").reason);
    }

    @Test public void utf16MetadataCountsSupplementaryCharactersAsTwoUnits() {
        String original = "A\uD83D\uDE80中";
        IslandExportDocument document = export(Mode.REDACTED, captured(original, "{}", "{}"));
        Redaction change = redaction(document, "/samples/0/bodyText");
        assertEquals(Integer.valueOf(4), change.originalUtf16Length);
        assertEquals(Integer.valueOf(PLACEHOLDER.length()), change.exportedUtf16Length);
        assertEquals("string", change.originalType);
        assertEquals("string", change.exportedType);
        assertEquals("replace", change.action);
        assertEquals("free-text", change.reason);
        assertFalse(change.structureChanged);
        Map<String, Object> encoded = object(list(sample(document, 0).get("redactions")).get(0));
        assertTrue(encoded.containsKey("originalUtf16Length"));
        assertTrue(encoded.containsKey("exportedUtf16Length"));
        assertTrue(encoded.containsKey("originalType"));
        assertTrue(encoded.containsKey("exportedType"));
    }

    @Test public void fullRetainsCapturedValuesWithoutSourceKeyOrSbnInventedValues() {
        String raw = "{ \"title\": \"source-secret\" }";
        IslandCaptureRecord record = captured("body-original", raw, "{}");
        IslandExportDocument document = export(Mode.FULL, record);
        Map<String, Object> sample = sample(document, 0);
        assertEquals("body-original", sample.get("bodyText"));
        Map<String, Object> representation = object(sample.get("rawParamJSON"));
        assertEquals(raw, representation.get("value"));
        assertNull(representation.get("sourceKey"));
        assertEquals("original-json-text", representation.get("representation"));
        Map<String, Object> sbn = object(sample.get("sbn"));
        assertEquals(PACKAGE, sbn.get("packageName"));
        assertEquals("10", number(sbn.get("userId")));
        assertEquals("notification-key", sbn.get("key"));
        assertFalse(sbn.containsKey("postTime"));
        assertFalse(sbn.containsKey("uid"));
        assertTrue(document.redactions.isEmpty());
        assertFalse(document.meta.replayableOriginal);
    }

    @Test public void comparisonAlwaysContainsCompleteNotObservedShape() {
        for (Mode mode : Mode.values()) {
            Map<String, Object> comparison = object(sample(export(mode, captured()), 0).get("comparison"));
            assertEquals(4, comparison.size());
            assertEquals("not-observed", comparison.get("status"));
            assertTrue(comparison.containsKey("before"));
            assertTrue(comparison.containsKey("after"));
            assertTrue(comparison.containsKey("changed"));
            assertNull(comparison.get("before"));
            assertNull(comparison.get("after"));
            assertNull(comparison.get("changed"));
        }
    }

    @Test public void beginRejectsUnverifiedAndDeniedAuthorizationInBothModes() {
        for (Mode mode : Mode.values()) {
            for (ExportAuthorization auth : new ExportAuthorization[] {
                    ExportAuthorization.UNVERIFIED, ExportAuthorization.DENIED }) {
                reject(request(mode, captured()), state(LockState.UNLOCKED, auth, FullConfirmation.CONFIRMED, 7), Reason.UNAUTHORIZED);
            }
        }
    }

    @Test public void beginFullRequiresIndependentConfirmation() {
        reject(request(Mode.FULL, captured()), state(LockState.UNLOCKED, ExportAuthorization.AUTHORIZED,
                FullConfirmation.NOT_CONFIRMED, 7), Reason.FULL_NOT_CONFIRMED);
    }

    @Test public void beginFullRejectsLockedAndUnknown() {
        for (LockState lock : new LockState[] { LockState.LOCKED, LockState.UNKNOWN })
            reject(request(Mode.FULL, captured()), state(lock, ExportAuthorization.AUTHORIZED,
                    FullConfirmation.CONFIRMED, 7), Reason.FULL_LOCKED);
    }

    @Test public void fullRejectsPackageNotAuthorizedForExport() {
        reject(new IslandExportRequest(Collections.singletonList(captured()), null,
                Collections.<String>emptySet(), Mode.FULL), authorized(), Reason.UNAUTHORIZED);
    }

    @Test public void redactedLockedAndUnknownCanExportHistoryAndOnlyIdentityFreePlaceholder() {
        for (LockState lock : new LockState[] { LockState.LOCKED, LockState.UNKNOWN }) {
            IslandCaptureRecord placeholder = new IslandCaptureRecord(2, 2, 0,
                    IslandCaptureRecord.State.LOCKED_PLACEHOLDER, IslandCaptureRecord.Reason.LOCKED,
                    "should-not-survive", 99, "hidden-key", "hidden-body", "{}", "{}", 999);
            IslandExportDocument document = finish(request(Mode.REDACTED, captured("prior-secret", "{}", "{}"), placeholder),
                    state(lock, ExportAuthorization.AUTHORIZED, FullConfirmation.NOT_CONFIRMED, 7));
            assertTrue(document.complete);
            assertFalse(document.json.contains("prior-secret"));
            assertFalse(document.json.contains("hidden-body"));
            Map<String, Object> item = sample(document, 1);
            assertEquals("LOCKED_PLACEHOLDER", item.get("state"));
            assertNull(item.get("bodyText"));
            assertNull(item.get("extras"));
            assertNull(item.get("rawParamJSON"));
            for (Object value : object(item.get("sbn")).values()) assertNull(value);
        }
    }

    @Test public void renderGenerationInvalidationReturnsPermanentTerminalPlan() {
        assertTerminal(state(LockState.UNLOCKED, ExportAuthorization.AUTHORIZED, FullConfirmation.CONFIRMED, 8), Reason.SESSION_CHANGED);
    }

    @Test public void renderAuthorizationLossReturnsPermanentTerminalPlan() {
        assertTerminal(state(LockState.UNLOCKED, ExportAuthorization.DENIED, FullConfirmation.CONFIRMED, 7), Reason.UNAUTHORIZED);
    }

    @Test public void renderRelockReturnsPermanentTerminalPlan() {
        assertTerminal(state(LockState.LOCKED, ExportAuthorization.AUTHORIZED, FullConfirmation.CONFIRMED, 7), Reason.LOCK_STATE_CHANGED);
    }

    @Test public void renderConfirmationLossReturnsPermanentTerminalPlan() {
        assertTerminal(state(LockState.UNLOCKED, ExportAuthorization.AUTHORIZED, FullConfirmation.NOT_CONFIRMED, 7), Reason.CONFIRMATION_LOST);
    }

    @Test public void finishRechecksAllStateConditionsAfterAllSamplesRendered() {
        IslandExportState[] changed = {
                state(LockState.UNLOCKED, ExportAuthorization.AUTHORIZED, FullConfirmation.CONFIRMED, 8),
                state(LockState.UNLOCKED, ExportAuthorization.UNVERIFIED, FullConfirmation.CONFIRMED, 7),
                state(LockState.UNKNOWN, ExportAuthorization.AUTHORIZED, FullConfirmation.CONFIRMED, 7),
                state(LockState.UNLOCKED, ExportAuthorization.AUTHORIZED, FullConfirmation.NOT_CONFIRMED, 7)
        };
        Reason[] reasons = { Reason.SESSION_CHANGED, Reason.UNAUTHORIZED, Reason.LOCK_STATE_CHANGED, Reason.CONFIRMATION_LOST };
        for (int i = 0; i < changed.length; i++) {
            ExportPlan plan = generator.begin(request(Mode.FULL, captured()), authorized());
            plan = generator.renderNextSample(plan, authorized()).plan;
            IslandExportDocument document = generator.finish(plan, changed[i]);
            assertFalse(document.complete);
            assertEquals(reasons[i], document.reason);
            assertNull(document.json);
            assertNull(document.markdown);
        }
    }

    @Test public void finishBeforeRenderingIsIncompleteWithoutPartialDocuments() {
        ExportPlan plan = generator.begin(request(Mode.REDACTED, captured()), authorized());
        IslandExportDocument document = generator.finish(plan, authorized());
        assertEquals(Reason.NOT_FINISHED, document.reason);
        assertFalse(document.complete);
        assertNull(document.json);
        assertNull(document.markdown);
    }

    @Test public void explicitSelectionPreservesChosenOrderAndIsDefensivelyCopied() {
        List<IslandCaptureRecord> source = new ArrayList<IslandCaptureRecord>(Arrays.asList(record(1), record(2), record(3)));
        List<Integer> selected = new ArrayList<Integer>(Arrays.asList(2, 0));
        Set<String> packages = new HashSet<String>(Collections.singleton(PACKAGE));
        IslandExportRequest request = new IslandExportRequest(source, selected, packages, Mode.FULL);
        source.clear(); selected.clear(); packages.clear();
        IslandExportDocument document = finish(request, authorized());
        assertEquals(2, samples(document).size());
        assertEquals("capture-2-3", sample(document, 0).get("id"));
        assertEquals("capture-2-1", sample(document, 1).get("id"));
        assertEquals(PACKAGE, object(sample(document, 0).get("sbn")).get("packageName"));
    }

    @Test public void duplicateOrOutOfRangeSelectionIsRejected() {
        List<IslandCaptureRecord> source = Collections.singletonList(captured());
        for (List<Integer> selection : Arrays.asList(Arrays.asList(0, 0), Arrays.asList(1), Arrays.asList(-1)))
            reject(new IslandExportRequest(source, selection, Collections.singleton(PACKAGE), Mode.REDACTED), authorized(), Reason.INVALID_REQUEST);
    }

    @Test public void emptySelectionIsCompleteAndStillChecksAuthorization() {
        IslandExportRequest request = new IslandExportRequest(Collections.singletonList(captured()),
                Collections.<Integer>emptyList(), Collections.singleton(PACKAGE), Mode.REDACTED);
        IslandExportDocument document = finish(request, authorized());
        assertTrue(document.complete);
        assertEquals(0, document.meta.sampleCount);
        assertTrue(samples(document).isEmpty());
        reject(request, state(LockState.UNLOCKED, ExportAuthorization.DENIED, FullConfirmation.CONFIRMED, 7), Reason.UNAUTHORIZED);
    }

    @Test public void sampleCount64Accepted65RejectedWithoutPartialExport() {
        List<IslandCaptureRecord> records = new ArrayList<IslandCaptureRecord>();
        for (int i = 0; i < 64; i++) records.add(record(i));
        IslandExportDocument document = finish(new IslandExportRequest(records, null, Collections.singleton(PACKAGE), Mode.FULL), authorized());
        assertEquals(64, document.meta.sampleCount);
        assertEquals(64, samples(document).size());
        records.add(record(64));
        reject(new IslandExportRequest(records, null, Collections.singleton(PACKAGE), Mode.FULL), authorized(), Reason.SAMPLE_LIMIT);
    }

    @Test public void jsonContainerMetadataAndSampleOrderAreExplicit() {
        IslandExportDocument document = export(Mode.REDACTED, record(4), record(5));
        Map<String, Object> root = root(document);
        assertEquals(2, root.size());
        Map<String, Object> meta = object(root.get("meta"));
        assertEquals("1", number(meta.get("formatVersion")));
        assertEquals("draft", meta.get("documentStatus"));
        assertEquals("redacted", meta.get("exportMode"));
        assertEquals(Boolean.TRUE, meta.get("complete"));
        assertEquals(Boolean.FALSE, meta.get("replayableOriginal"));
        assertNotNull(meta.get("sessionId"));
        assertEquals("capture-2-4", sample(document, 0).get("id"));
        assertEquals("capture-2-5", sample(document, 1).get("id"));
    }

    @Test public void markdownUsesIndentedJsonAndCannotActivateMaliciousNewlines() {
        String attack = "first\n![image](https://example.invalid/x)\n[link](https://example.invalid/)\n<script>alert(1)</script>\n```\n# injected";
        IslandExportDocument document = export(Mode.FULL, captured(attack, "{}", "{}"));
        assertTrue(document.markdown.contains("### Overview"));
        assertTrue(document.markdown.contains("### Original fields / redacted representation"));
        assertTrue(document.markdown.contains("### Before / after comparison"));
        assertTrue(document.markdown.contains("### Redactions / errors"));
        assertFalse(document.markdown.contains("\n![image]"));
        assertFalse(document.markdown.contains("\n[link]"));
        assertFalse(document.markdown.contains("\n<script>"));
        assertFalse(document.markdown.contains("\n```"));
        assertFalse(document.markdown.contains("\n# injected"));
        assertTrue(document.markdown.contains("\\u000a![image]"));
        for (String line : document.markdown.split("\n")) {
            if (line.contains("![image]") || line.contains("<script>")) assertTrue(line.startsWith("    {"));
        }
    }

    @Test public void markdownNeverRestoresTextRemovedFromJson() {
        IslandExportDocument document = export(Mode.REDACTED,
                captured("body-secret", "{\"title\":\"raw-secret\"}", "{\"miui.focus.ticker\":\"ticker-secret\"}"));
        for (String secret : Arrays.asList("body-secret", "raw-secret", "ticker-secret")) {
            assertFalse(document.json.contains(secret));
            assertFalse(document.markdown.contains(secret));
        }
        assertTrue(document.markdown.contains(PLACEHOLDER));
    }

    @Test public void invalidRawJsonUsesFixedPlaceholderAndReason() {
        IslandExportDocument document = export(Mode.REDACTED, captured("{}", "not-json-secret", "{}"));
        Map<String, Object> raw = object(sample(document, 0).get("rawParamJSON"));
        assertEquals(PLACEHOLDER, raw.get("value"));
        Redaction change = redaction(document, "/samples/0/rawParamJSON/value");
        assertEquals("invalid-json", change.reason);
        assertTrue(change.structureChanged);
        assertFalse(document.json.contains("not-json-secret"));
    }

    @Test public void invalidExtrasJsonRedactsButFullRejects() {
        IslandCaptureRecord record = captured("{}", "{}", "invalid-extras-secret");
        IslandExportDocument redacted = export(Mode.REDACTED, record);
        assertEquals(PLACEHOLDER, sample(redacted, 0).get("extras"));
        assertEquals("invalid-json", redaction(redacted, "/samples/0/extras").reason);
        ExportPlan plan = generator.begin(request(Mode.FULL, record), authorized());
        Step step = generator.renderNextSample(plan, authorized());
        assertEquals(Reason.UNSAFE_EXTRAS, step.incomplete.reason);
        assertNull(step.jsonFragment);
        assertNull(step.markdownFragment);
    }

    @Test public void extrasWhitelistRetainsDescriptorsAndRedactsTickerOnly() {
        String extras = "{\"miui.focus.isFocus\":true,\"miui.focus.param\":{\"present\":true,\"valueType\":\"string\",\"length\":21},"
                + "\"miui.focus.rv\":{\"present\":true,\"valueType\":\"remote-views\"},\"miui.focus.ticker\":\"ticker-private\"}";
        IslandExportDocument document = export(Mode.REDACTED, captured("{}", "{}", extras));
        Map<String, Object> result = object(sample(document, 0).get("extras"));
        assertEquals(Boolean.TRUE, result.get("miui.focus.isFocus"));
        assertEquals("21", number(object(result.get("miui.focus.param")).get("length")));
        assertEquals("remote-views", object(result.get("miui.focus.rv")).get("valueType"));
        assertEquals(PLACEHOLDER, result.get("miui.focus.ticker"));
        assertEquals(4, result.size());
    }

    @Test public void unknownExtrasAreRemovedWithoutLeakingNamesAndFullFailsClosed() {
        IslandCaptureRecord record = captured("{}", "{}", "{\"person-secret-field\":\"private\",\"miui.focus.isFocus\":false}");
        IslandExportDocument document = export(Mode.REDACTED, record);
        assertEquals(1, object(sample(document, 0).get("extras")).size());
        assertFalse(document.json.contains("person-secret-field"));
        assertEquals("unsafe-extras-field", redaction(document, "/samples/0/extras").reason);
        Step failure = generator.renderNextSample(generator.begin(request(Mode.FULL, record), authorized()), authorized());
        assertEquals(Reason.UNSAFE_EXTRAS, failure.incomplete.reason);
        assertFalse(failure.incomplete.complete);
    }

    @Test public void immutableCollectionsRejectMutationAndOldPlanDoesNotAdvance() {
        IslandCaptureRecord record = captured();
        IslandExportRequest request = request(Mode.REDACTED, record);
        ExportPlan original = generator.begin(request, authorized());
        Step step = generator.renderNextSample(original, authorized());
        assertEquals(0, original.nextIndex);
        assertEquals(1, step.plan.nextIndex);
        assertSame(record, original.frozenSamples.get(0));
        assertUnsupported(() -> request.records.clear());
        assertUnsupported(() -> request.allowedPackages.clear());
        assertUnsupported(() -> original.frozenSamples.clear());
        IslandExportDocument document = generator.finish(step.plan, authorized());
        assertUnsupported(() -> document.redactions.clear());
        assertEquals("{}", record.bodyText);
        assertEquals("{}", record.rawParamJSON);
        assertEquals("{}", record.extrasJson);
        assertEquals(LockState.UNLOCKED, authorized().lockState);
    }

    @Test public void stateNullsFailClosedAndInputSnapshotsRemainUnchanged() {
        IslandExportState unknown = new IslandExportState(null, null, null, 7);
        assertEquals(LockState.UNKNOWN, unknown.lockState);
        assertEquals(ExportAuthorization.UNVERIFIED, unknown.exportAuth);
        assertEquals(FullConfirmation.NOT_CONFIRMED, unknown.fullConfirm);
        reject(request(Mode.REDACTED, captured()), unknown, Reason.UNAUTHORIZED);
        IslandExportState state = authorized();
        ExportPlan plan = generator.begin(request(Mode.FULL, captured()), state);
        generator.renderNextSample(plan, state);
        assertEquals(7, state.sessionGeneration);
        assertEquals(LockState.UNLOCKED, state.lockState);
        assertEquals(ExportAuthorization.AUTHORIZED, state.exportAuth);
        assertEquals(FullConfirmation.CONFIRMED, state.fullConfirm);
    }

    private void assertTerminal(IslandExportState changed, Reason reason) {
        ExportPlan original = generator.begin(request(Mode.FULL, captured(), record(2)), authorized());
        ExportPlan one = generator.renderNextSample(original, authorized()).plan;
        Step stopped = generator.renderNextSample(one, changed);
        assertNotNull(stopped.incomplete);
        assertEquals(reason, stopped.incomplete.reason);
        assertFalse(stopped.incomplete.complete);
        assertNull(stopped.jsonFragment);
        assertNull(stopped.markdownFragment);
        assertEquals(1, stopped.plan.nextIndex);
        Step retried = generator.renderNextSample(stopped.plan, authorized());
        assertNotNull(retried.incomplete);
        assertEquals(reason, retried.incomplete.reason);
        assertEquals(1, retried.plan.nextIndex);
        IslandExportDocument finished = generator.finish(retried.plan, authorized());
        assertEquals(reason, finished.reason);
        assertFalse(finished.complete);
        assertNull(finished.json);
        assertNull(finished.markdown);
    }

    private IslandExportDocument export(Mode mode, IslandCaptureRecord... records) {
        return finish(request(mode, records), authorized());
    }
    private IslandExportDocument finish(IslandExportRequest request, IslandExportState state) {
        ExportPlan plan = generator.begin(request, state);
        while (plan.nextIndex < plan.frozenSamples.size()) {
            Step step = generator.renderNextSample(plan, state);
            assertNull(step.incomplete);
            // render 只返回不可变计划；片段不对外交付，容量检查完成前不得形成部分文档。
            assertNull(step.jsonFragment);
            assertNull(step.markdownFragment);
            plan = step.plan;
        }
        IslandExportDocument document = generator.finish(plan, state);
        assertTrue(document.complete);
        assertEquals(Reason.NONE, document.reason);
        return document;
    }
    private void reject(IslandExportRequest request, IslandExportState state, Reason reason) {
        try { generator.begin(request, state); fail("expected " + reason); }
        catch (IslandExportGenerator.Rejected rejected) {
            assertEquals(reason, rejected.reason);
            assertEquals(reason.name(), rejected.getMessage());
        }
    }
    private static IslandExportRequest request(Mode mode, IslandCaptureRecord... records) {
        return new IslandExportRequest(Arrays.asList(records), null, Collections.singleton(PACKAGE), mode);
    }
    private static IslandExportState authorized() {
        return state(LockState.UNLOCKED, ExportAuthorization.AUTHORIZED, FullConfirmation.CONFIRMED, 7);
    }
    private static IslandExportState state(LockState lock, ExportAuthorization authorization,
            FullConfirmation confirmation, long generation) {
        return new IslandExportState(lock, authorization, confirmation, generation);
    }
    private static IslandCaptureRecord captured() { return captured("{}", "{}", "{}"); }
    private static IslandCaptureRecord captured(String body, String raw, String extras) {
        // 内部 bodyBytes 故意与内容不一致，导出不能读取它来筛选或生成文档。
        return new IslandCaptureRecord(2, 1, 0, IslandCaptureRecord.State.CAPTURED, null,
                PACKAGE, 10, "notification-key", body, raw, extras, Long.MAX_VALUE);
    }
    private static IslandCaptureRecord record(long sequence) {
        return new IslandCaptureRecord(2, sequence, 0, IslandCaptureRecord.State.CAPTURED, null,
                PACKAGE, 10, "notification-key", "{}", "{}", "{}", 0);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return (List<Object>) value; }
    private static Map<String, Object> root(IslandExportDocument document) { return object(IslandExportJson.parse(document.json)); }
    private static List<Object> samples(IslandExportDocument document) { return list(root(document).get("samples")); }
    private static Map<String, Object> sample(IslandExportDocument document, int index) { return object(samples(document).get(index)); }
    private static Object raw(Map<String, Object> sample) { return IslandExportJson.parse((String) object(sample.get("rawParamJSON")).get("value")); }
    private static String number(Object value) { return ((IslandExportJson.NumberToken) value).text; }
    private static String json(Object value) { return IslandExportJson.write(value, 2097152); }
    private static Object at(Object tree, String path) {
        Object current = tree;
        for (String key : path.substring(1).split("/")) current = object(current).get(key);
        return current;
    }
    private static void putPath(Map<String, Object> tree, String path, Object value) {
        String[] keys = path.substring(1).split("/");
        Map<String, Object> current = tree;
        for (int i = 0; i < keys.length - 1; i++) {
            Map<String, Object> child = new LinkedHashMap<String, Object>();
            current.put(keys[i], child);
            current = child;
        }
        current.put(keys[keys.length - 1], value);
    }
    private static boolean hasRedaction(IslandExportDocument document, String suffix) {
        for (Redaction change : document.redactions) if (change.path.endsWith(suffix)) return true;
        return false;
    }
    private static Redaction redaction(IslandExportDocument document, String path) {
        for (Redaction change : document.redactions) if (change.path.equals(path)) return change;
        fail("missing redaction " + path);
        return null;
    }
    private static void assertUnsupported(Runnable mutation) {
        try { mutation.run(); fail("collection must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }
}
