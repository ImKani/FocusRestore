/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class IslandCapturePreviewTest {
    private static final String PKG = "example.island";
    private static final String KEY = "private-notification-key";
    private static final String RAW = "{\"baseInfo\":{\"title\":\"sample text\"}}";
    private static final int USER = 0;
    private static final int UID = 10045;

    private static final class Fixture {
        final Object connection = new Object();
        final AtomicReference<Boolean> eligible = new AtomicReference<>(true);
        final AtomicReference<IslandCaptureRuntime.PreviewAccess> access = new AtomicReference<>(
                new IslandCaptureRuntime.PreviewAccess(connection, USER));
        final AtomicLong clock = new AtomicLong();
        final AtomicInteger proofReads = new AtomicInteger();
        int revokeAt = Integer.MAX_VALUE;
        final IslandCaptureRuntime runtime = new IslandCaptureRuntime(() -> Boolean.TRUE.equals(eligible.get()),
                () -> proofReads.incrementAndGet() < revokeAt ? access.get() : null, clock::get,
                pkg -> PKG.equals(pkg) ? UID : UID + 1);
        final long generation = start();

        long start() {
            IslandCaptureRuntime.Grant grant = runtime.start(runtime.status().epoch, USER);
            assertNotNull(grant);
            return grant.generation;
        }

        void capture(String raw) throws Exception {
            IslandCaptureInputAdapter.Input input = fromExtras(raw, null, "ordinary text");
            assertTrue(runtime.offer(runtime.current(), PKG, USER, UID, KEY, () -> input));
            assertTrue(runtime.awaitIdle(5, TimeUnit.SECONDS));
        }

        List<IslandCapturePreview.Summary> list() { return runtime.previewList(connection, generation); }
        long open(long handle) { return runtime.openPreview(connection, generation, handle); }
        IslandCapturePreview.Page page(long handle, long ticket, int offset) {
            return runtime.previewPage(connection, generation, handle, ticket, offset);
        }
    }

    @Test public void evictsAt31AndKeepsAcceptingAfterLegacyListIsFull() throws Exception {
        Fixture f = new Fixture();
        for (int i = 0; i < 30; i++) f.capture(RAW);
        long first = f.list().get(29).handle;
        long ticket = f.open(first);
        assertNotNull(f.page(first, ticket, 0));
        IslandCaptureRuntime.Export firstExport = f.runtime.prepare(f.generation, first);
        assertNotNull(f.runtime.chunk(f.generation, first, firstExport.ticket, 0));
        f.capture(RAW);
        assertNull(f.runtime.chunk(f.generation, first, firstExport.ticket, 0));
        assertEquals(30, f.list().size());
        assertEquals(first + 30, f.list().get(0).handle);
        assertEquals(first + 1, f.list().get(29).handle);
        assertNull(f.page(first, ticket, 0));
        assertEquals(0, f.open(first));
        for (int i = 31; i < 70; i++) f.capture(RAW);
        List<IslandCaptureRuntime.Sample> exportable = f.runtime.status().samples;
        assertEquals(30, exportable.size());
        assertEquals(first + 69, exportable.get(0).sequence);
        assertEquals(first + 40, exportable.get(29).sequence);
        assertThrows(java.io.IOException.class, () -> f.runtime.prepare(f.generation, first));
        assertEquals(30, f.list().size());
        assertEquals(first + 69, f.list().get(0).handle);
        f.runtime.end();
        long fresh = f.start();
        assertEquals(Collections.emptyList(), f.runtime.previewList(f.connection, fresh));
        assertNull(f.list());
    }

    @Test public void rejectsOrdinaryInvalidAndUnprojectableRecords() throws Exception {
        Fixture f = new Fixture();
        AtomicInteger bodyReads = new AtomicInteger();
        for (Object primary : Arrays.asList(null, "  ", new Object(), Boolean.TRUE)) {
            assertFalse(f.runtime.offer(f.runtime.current(), PKG, USER, UID, KEY,
                    () -> IslandCaptureInputAdapter.readNotification(name -> {
                        if (FocusParamKeys.PRIMARY.equals(name)) return primary;
                        if (!FocusParamKeys.CUSTOM.equals(name)) bodyReads.incrementAndGet();
                        return null;
                    }, "android.text", value -> false)));
        }
        assertEquals(0, bodyReads.get());
        assertFalse(f.runtime.offer(f.runtime.current(), "example.other", USER, UID, KEY,
                () -> { throw new AssertionError("unapproved read"); }));
        assertFalse(f.runtime.offer(f.runtime.current(), PKG, USER + 1, UID, KEY,
                () -> { throw new AssertionError("wrong user read"); }));
        for (String raw : Arrays.asList("{", "[]", "{\"dynamic\":\"private\"}",
                "{\"baseInfo\":{\"title\":{\"private\":\"value\"},\"content\":[\"private\"],\"subTitle\":null}}")) {
            f.capture(raw);
        }
        assertEquals(4, f.list().size());
        for (IslandCapturePreview.Summary row : f.list()) {
            assertEquals(0, row.fieldCount);
            assertNotNull(row.reason);
            assertEquals(0, f.open(row.handle));
        }
        assertEquals(IslandCaptureInputAdapter.Uneditable.INVALID, f.list().get(3).uneditable);
        assertEquals(IslandCaptureInputAdapter.Uneditable.NO_FIELDS, f.list().get(0).uneditable);
        assertEquals(4, f.runtime.status().samples.size());
    }

    @Test public void admissionRequiresSameLiveObjectAndGenerationInEitherOrder() {
        IslandCaptureAdmission<Object, Object> pairs = new IslandCaptureAdmission<>();
        Object source = new Object(), entry = new Object(), replacement = new Object();
        assertNull(pairs.decide(1, source, true));
        assertFalse(pairs.post(1, replacement, entry));
        assertTrue(pairs.post(1, source, entry));
        assertFalse(pairs.post(1, source, entry));
        assertSame(entry, pairs.decide(1, source, true));
        assertFalse(pairs.post(1, source, entry));
        assertNull(pairs.decide(1, source, false));
        assertNull(pairs.decide(1, source, true));
        assertFalse(pairs.post(2, source, entry));
        assertSame(entry, pairs.decide(2, source, true));
    }

    @Test public void bodyByteBudgetEvictsBeforeThirtyAndRevokesAllViews() throws Exception {
        Fixture f = new Fixture();
        String raw = "{\"baseInfo\":{\"title\":\"short\"},\"padding\":\"" + repeat('p', 60_000) + "\"}";
        IslandCaptureInputAdapter.Input input = fromExtras(raw, null, repeat('b', 60_000));
        assertTrue(f.runtime.offer(f.runtime.current(), PKG, USER, UID, KEY, () -> input));
        assertTrue(f.runtime.awaitIdle(5, TimeUnit.SECONDS));
        long first = f.list().get(0).handle;
        long preview = f.open(first);
        IslandCaptureRuntime.Export export = f.runtime.prepare(f.generation, first);
        for (int i = 0; i < 29; i++) {
            assertTrue(f.runtime.offer(f.runtime.current(), PKG, USER, UID, KEY, () -> input));
            assertTrue(f.runtime.awaitIdle(5, TimeUnit.SECONDS));
        }
        assertTrue(f.list().size() < 30);
        assertEquals(first + 29, f.list().get(0).handle);
        assertNull(f.page(first, preview, 0));
        assertNull(f.runtime.chunk(f.generation, first, export.ticket, 0));
    }

    @Test public void sessionCallbackReceivesNeitherFailuresNorPlaceholders() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        try (IslandCaptureSession session = new IslandCaptureSession()) {
            session.enable(Collections.singleton(PKG));
            session.setLockState(IslandCaptureSession.LockState.LOCKED);
            session.offer(PKG, USER, KEY, IslandCaptureSession.IdentityAuthorization.CONFIRMED,
                    () -> { throw new AssertionError("locked read"); }, record -> callbacks.incrementAndGet());
            session.setLockState(IslandCaptureSession.LockState.UNLOCKED);
            session.offer(PKG, USER, KEY, IslandCaptureSession.IdentityAuthorization.CONFIRMED,
                    () -> new IslandCaptureSession.BodyFields(Boolean.TRUE, RAW, "{}"), record -> callbacks.incrementAndGet());
            session.offer(PKG, USER, KEY, IslandCaptureSession.IdentityAuthorization.CONFIRMED,
                    () -> { throw new java.io.IOException("synthetic"); }, record -> callbacks.incrementAndGet());
            session.offer(PKG, USER, KEY, IslandCaptureSession.IdentityAuthorization.CONFIRMED,
                    () -> new IslandCaptureSession.BodyFields(null, RAW, "{}", FocusParamKeys.PRIMARY), record -> {
                        callbacks.incrementAndGet();
                        throw new IllegalStateException("synthetic preview failure");
                    });
            assertTrue(session.awaitIdle(5, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
            List<IslandCaptureRecord> legacy = session.records();
            assertEquals(4, legacy.size());
            assertEquals(IslandCaptureRecord.State.LOCKED_PLACEHOLDER, legacy.get(0).state);
            assertEquals(IslandCaptureRecord.Reason.TYPE_MISMATCH, legacy.get(1).reason);
            assertEquals(IslandCaptureRecord.Reason.READ_FAILED, legacy.get(2).reason);
            assertEquals(IslandCaptureRecord.State.CAPTURED, legacy.get(3).state);
            assertNull(legacy.get(0).notificationKey);
            assertNull(legacy.get(1).rawParamJSON);
        }
    }

    @Test public void projectsOnlyRegisteredPathTypesAndUsesExistingContainerChoice() throws Exception {
        Fixture f = new Fixture();
        String raw = "{\"title\":\"legacy\",\"baseInfo\":{\"title\":\"wrong container\"},"
                + "\"param_v2\":{\"baseInfo\":{\"title\":\"specific text\",\"content\":false,\"subTitle\":[]},"
                + "\"progressInfo\":{\"progress\":-1.250e+02},\"stepInfo\":{\"step\":1e999},"
                + "\"dynamic\":\"private\"},\"param_v3\":{\"baseInfo\":{\"extraTitle\":\"ignored\"}}}";
        f.capture(raw);
        IslandCapturePreview.Summary item = f.list().get(0);
        assertEquals(PKG, item.packageName);
        assertEquals(FocusParamKeys.PRIMARY, item.sourceKey);
        assertEquals(4, item.fieldCount);
        assertTrue(item.capturedAt > 0);
        long ticket = f.open(item.handle);
        IslandCapturePreview.Page page = f.page(item.handle, ticket, 0);
        assertNotNull(page);
        assertEquals(-1, page.nextOffset);
        Map<String, IslandCapturePreview.Field> fields = new LinkedHashMap<>();
        for (IslandCapturePreview.Field field : page.fields) {
            assertSame(field.path, IslandRulePaths.find(field.path.path));
            assertTrue(IslandRulePaths.isRuntimeAllowed(field.path));
            fields.put(field.path.path, field);
        }
        assertEquals(IslandCapturePreview.Type.STRING, fields.get("/baseInfo/title").type);
        assertEquals("specific text", fields.get("/baseInfo/title").text);
        assertEquals(IslandRulePaths.SecurityLevel.FREE_TEXT, fields.get("/baseInfo/title").path.securityLevel);
        assertEquals(IslandCapturePreview.Type.BOOLEAN, fields.get("/baseInfo/content").type);
        assertEquals(Boolean.FALSE, fields.get("/baseInfo/content").booleanValue);
        assertEquals(IslandCapturePreview.Type.NUMBER, fields.get("/progressInfo/progress").type);
        assertEquals("-1.250e+02", fields.get("/progressInfo/progress").text);
        assertEquals(IslandRulePaths.SecurityLevel.STRUCTURAL, fields.get("/progressInfo/progress").path.securityLevel);
        assertEquals("legacy", fields.get("/title").text);
    }

    @Test public void customFallbackAndAndroidObjectMarkersNeverReachPreview() throws Exception {
        Fixture f = new Fixture();
        Object marker = new Object() { @Override public String toString() { throw new AssertionError("arbitrary toString"); } };
        IslandCaptureInputAdapter.Input input = IslandCaptureInputAdapter.readNotification(name -> {
            if (FocusParamKeys.PRIMARY.equals(name)) return " \t";
            if (FocusParamKeys.CUSTOM.equals(name)) return RAW;
            return marker;
        }, "android.text", value -> value == marker);
        assertTrue(f.runtime.offer(f.runtime.current(), PKG, USER, UID, KEY, () -> input));
        assertTrue(f.runtime.awaitIdle(5, TimeUnit.SECONDS));
        IslandCapturePreview.Summary item = f.list().get(0);
        assertEquals(FocusParamKeys.CUSTOM, item.sourceKey);
        IslandCapturePreview.Page page = f.page(item.handle, f.open(item.handle), 0);
        assertEquals(1, page.fields.size());
        assertEquals("sample text", page.fields.get(0).text);
    }

    @Test public void previewTicketAndRedactedExportAreSeparate() throws Exception {
        Fixture f = new Fixture();
        f.capture(RAW);
        long handle = f.list().get(0).handle;
        long preview = f.open(handle);
        assertTrue(preview < 0);
        IslandCaptureRuntime.Export export = f.runtime.prepare(f.generation, handle);
        assertTrue(export.ticket > 0);
        assertNull(f.runtime.chunk(f.generation, handle, preview, 0));
        assertNull(f.page(handle, export.ticket, 0));
        String redacted = new String(export.chunk(0), StandardCharsets.UTF_8);
        assertFalse(redacted.contains("sample text"));
        assertFalse(redacted.contains("ordinary text"));
        assertFalse(redacted.contains(KEY));
        assertTrue(f.runtime.closePreview(f.connection, f.generation, handle, preview));
        assertNull(f.page(handle, preview, 0));
        assertNotNull(f.runtime.chunk(f.generation, handle, export.ticket, 0));
    }

    @Test public void lockScreenOffAndUnknownGateRevokeWithoutResurrection() throws Exception {
        for (Boolean denied : Arrays.asList(Boolean.FALSE, null)) {
            Fixture f = new Fixture();
            f.capture(RAW);
            long handle = f.list().get(0).handle;
            long ticket = f.open(handle);
            f.eligible.set(denied);
            assertNull(f.page(handle, ticket, 0));
            assertNull(f.list());
            assertEquals(0, f.open(handle));
            f.eligible.set(true);
            assertNull(f.page(handle, ticket, 0));
            assertEquals(Collections.emptyList(), f.runtime.previewList(f.connection, f.start()));
        }
    }

    @Test public void unknownMainUserWrongUserAndChangedConnectionRevokePreviewOnly() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            Fixture f = new Fixture();
            f.capture(RAW);
            long handle = f.list().get(0).handle;
            long ticket = f.open(handle);
            IslandCaptureRuntime.PreviewAccess former = f.access.get();
            Object other = new Object();
            f.access.set(kind == 0 ? null : kind == 1 ? new IslandCaptureRuntime.PreviewAccess(f.connection, USER + 1)
                    : new IslandCaptureRuntime.PreviewAccess(other, USER));
            assertNull(f.runtime.previewPage(other, f.generation, handle, ticket, 0));
            assertNull(f.page(handle, ticket, 0));
            assertNull(f.list());
            assertTrue(f.runtime.status().samples.isEmpty());
            assertNull(f.runtime.current());
            f.access.set(former);
            assertEquals(0, f.open(handle));
            assertNull(f.page(handle, ticket, 0));
        }
    }

    @Test public void profileRevocationGenerationReopenAndExpiryRejectStaleTickets() throws Exception {
        Fixture f = new Fixture();
        f.capture(RAW);
        long handle = f.list().get(0).handle;
        long first = f.open(handle);
        long second = f.open(handle);
        assertNotEquals(first, second);
        assertNull(f.page(handle, first, 0));
        assertNull(f.page(handle + 1, second, 0));
        assertNull(f.runtime.previewPage(f.connection, f.generation + 1, handle, second, 0));
        f.clock.set(60_000);
        assertNull(f.page(handle, second, 0));
        long third = f.open(handle);
        assertNotNull(f.page(handle, third, 0));
        f.runtime.revokePreviewAccess();
        assertNull(f.list());
        assertNull(f.page(handle, third, 0));
        assertFalse(f.runtime.end(f.generation));
        long next = f.start();
        assertEquals(Collections.emptyList(), f.runtime.previewList(f.connection, next));
        assertNull(f.runtime.previewPage(f.connection, next, handle, third, 0));
    }

    @Test public void authorizationLossAtFinalReadDiscardsAlreadyGeneratedPage() throws Exception {
        Fixture f = new Fixture();
        f.capture(RAW);
        long handle = f.list().get(0).handle;
        long ticket = f.open(handle);
        f.revokeAt = f.proofReads.get() + 3;
        assertNull(f.page(handle, ticket, 0));
        assertEquals(f.revokeAt, f.proofReads.get());
        f.revokeAt = Integer.MAX_VALUE;
        assertNull(f.page(handle, ticket, 0));
        assertNull(f.list());
    }

    @Test public void fieldPageAndRecordCeilingsAreEnforced() throws Exception {
        IslandRulePaths.Entry title = IslandRulePaths.find("/baseInfo/title");
        assertNotNull(IslandCapturePreview.Field.create(title, IslandCapturePreview.Type.STRING, repeat('é', 1024), null));
        assertNull(IslandCapturePreview.Field.create(title, IslandCapturePreview.Type.STRING, repeat('a', 1025), null));
        assertNull(IslandCapturePreview.Field.create(title, IslandCapturePreview.Type.STRING, repeat('字', 683), null));
        assertNull(IslandCapturePreview.Field.create(title, IslandCapturePreview.Type.STRING, "\uD800", null));
        assertNull(IslandCapturePreview.Field.create(title, IslandCapturePreview.Type.NUMBER, "01", null));
        assertNull(IslandCapturePreview.Field.create(null, IslandCapturePreview.Type.BOOLEAN, null, true));
        IslandCapturePreview small = makePreview(24, "v");
        assertEquals(12, small.page(0).fields.size());
        assertEquals(12, small.page(0).nextOffset);
        assertEquals(-1, small.page(12).nextOffset);
        assertNull(small.page(-1));
        assertNull(small.page(24));
        IslandCapturePreview dense = makePreview(40, repeat('x', 1024));
        int bytes = 0;
        int counted = 0;
        for (int offset = 0; offset != -1;) {
            IslandCapturePreview.Page page = dense.page(offset);
            assertTrue(page.fields.size() > 0);
            assertTrue(page.fields.size() <= 3);
            int pageBytes = page.fields.stream().mapToInt(field -> field.wireBytes).sum();
            assertTrue(pageBytes + IslandCapturePreview.PAGE_HEADER_BYTES <= IslandCapturePreview.MAX_PAGE_BYTES);
            bytes += pageBytes;
            counted += page.fields.size();
            assertTrue(page.nextOffset == -1 || page.nextOffset == offset + page.fields.size());
            offset = page.nextOffset;
        }
        assertEquals(dense.summary.fieldCount, counted);
        assertTrue(counted < 40);
        assertTrue(bytes <= IslandCapturePreview.MAX_RECORD_BYTES);
    }

    private static IslandCaptureInputAdapter.Input fromExtras(Object primary, Object custom, String text) {
        return IslandCaptureInputAdapter.readNotification(name -> {
            if (FocusParamKeys.PRIMARY.equals(name)) return primary;
            if (FocusParamKeys.CUSTOM.equals(name)) return custom;
            return "android.text".equals(name) ? text : null;
        }, "android.text", value -> false);
    }

    private static String repeat(char ch, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, ch);
        return new String(chars);
    }

    @SuppressWarnings("unchecked")
    private static IslandCapturePreview makePreview(int fields, String value) throws Exception {
        Map<String, Object> tree = new LinkedHashMap<>();
        for (IslandRulePaths.Entry entry : IslandRulePaths.all().subList(0, fields)) {
            Map<String, Object> cursor = tree;
            String[] path = entry.path.substring(1).split("/");
            for (int i = 0; i < path.length - 1; i++) {
                cursor = (Map<String, Object>) cursor.computeIfAbsent(path[i], key -> new LinkedHashMap<String, Object>());
            }
            cursor.put(path[path.length - 1], value);
        }
        String raw = IslandExportJson.write(tree, IslandCaptureSession.MAX_FIELD_BYTES);
        return IslandCapturePreview.project(new IslandCaptureRecord(1, 1, 1, IslandCaptureRecord.State.CAPTURED,
                null, PKG, USER, KEY, null, raw, "{}", raw.length(), FocusParamKeys.PRIMARY), 1);
    }
}
