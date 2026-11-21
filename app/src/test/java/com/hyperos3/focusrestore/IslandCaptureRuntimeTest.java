/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class IslandCaptureRuntimeTest {
    private static final String TARGET = "example.island";
    private static final int USER = 0;
    private static final int UID = 10045;
    private static final String KEY = "private-notification-key";
    private static final String BODY_KEY = "android.text";
    private final AtomicBoolean eligible = new AtomicBoolean(true);
    private final Object connection = new Object();
    private final IslandCaptureRuntime runtime = new IslandCaptureRuntime(eligible::get,
            () -> new IslandCaptureRuntime.PreviewAccess(connection, USER), () -> 0,
            pkg -> TARGET.equals(pkg) ? UID : UID + 1);

    @Test public void closedAndMismatchedIdentitiesNeverReadScalars() {
        AtomicInteger reads = new AtomicInteger();
        assertFalse(runtime.offer(null, TARGET, USER, UID, KEY, () -> input(reads)));
        IslandCaptureRuntime.Grant grant = start();
        assertFalse(runtime.offer(grant, "example.other", USER, UID, KEY, () -> input(reads)));
        assertFalse(runtime.offer(grant, TARGET, -1, UID, KEY, () -> input(reads)));
        assertFalse(runtime.offer(grant, TARGET, 10, UID, KEY, () -> input(reads)));
        assertFalse(runtime.offer(grant, TARGET, USER, UID + 1, KEY, () -> input(reads)));
        assertEquals(0, reads.get());
        assertTrue(runtime.offer(grant, TARGET, USER, UID, KEY, () -> input(reads)));
        assertEquals(1, reads.get());
    }

    @Test public void twoAppsShareNewestFirstQueueIncludingFocusWithoutIslandFields() throws Exception {
        String secondPackage = "example.other";
        IslandCaptureRuntime.Grant grant = start();
        long oldest = 0;
        for (int i = 0; i < 31; i++) {
            boolean second = i % 2 == 0;
            final String displayed = "焦点内容 " + i;
            assertTrue(runtime.offer(grant, second ? secondPackage : TARGET, USER, second ? UID + 1 : UID,
                    "focus-" + i, () -> second ? IslandCaptureInputAdapter.readFocus(
                            key -> null, BODY_KEY, value -> false, displayed) : input(new AtomicInteger())));
            assertTrue(runtime.awaitIdle(5, TimeUnit.SECONDS));
            if (i == 0) oldest = runtime.previewList(connection, grant.generation).get(0).handle;
        }
        List<IslandCapturePreview.Summary> rows = runtime.previewList(connection, grant.generation);
        assertEquals(30, rows.size());
        assertEquals(secondPackage, rows.get(0).packageName);
        assertEquals(TARGET, rows.get(1).packageName);
        assertTrue(rows.get(1).fieldCount > 0);
        for (IslandCapturePreview.Summary row : rows) assertNotEquals(oldest, row.handle);
        IslandCapturePreview.Summary recent = rows.get(0);
        assertEquals(IslandCaptureInputAdapter.Uneditable.NO_PARAMETER, recent.uneditable);
        assertEquals(Arrays.asList("焦点内容 30"), runtime.visibleSnippets(connection, grant.generation, new long[]{recent.handle}));
        assertEquals(0, runtime.openPreview(connection, grant.generation, recent.handle));
    }

    @Test public void endAndLockRejectLateCaptureAndStart() throws Exception {
        IslandCaptureRuntime.Grant first = start();
        assertTrue(runtime.offer(first, TARGET, USER, UID, KEY, () -> input(new AtomicInteger())));
        assertTrue(runtime.awaitIdle(5, TimeUnit.SECONDS));
        assertEquals(1, runtime.previewList(connection, first.generation).size());
        assertTrue(runtime.end(first.generation));
        long pendingStart = runtime.status().epoch;
        eligible.set(false);
        runtime.end(); // 锁屏回调撤销开启命令所依据的代次。
        assertFalse(runtime.status().ready);
        eligible.set(true);
        assertNull(runtime.start(pendingStart, USER));
        IslandCaptureRuntime.Grant next = start();
        AtomicInteger reads = new AtomicInteger();
        assertFalse(runtime.offer(first, TARGET, USER, UID, KEY, () -> input(reads)));
        assertFalse(runtime.end(first.generation));
        assertNull(runtime.previewList(connection, first.generation));
        assertEquals(0, reads.get());
        eligible.set(false); // 生命周期回调延迟时，实时门禁仍拒绝。
        assertFalse(runtime.offer(next, TARGET, USER, UID, KEY, () -> input(reads)));
        assertNull(runtime.current());
        assertEquals(0, reads.get());
    }

    @Test public void lockWhileSamplingDiscardsCandidate() throws Exception {
        IslandCaptureRuntime.Grant grant = start();
        assertFalse(runtime.offer(grant, TARGET, USER, UID, KEY, () -> {
            eligible.set(false);
            return input(new AtomicInteger());
        }));
        assertTrue(runtime.awaitIdle(5, TimeUnit.SECONDS));
        assertNull(runtime.previewList(connection, grant.generation));
    }

    @Test public void authorizedOrdinaryNotificationReadsOnlyIslandParameterKeys() throws Exception {
        IslandCaptureRuntime.Grant grant = start();
        for (Object[] params : Arrays.asList(new Object[] {null, null}, new Object[] {" \t\n", Boolean.TRUE},
                new Object[] {Boolean.TRUE, " \r\n"})) {
            List<String> reads = new ArrayList<>();
            assertFalse(runtime.offer(grant, TARGET, USER, UID, KEY,
                    () -> IslandCaptureInputAdapter.readNotification(key -> {
                        reads.add(key);
                        if (FocusParamKeys.PRIMARY.equals(key)) return params[0];
                        if (FocusParamKeys.CUSTOM.equals(key)) return params[1];
                        return BODY_KEY.equals(key) ? "private-body" : null;
                    }, BODY_KEY, value -> false)));
            assertEquals(Arrays.asList(FocusParamKeys.PRIMARY, FocusParamKeys.CUSTOM), reads);
        }
        assertTrue(runtime.awaitIdle(5, TimeUnit.SECONDS));
        assertTrue(runtime.previewList(connection, grant.generation).isEmpty());
    }

    @Test public void liveCustomOnlyNotificationWithoutOrdinaryBodyKeepsPreviewSourceKey() throws Exception {
        IslandCaptureRuntime.Grant grant = start();
        List<String> reads = new ArrayList<>();
        assertTrue(runtime.offer(grant, TARGET, USER, UID, KEY,
                () -> IslandCaptureInputAdapter.readNotification(key -> {
                    reads.add(key);
                    return FocusParamKeys.CUSTOM.equals(key) ? " \t{\"baseInfo\":{\"title\":\"private-title\"}}\n" : null;
                }, BODY_KEY, value -> false)));
        assertEquals(Arrays.asList(FocusParamKeys.PRIMARY, FocusParamKeys.CUSTOM, BODY_KEY), reads.subList(0, 3));
        assertEquals(1, java.util.Collections.frequency(reads, FocusParamKeys.PRIMARY));
        assertEquals(1, java.util.Collections.frequency(reads, FocusParamKeys.CUSTOM));
        assertTrue(runtime.awaitIdle(5, TimeUnit.SECONDS));
        List<IslandCapturePreview.Summary> rows = runtime.previewList(connection, grant.generation);
        assertEquals(1, rows.size());
        assertEquals(FocusParamKeys.CUSTOM, rows.get(0).sourceKey);
        long ticket = runtime.openPreview(connection, grant.generation, rows.get(0).handle);
        assertTrue(ticket < 0);
        IslandCapturePreview.Page page = runtime.previewPage(connection, grant.generation, rows.get(0).handle, ticket, 0);
        assertNotNull(page);
        IslandCapturePreview.Field title = null;
        for (IslandCapturePreview.Field field : page.fields) {
            if ("/baseInfo/title".equals(field.path.path)) title = field;
        }
        assertNotNull(title);
        assertEquals("private-title", title.text);
    }

    @Test public void liveParameterSelectionPrefersPrimaryAndFallsBackFromBlank() throws Exception {
        String primary = " \t{\"protocol\":1}\n";
        String custom = "{\"protocol\":2}";
        IslandCaptureSession.BodyFields both = readParams(primary, custom);
        assertEquals(FocusParamKeys.PRIMARY, both.sourceKey);
        assertEquals(primary, both.rawParamJSON);
        IslandCaptureSession.BodyFields blank = readParams(" \t\r\n", custom);
        assertEquals(FocusParamKeys.CUSTOM, blank.sourceKey);
        assertEquals(custom, blank.rawParamJSON);
    }

    @Test public void os3ClosedOrWrongIdentityDoesNotInspectEntryOrReadExtras() {
        Object entry = new Object();
        Object sbn = new Object();
        java.util.function.Supplier<Object> forbidden = () -> { throw new AssertionError("unexpected entry inspection"); };
        java.util.function.Supplier<IslandCaptureInputAdapter.Input> read = () -> {
            throw new AssertionError("unexpected extras access");
        };
        assertFalse(runtime.offerActiveEntry(null, TARGET, USER, UID, KEY,
                entry, sbn, forbidden, forbidden, () -> true, read));
        IslandCaptureRuntime.Grant grant = start();
        assertFalse(runtime.offerActiveEntry(grant, "example.other", USER, UID, KEY,
                entry, sbn, forbidden, forbidden, () -> true, read));
        assertFalse(runtime.offerActiveEntry(grant, TARGET, USER + 1, UID, KEY,
                entry, sbn, forbidden, forbidden, () -> true, read));
        assertFalse(runtime.offerActiveEntry(grant, TARGET, USER, UID + 1, KEY,
                entry, sbn, forbidden, forbidden, () -> true, read));
    }

    @Test public void os3ReplacedRemovedOrCanceledEntryNeverReadsExtras() {
        Object entry = new Object();
        Object sbn = new Object();
        IslandCaptureRuntime.Grant grant = start();
        AtomicInteger reads = new AtomicInteger();
        assertFalse(runtime.offerActiveEntry(grant, TARGET, USER, UID, KEY,
                entry, sbn, Object::new, () -> sbn, () -> true, () -> input(reads)));
        assertFalse(runtime.offerActiveEntry(grant, TARGET, USER, UID, KEY,
                entry, sbn, () -> null, () -> sbn, () -> true, () -> input(reads)));
        assertFalse(runtime.offerActiveEntry(grant, TARGET, USER, UID, KEY,
                entry, sbn, () -> entry, Object::new, () -> true, () -> input(reads)));
        assertFalse(runtime.offerActiveEntry(grant, TARGET, USER, UID, KEY,
                entry, sbn, () -> entry, () -> sbn, () -> false, () -> input(reads)));
        assertEquals(0, reads.get());
    }

    @Test public void os3RejectsReplacementOrRevocationDuringSynchronousSampling() throws Exception {
        Object entry = new Object();
        Object sbn = new Object();
        java.util.concurrent.atomic.AtomicReference<Object> liveSbn = new java.util.concurrent.atomic.AtomicReference<>(sbn);
        IslandCaptureRuntime.Grant grant = start();
        AtomicInteger reads = new AtomicInteger();
        assertFalse(runtime.offerActiveEntry(grant, TARGET, USER, UID, KEY,
                entry, sbn, () -> entry, liveSbn::get, () -> true, () -> {
                    liveSbn.set(new Object());
                    return input(reads);
                }));
        liveSbn.set(sbn);
        assertFalse(runtime.offerActiveEntry(grant, TARGET, USER, UID, KEY,
                entry, sbn, () -> entry, liveSbn::get, () -> true, () -> {
                    runtime.end();
                    return input(reads);
                }));
        IslandCaptureRuntime.Grant next = start();
        assertFalse(runtime.offerActiveEntry(next, TARGET, USER, UID, KEY,
                entry, sbn, () -> entry, liveSbn::get, () -> true, () -> {
                    eligible.set(false);
                    return input(reads);
                }));
        assertEquals(3, reads.get());
        assertTrue(runtime.awaitIdle(5, TimeUnit.SECONDS));
        assertNull(runtime.previewList(connection, grant.generation));
        assertNull(runtime.current());
    }

    @Test public void os3ActiveEntryUsesExistingPreviewEditor() throws Exception {
        Object connection = new Object();
        Object entry = new Object();
        Object sbn = new Object();
        IslandCaptureRuntime capture = new IslandCaptureRuntime(eligible::get,
                () -> new IslandCaptureRuntime.PreviewAccess(connection, USER), () -> 0, pkg -> UID);
        IslandCaptureRuntime.Grant grant = capture.start(capture.status().epoch, USER);
        assertNotNull(grant);
        List<String> ordinaryReads = new ArrayList<>();
        assertFalse(capture.offerActiveEntry(grant, TARGET, USER, UID, KEY,
                entry, sbn, () -> entry, () -> sbn, () -> true,
                () -> IslandCaptureInputAdapter.readNotification(key -> {
                    ordinaryReads.add(key);
                    return BODY_KEY.equals(key) ? "ordinary private body" : null;
                }, BODY_KEY, value -> false)));
        assertEquals(Arrays.asList(FocusParamKeys.PRIMARY, FocusParamKeys.CUSTOM), ordinaryReads);
        assertTrue(capture.previewList(connection, grant.generation).isEmpty());
        assertTrue(capture.offerActiveEntry(grant, TARGET, USER, UID, KEY,
                entry, sbn, () -> entry, () -> sbn, () -> true, () -> input(new AtomicInteger())));
        assertTrue(capture.awaitIdle(5, TimeUnit.SECONDS));
        List<IslandCapturePreview.Summary> rows = capture.previewList(connection, grant.generation);
        assertEquals(1, rows.size());
        long handle = rows.get(0).handle;
        long ticket = capture.openPreview(connection, grant.generation, handle);
        assertTrue(ticket < 0);
        IslandCapturePreview.Page page = capture.previewPage(connection, grant.generation, handle, ticket, 0);
        IslandCaptureEditor.Lifetime editor = new IslandCaptureEditor.Lifetime(1);
        assertTrue(editor.acceptPage(0, page));
        assertTrue(editor.editor.toggle(IslandRulePaths.find("/baseInfo/title")));
        assertEquals("private-title", editor.editor.preview());
        assertFalse(new String(editor.editor.candidate(TARGET, 0).bytes(), StandardCharsets.UTF_8).contains("private-title"));
        eligible.set(false);
        assertNull(capture.previewPage(connection, grant.generation, handle, ticket, 0));
        editor.revoke();
        assertFalse(editor.acceptPage(0, page));
        assertEquals("", editor.editor.preview());
    }

    private static IslandCaptureSession.BodyFields readParams(String primary, String custom) throws Exception {
        return IslandCaptureInputAdapter.notificationSource(IslandCaptureInputAdapter.readNotification(key -> {
            if (FocusParamKeys.PRIMARY.equals(key)) return primary;
            if (FocusParamKeys.CUSTOM.equals(key)) return custom;
            return null;
        }, BODY_KEY, value -> false)).read();
    }

    private IslandCaptureRuntime.Grant start() {
        IslandCaptureRuntime.Grant grant = runtime.start(runtime.status().epoch, USER);
        assertNotNull(grant);
        return grant;
    }

    private static IslandCaptureInputAdapter.Input input(AtomicInteger reads) {
        reads.incrementAndGet();
        return new IslandCaptureInputAdapter.Builder().bodyText(IslandCaptureInputAdapter.Value.of("private-body"))
                .extra("miui.focus.ticker", IslandCaptureInputAdapter.Value.of("private-ticker"))
                .extra("miui.focus.param.custom", IslandCaptureInputAdapter.Value.of("{\"protocol\":1,"
                        + "\"scene\":\"foodDelivery\",\"business\":\"private-business\","
                        + "\"baseInfo\":{\"title\":\"private-title\"},\"stepInfo\":{\"step\":\"735945\"},"
                        + "\"progressInfo\":{\"progress\":31},\"private-dynamic-key\":\"private-nested-value\"}"))
                .build();
    }
}
