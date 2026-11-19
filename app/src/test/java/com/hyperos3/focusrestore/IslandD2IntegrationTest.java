/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.hyperos3.focusrestore.IslandCaptureInputAdapter.Builder;
import com.hyperos3.focusrestore.IslandCaptureInputAdapter.Code;
import com.hyperos3.focusrestore.IslandCaptureInputAdapter.Input;
import com.hyperos3.focusrestore.IslandCaptureInputAdapter.Source;
import com.hyperos3.focusrestore.IslandCaptureInputAdapter.Value;
import com.hyperos3.focusrestore.IslandCaptureRecord.Reason;
import com.hyperos3.focusrestore.IslandCaptureRecord.State;
import com.hyperos3.focusrestore.IslandCaptureSession.IdentityAuthorization;
import com.hyperos3.focusrestore.IslandCaptureSession.Submission;
import com.hyperos3.focusrestore.IslandExportRequest.Mode;
import com.hyperos3.focusrestore.IslandExportState.ExportAuthorization;
import com.hyperos3.focusrestore.IslandExportState.FullConfirmation;
import com.hyperos3.focusrestore.IslandExportState.LockState;

import static org.junit.Assert.*;

/**
 * D2 只组合封闭纯输入、真实 Session 工作线程和纯导出；不证明真实来源或身份认证。
 * 所有正文来自本测试的内存标量；不接 Android、文件写入、网络或真实通知。
 */
public class IslandD2IntegrationTest {
    private static final String PACKAGE = "test.d2.app";
    private static final String PRIMARY = "miui.focus.param";
    private static final String CUSTOM = "miui.focus.param.custom";
    private static final String TICKER = "miui.focus.ticker";
    private static final List<String> EXTRA_KEYS = Arrays.asList("miui.focus.isFocus", PRIMARY, CUSTOM,
            TICKER, "miui.focus.rv", "miui.focus.rvNight", "miui.focus.rvBar", "miui.focus.rvBarNight");

    @Test public void primaryWinsWithoutMergingAndFullExportPreservesUntrimmedStrings() throws Exception {
        String body = " \tbody 原文\n ";
        String primary = " \n{\"title\":\"primary-only\"}\t ";
        String custom = "{\"title\":\"custom-only\",\"progressInfo\":{\"progress\":81}}";
        Source source = source(builder(body, primary).extra(CUSTOM, Value.of(custom)).build());
        try (IslandCaptureSession session = enabledSession()) {
            assertEquals(0, source.readCount()); assertEquals(Code.NOT_READ, source.summary().code);
            IslandCaptureRecord record = capture(session, source);
            assertEquals(body, record.bodyText); assertEquals(primary, record.rawParamJSON);
            assertFalse(record.rawParamJSON.contains("custom-only"));
            assertFalse(record.rawParamJSON.contains("progressInfo"));
            assertEquals(PRIMARY, source.summary().selectedKey);
            assertEquals(1, source.readCount()); assertEquals(Code.READY, source.summary().code);
            IslandExportDocument full = export(session.records(), Mode.FULL, LockState.UNLOCKED);
            Map<String, Object> sample = sample(full, 0);
            assertEquals(body, sample.get("bodyText"));
            assertEquals(primary, object(sample.get("rawParamJSON")).get("value"));
            assertNull(object(sample.get("rawParamJSON")).get("sourceKey"));
            assertEquals(PACKAGE, object(sample.get("sbn")).get("packageName"));
            assertEquals("7", number(object(sample.get("sbn")).get("userId")));
            assertEquals("caller-key", object(sample.get("sbn")).get("key"));
            assertRecordHasOnlyApprovedPublicFields(record);
        }
    }

    @Test public void whitespaceOnlyPrimaryFallsBackToCustomAndPreservesItsOriginalWhitespace() throws Exception {
        String raw = " \t{\"title\":\"custom text\"}\n ";
        Source source = source(builder("body", " \t\r\n ").extra(CUSTOM, Value.of(raw)).build());
        try (IslandCaptureSession session = enabledSession()) {
            IslandCaptureRecord record = capture(session, source);
            assertEquals(raw, record.rawParamJSON); assertEquals(CUSTOM, source.summary().selectedKey);
            Map<String, Object> full = sample(export(session.records(), Mode.FULL, LockState.UNLOCKED), 0);
            assertEquals(raw, object(full.get("rawParamJSON")).get("value"));
            Map<String, Object> extras = object(full.get("extras"));
            assertEquals("5", number(object(extras.get(PRIMARY)).get("length")));
            assertFalse(object(extras.get(PRIMARY)).containsKey("value"));
        }
    }

    @Test public void wrongTypePrimaryCanFallBackToCustomWithoutStringifyingCallerObject() throws Exception {
        Object forbidden = new Object() {
            @Override public String toString() { throw new AssertionError("must not stringify input object"); }
        };
        Source source = source(new Builder().bodyText(Value.of("body"))
                .extra(PRIMARY, Value.of(forbidden)).extra(CUSTOM, Value.of("{\"title\":\"custom\"}")).build());
        try (IslandCaptureSession session = enabledSession()) {
            assertEquals("{\"title\":\"custom\"}", capture(session, source).rawParamJSON);
            assertEquals(CUSTOM, source.summary().selectedKey);
            Map<String, Object> extras = object(sample(export(session.records(), Mode.FULL, LockState.UNLOCKED), 0).get("extras"));
            assertEquals("wrong-type", object(extras.get(PRIMARY)).get("valueType"));
            assertFalse(object(extras.get(PRIMARY)).containsKey("value"));
        }
    }

    @Test public void nonblankPrimaryIsNotReplacedByCustomWhenPrimaryIsInvalidJson() throws Exception {
        String raw = " primary-private-non-json ";
        Source source = source(builder("body", raw).extra(CUSTOM, Value.of("{\"title\":\"valid custom\"}")).build());
        try (IslandCaptureSession session = enabledSession()) {
            assertEquals(raw, capture(session, source).rawParamJSON);
            assertEquals(PRIMARY, source.summary().selectedKey);
            assertEquals(raw, object(sample(export(session.records(), Mode.FULL, LockState.UNLOCKED), 0)
                    .get("rawParamJSON")).get("value"));
            IslandExportDocument redacted = export(session.records(), Mode.REDACTED, LockState.UNLOCKED);
            assertEquals("<redacted:string>", object(sample(redacted, 0).get("rawParamJSON")).get("value"));
            assertFalse(redacted.json.contains("primary-private-non-json"));
            boolean invalid = false;
            for (IslandExportDocument.Redaction change : redacted.redactions)
                if ("invalid-json".equals(change.reason)) { assertTrue(change.structureChanged); invalid = true; }
            assertTrue(invalid);
        }
    }

    @Test public void allEightRestrictedDescriptorsRoundTripFullAndRedactedWithoutInventedComparison() throws Exception {
        String raw = "{\"title\":\"primary-secret\",\"progressInfo\":{\"progress\":37}}";
        Builder input = builder("body-secret", raw).extra("miui.focus.isFocus", Value.of(Boolean.TRUE))
                .extra(CUSTOM, Value.of("{\"title\":\"custom-secret\"}"))
                .extra(TICKER, Value.of(" ticker-secret 😀 "));
        for (String key : EXTRA_KEYS.subList(4, 8)) input.extra(key, Value.remoteViewsMarker());
        Source source = source(input.build());
        try (IslandCaptureSession session = enabledSession()) {
            IslandCaptureRecord record = capture(session, source);
            Map<String, Object> descriptors = object(IslandExportJson.parse(record.extrasJson));
            assertEquals(new HashSet<String>(EXTRA_KEYS), descriptors.keySet());
            assertEquals(8, descriptors.size());
            assertEquals(Boolean.TRUE, object(descriptors.get("miui.focus.isFocus")).get("value"));
            assertEquals("boolean", object(descriptors.get("miui.focus.isFocus")).get("valueType"));
            assertEquals("string", object(descriptors.get(PRIMARY)).get("valueType"));
            assertFalse(object(descriptors.get(PRIMARY)).containsKey("value"));
            assertFalse(object(descriptors.get(CUSTOM)).containsKey("value"));
            assertEquals(Integer.toString(raw.length()), number(object(descriptors.get(PRIMARY)).get("length")));
            for (String key : EXTRA_KEYS.subList(4, 8)) {
                Map<String, Object> descriptor = object(descriptors.get(key));
                assertEquals(Boolean.TRUE, descriptor.get("present"));
                assertEquals("remote-views", descriptor.get("valueType"));
                assertEquals(2, descriptor.size());
            }
            IslandExportDocument full = export(session.records(), Mode.FULL, LockState.UNLOCKED);
            Map<String, Object> fullSample = sample(full, 0);
            assertEquals(" ticker-secret 😀 ", object(object(fullSample.get("extras")).get(TICKER)).get("value"));
            assertTrue(full.redactions.isEmpty()); assertComparison(fullSample);
            assertFalse(object(fullSample.get("sbn")).containsKey("uid"));
            assertFalse(object(fullSample.get("sbn")).containsKey("postTime"));
            assertNull(object(fullSample.get("rawParamJSON")).get("sourceKey"));
            IslandExportDocument redacted = export(session.records(), Mode.REDACTED, LockState.UNLOCKED);
            Map<String, Object> safe = sample(redacted, 0);
            assertComparison(safe); assertFalse(redacted.meta.replayableOriginal);
            assertEquals("<redacted:string>", safe.get("bodyText"));
            assertEquals("<redacted:number>", object(safe.get("sbn")).get("userId"));
            assertEquals("<redacted:string>", object(safe.get("sbn")).get("key"));
            assertEquals(PACKAGE, object(safe.get("sbn")).get("packageName"));
            Map<String, Object> safeExtras = object(safe.get("extras"));
            assertEquals(8, safeExtras.size());
            assertEquals("<redacted:string>", object(safeExtras.get(TICKER)).get("value"));
            Map<String, Object> safeRaw = object(IslandExportJson.parse((String) object(safe.get("rawParamJSON")).get("value")));
            assertEquals("37", number(object(safeRaw.get("progressInfo")).get("progress")));
            for (String secret : Arrays.asList("body-secret", "primary-secret", "custom-secret", "ticker-secret")) {
                assertFalse(redacted.json.contains(secret)); assertFalse(redacted.markdown.contains(secret));
            }
        }
    }

    @Test public void disabledAndNonallowlistedOffersNeverReadAdapterOrRetainFailedIdentity() throws Exception {
        Source disabled = source(builder("private", "{}").build());
        Source denied = source(builder("private", "{}").build());
        try (IslandCaptureSession session = new IslandCaptureSession()) {
            assertEquals(Submission.DISABLED, session.offer(PACKAGE, 7, "caller-key", IdentityAuthorization.CONFIRMED, disabled));
            session.enable(Collections.singleton("another.app"));
            session.setLockState(IslandCaptureSession.LockState.UNLOCKED);
            assertEquals(Submission.NOT_ALLOWLISTED, session.offer(PACKAGE, 7, "caller-key", IdentityAuthorization.CONFIRMED, denied));
            idle(session); assertTrue(session.records().isEmpty());
            assertNotRead(disabled); assertNotRead(denied);
            assertEquals(1, session.counters().rejectedDisabled);
            assertEquals(1, session.counters().rejectedNotAllowlisted);
            assertEquals(0, export(session.records(), Mode.REDACTED, LockState.UNLOCKED).meta.sampleCount);
        }
    }

    @Test public void unverifiedAndDeniedIdentityConclusionsBothSkipSourceAndProduceIdentityFreeRejections() throws Exception {
        try (IslandCaptureSession session = enabledSession()) {
            for (IdentityAuthorization authorization : Arrays.asList(IdentityAuthorization.UNVERIFIED, IdentityAuthorization.DENIED)) {
                Source source = source(builder("private", "{}").build());
                assertEquals(Submission.AUTH_UNVERIFIED, session.offer(PACKAGE, 7, "caller-key", authorization, source));
                idle(session); assertNotRead(source);
                IslandCaptureRecord record = session.records().get(session.records().size() - 1);
                assertEquals(State.REJECTED, record.state); assertEquals(Reason.AUTH_UNVERIFIED, record.reason);
                assertNoIdentityOrBody(record);
            }
            assertEquals(2, session.counters().rejectedAuthUnverified);
            assertFailureSamplesHaveNoIdentityOrBody(export(session.records(), Mode.FULL, LockState.UNLOCKED));
            assertFailureSamplesHaveNoIdentityOrBody(export(session.records(), Mode.REDACTED, LockState.UNLOCKED));
        }
    }

    @Test public void lockedAndUnknownSessionStatesOnlyCapturePlaceholdersAndNeverBackfillAdapter() throws Exception {
        for (IslandCaptureSession.LockState lock : Arrays.asList(IslandCaptureSession.LockState.LOCKED,
                IslandCaptureSession.LockState.UNKNOWN)) {
            try (IslandCaptureSession session = enabledSession()) {
                session.setLockState(lock);
                Source source = source(builder("private", "{}").build());
                assertEquals(Submission.QUEUED, offer(session, source)); idle(session);
                IslandCaptureRecord record = session.records().get(0);
                assertEquals(State.LOCKED_PLACEHOLDER, record.state);
                assertEquals(lock == IslandCaptureSession.LockState.LOCKED ? Reason.LOCKED : Reason.LOCK_STATE_UNKNOWN, record.reason);
                assertNoIdentityOrBody(record); assertNotRead(source);
                LockState exportLock = lock == IslandCaptureSession.LockState.LOCKED ? LockState.LOCKED : LockState.UNKNOWN;
                assertFailureSamplesHaveNoIdentityOrBody(export(session.records(), Mode.REDACTED, exportLock));
                session.setLockState(IslandCaptureSession.LockState.UNLOCKED); idle(session);
                assertNotRead(source);
                assertFailureSamplesHaveNoIdentityOrBody(export(session.records(), Mode.FULL, LockState.UNLOCKED));
            }
        }
    }

    @Test public void missingBodyRawOrCallerIdentityMapToExistingMissingFieldWithoutLeakingPartialData() throws Exception {
        for (Input input : Arrays.asList(null, new Builder().extra(PRIMARY, Value.of("{}")).build(),
                new Builder().bodyText(Value.of(null)).extra(PRIMARY, Value.of("{}")).build(),
                new Builder().bodyText(Value.of("private")).build())) {
            Source source = source(input);
            assertAdapterFailure(source, State.READ_ERROR, Reason.MISSING_FIELD, Code.MISSING_FIELD);
        }
        try (IslandCaptureSession session = enabledSession()) {
            for (boolean missingUser : Arrays.asList(Boolean.TRUE, Boolean.FALSE)) {
                Source source = source(builder("private", "{}").build());
                assertEquals(Submission.QUEUED, session.offer(PACKAGE, missingUser ? null : Integer.valueOf(7),
                        missingUser ? "key" : null, IdentityAuthorization.CONFIRMED, source));
                idle(session); assertNotRead(source);
                IslandCaptureRecord record = session.records().get(session.records().size() - 1);
                assertEquals(Reason.MISSING_FIELD, record.reason); assertNoIdentityOrBody(record);
            }
        }
    }

    @Test public void wrongScalarTypesMapToTypeMismatchWithoutAnyObjectToString() throws Exception {
        Object arbitrary = new Object() {
            @Override public String toString() { throw new AssertionError("arbitrary object must be discarded"); }
        };
        for (Input input : Arrays.asList(new Builder().bodyText(Value.of(arbitrary)).extra(PRIMARY, Value.of("{}")).build(),
                new Builder().bodyText(Value.of(Boolean.TRUE)).extra(PRIMARY, Value.of("{}")).build(),
                new Builder().bodyText(Value.of("private")).extra(PRIMARY, Value.of(Boolean.FALSE)).build(),
                new Builder().bodyText(Value.of("private")).extra(PRIMARY, Value.of(Integer.valueOf(1))).build()))
            assertAdapterFailure(source(input), State.READ_ERROR, Reason.TYPE_MISMATCH, Code.TYPE_MISMATCH);
    }

    @Test public void simulatedReadFailuresMapToReadFailedWithoutExportingPartialBodyOrIdentity() throws Exception {
        for (Input input : Arrays.asList(new Builder().bodyText(Value.readFailure()).extra(PRIMARY, Value.of("{}")).build(),
                new Builder().bodyText(Value.of("private")).extra(PRIMARY, Value.readFailure()).build(),
                new Builder().bodyText(Value.of("private")).extra(CUSTOM, Value.readFailure()).build()))
            assertAdapterFailure(source(input), State.READ_ERROR, Reason.READ_FAILED, Code.READ_FAILED);
    }

    @Test public void bodyFieldByteOverflowRejectsWholeRecordRatherThanTruncating() throws Exception {
        Source exact = source(builder(repeat('a', 65536), "{}").build());
        try (IslandCaptureSession session = enabledSession()) {
            IslandCaptureRecord record = capture(session, exact);
            assertEquals(65536, bytes(record.bodyText)); assertEquals(Code.READY, exact.summary().code);
        }
        Source over = source(builder(repeat('a', 65537), "{}").build());
        assertAdapterFailure(over, State.REJECTED, Reason.OVERSIZE, Code.OVERSIZE);
        assertEquals("$.bodyText", over.summary().path);
    }

    @Test public void rawParamUtf8OverflowRejectsWholeRecordAndDoesNotSubstituteSmallCustom() throws Exception {
        String largeRaw = "{\"title\":\"" + repeat('中', 21846) + "\"}";
        assertTrue(largeRaw.length() < 65536); assertTrue(bytes(largeRaw) > 65536);
        Source source = source(builder("private", largeRaw).extra(CUSTOM, Value.of("{}")).build());
        assertAdapterFailure(source, State.REJECTED, Reason.OVERSIZE, Code.OVERSIZE);
        assertEquals(PRIMARY, source.summary().selectedKey); assertEquals("$.rawParamJSON", source.summary().path);
    }

    @Test public void extrasDescriptorHeaderAndJsonEscapingCountTowardSingleFieldCapacity() throws Exception {
        // 原 ticker 均未超过单字段字节数；固定八键包头或 JSON 转义使编码后 extras 超额。
        for (String ticker : Arrays.asList(repeat('a', 65536), repeat('\u0000', 12000))) {
            assertTrue(bytes(ticker) <= 65536);
            Source source = source(builder("private", "{}").extra(TICKER, Value.of(ticker)).build());
            assertAdapterFailure(source, State.REJECTED, Reason.OVERSIZE, Code.OVERSIZE);
            assertEquals("$.extrasJson", source.summary().path);
        }
    }

    @Test public void lockChangesBeforeQueuedAdapterReadProduceOnlyPlaceholders() throws Exception {
        for (IslandCaptureSession.LockState lock : Arrays.asList(IslandCaptureSession.LockState.LOCKED,
                IslandCaptureSession.LockState.UNKNOWN)) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            Source queued = source(builder("queued-private", "{}").build());
            try (IslandCaptureSession session = enabledSession()) {
                try {
                    assertEquals(Submission.QUEUED, session.offer(PACKAGE, 7, "inflight", IdentityAuthorization.CONFIRMED, () -> {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout");
                        return new IslandCaptureSession.BodyFields("already-inflight", "{}", "{}");
                    }));
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertEquals(Submission.QUEUED, offer(session, queued));
                    session.setLockState(lock); assertNotRead(queued);
                } finally { release.countDown(); }
                idle(session); assertNotRead(queued); assertEquals(2, session.records().size());
                for (IslandCaptureRecord record : session.records()) {
                    assertEquals(State.LOCKED_PLACEHOLDER, record.state); assertNoIdentityOrBody(record);
                    assertEquals(lock == IslandCaptureSession.LockState.LOCKED ? Reason.LOCKED : Reason.LOCK_STATE_UNKNOWN, record.reason);
                }
            }
        }
    }

    @Test public void clearBeforeQueuedAdapterReadInvalidatesOldGenerationButAllowsFreshCapture() throws Exception {
        assertQueuedAdapterInvalidated(0);
    }
    @Test public void disableBeforeQueuedAdapterReadSkipsSourceAndKeepsCaptureDisabled() throws Exception {
        assertQueuedAdapterInvalidated(1);
    }
    @Test public void closeBeforeQueuedAdapterReadSkipsSourceAndCannotPublishInflightOldBody() throws Exception {
        assertQueuedAdapterInvalidated(2);
    }
    @Test public void newEnableBeforeQueuedAdapterReadSkipsOldSourceAndAcceptsOnlyNewGeneration() throws Exception {
        assertQueuedAdapterInvalidated(3);
    }

    private static Builder builder(String body, String raw) {
        return new Builder().bodyText(Value.of(body)).extra(PRIMARY, Value.of(raw));
    }
    private static Source source(Input input) { return IslandCaptureInputAdapter.source(input); }
    private static String repeat(char character, int count) {
        char[] text = new char[count]; Arrays.fill(text, character); return new String(text);
    }
    private static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    private static IslandCaptureSession enabledSession() {
        IslandCaptureSession session = new IslandCaptureSession();
        session.enable(Collections.singleton(PACKAGE)); session.setLockState(IslandCaptureSession.LockState.UNLOCKED);
        return session;
    }
    private static Submission offer(IslandCaptureSession session, Source source) {
        return session.offer(PACKAGE, Integer.valueOf(7), "caller-key", IdentityAuthorization.CONFIRMED, source);
    }
    private static IslandCaptureRecord capture(IslandCaptureSession session, Source source) throws Exception {
        assertEquals(Submission.QUEUED, offer(session, source)); idle(session);
        IslandCaptureRecord record = session.records().get(session.records().size() - 1);
        assertEquals(State.CAPTURED, record.state); assertNull(record.reason);
        return record;
    }
    private static void idle(IslandCaptureSession session) throws InterruptedException {
        assertTrue("capture worker did not become idle", session.awaitIdle(5, TimeUnit.SECONDS));
    }
    private static void assertNotRead(Source source) {
        assertEquals(0, source.readCount()); assertEquals(Code.NOT_READ, source.summary().code);
    }
    private static void assertNoIdentityOrBody(IslandCaptureRecord record) {
        assertNull(record.packageName); assertNull(record.userId); assertNull(record.notificationKey);
        assertNull(record.bodyText); assertNull(record.rawParamJSON); assertNull(record.extrasJson);
    }
    private void assertAdapterFailure(Source source, State state, Reason reason, Code summary) throws Exception {
        try (IslandCaptureSession session = enabledSession()) {
            assertEquals(Submission.QUEUED, offer(session, source)); idle(session);
            assertEquals(1, source.readCount()); assertEquals(summary, source.summary().code);
            assertEquals(1, session.records().size());
            IslandCaptureRecord record = session.records().get(0);
            assertEquals(state, record.state); assertEquals(reason, record.reason); assertNoIdentityOrBody(record);
            if (reason == Reason.OVERSIZE) assertEquals(1, session.counters().droppedOversize);
            assertFailureSamplesHaveNoIdentityOrBody(export(session.records(), Mode.FULL, LockState.UNLOCKED));
            assertFailureSamplesHaveNoIdentityOrBody(export(session.records(), Mode.REDACTED, LockState.UNLOCKED));
        }
    }
    private void assertQueuedAdapterInvalidated(int action) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Source queued = source(builder("queued-private", "{}").build());
        try (IslandCaptureSession session = enabledSession()) {
            try {
                assertEquals(Submission.QUEUED, session.offer(PACKAGE, 7, "blocking-old-key", IdentityAuthorization.CONFIRMED, () -> {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout");
                    return new IslandCaptureSession.BodyFields("inflight-private", "{}", "{}");
                }));
                assertTrue("worker did not enter blocking source", entered.await(5, TimeUnit.SECONDS));
                assertEquals(Submission.QUEUED, offer(session, queued));
                assertEquals(1, session.pendingCount()); assertNotRead(queued);
                switch (action) {
                    case 0: session.clear(); break;
                    case 1: session.disable(); break;
                    case 2: session.close(); break;
                    case 3: session.enable(Collections.singleton(PACKAGE)); break;
                    default: throw new AssertionError("unknown test action");
                }
                assertEquals(0, session.pendingCount()); assertNotRead(queued);
            } finally { release.countDown(); }
            idle(session); assertNotRead(queued); assertTrue(session.records().isEmpty());
            assertEquals(2, session.counters().droppedSessionStale);
            Source fresh = source(builder("fresh", "{}").build());
            if (action == 0 || action == 3) {
                assertEquals("fresh", capture(session, fresh).bodyText); assertEquals(1, fresh.readCount());
                Map<String, Object> projected = sample(export(session.records(), Mode.FULL, LockState.UNLOCKED), 0);
                assertEquals("fresh", projected.get("bodyText"));
            } else {
                assertEquals(Submission.DISABLED, offer(session, fresh)); assertNotRead(fresh);
                assertEquals(0, export(session.records(), Mode.REDACTED, LockState.UNLOCKED).meta.sampleCount);
            }
        }
    }
    private IslandExportDocument export(List<IslandCaptureRecord> records, Mode mode, LockState lock) {
        IslandExportState state = new IslandExportState(lock, ExportAuthorization.AUTHORIZED,
                FullConfirmation.CONFIRMED, 1);
        IslandExportGenerator generator = new IslandExportGenerator();
        IslandExportGenerator.ExportPlan plan = generator.begin(new IslandExportRequest(records, null,
                Collections.singleton(PACKAGE), mode), state);
        while (plan.nextIndex < plan.frozenSamples.size()) {
            IslandExportGenerator.Step step = generator.renderNextSample(plan, state);
            assertNull(step.incomplete); assertNull(step.jsonFragment); assertNull(step.markdownFragment);
            plan = step.plan;
        }
        IslandExportDocument document = generator.finish(plan, state);
        assertTrue(document.complete); assertTrue(document.meta.complete);
        assertEquals(IslandExportDocument.Reason.NONE, document.reason);
        for (Object sample : samples(document)) assertComparison(object(sample));
        return document;
    }
    private static void assertFailureSamplesHaveNoIdentityOrBody(IslandExportDocument document) {
        for (Object input : samples(document)) {
            Map<String, Object> sample = object(input);
            assertNull(sample.get("bodyText")); assertNull(sample.get("rawParamJSON")); assertNull(sample.get("extras"));
            Map<String, Object> identity = object(sample.get("sbn"));
            assertNull(identity.get("packageName")); assertNull(identity.get("userId")); assertNull(identity.get("key"));
            assertComparison(sample);
        }
    }
    private static void assertComparison(Map<String, Object> sample) {
        Map<String, Object> comparison = object(sample.get("comparison"));
        assertEquals(4, comparison.size()); assertEquals("not-observed", comparison.get("status"));
        for (String key : Arrays.asList("before", "after", "changed")) {
            assertTrue(comparison.containsKey(key)); assertNull(comparison.get(key));
        }
    }
    private static void assertRecordHasOnlyApprovedPublicFields(IslandCaptureRecord record) {
        Set<String> names = new HashSet<String>();
        for (Field field : record.getClass().getFields()) names.add(field.getName());
        assertEquals(new HashSet<String>(Arrays.asList("id", "capturedAt", "sequence", "state", "reason",
                "packageName", "userId", "notificationKey", "bodyText", "rawParamJSON", "extrasJson")), names);
    }
    private static String number(Object token) { return ((IslandExportJson.NumberToken) token).text; }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object input) { return (Map<String, Object>) input; }
    @SuppressWarnings("unchecked") private static List<Object> samples(IslandExportDocument document) {
        return (List<Object>) object(IslandExportJson.parse(document.json)).get("samples");
    }
    private static Map<String, Object> sample(IslandExportDocument document, int index) { return object(samples(document).get(index)); }
}
