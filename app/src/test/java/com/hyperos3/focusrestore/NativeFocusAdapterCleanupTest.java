/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** 纯 Java 假 adapter 验证清理流程；不声称验证 ROM 取消或 Android View。 */
public class NativeFocusAdapterCleanupTest {
    static final class Adapter {
        final Object scope;
        final Map<String, Object> holders = new LinkedHashMap<>();
        final Map<String, Object> tinyHolders = new LinkedHashMap<>();
        final Map<String, Object> decoHolders = new LinkedHashMap<>();
        final Map<String, Object> decoLandHolders = new LinkedHashMap<>();
        final Map<String, Object> dataMap = new LinkedHashMap<>();
        Adapter(Object scope, Holder holder) {
            this.scope = scope;
            holders.put("one", holder);
            tinyHolders.put("duplicate", holder);
            dataMap.put("module", new Object());
        }
    }

    static final class Holder {
        int detaches;
        final boolean fail;
        Holder(boolean fail) { this.fail = fail; }
        void onDetach() {
            detaches++;
            if (fail) throw new IllegalStateException("detach failed");
        }
    }

    static final class RecordingLogger implements NativeFocusTemplateRenderer.Logger {
        final List<String> logs = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        @Override public void log(String message) { logs.add(message); }
        @Override public void error(String stage, Throwable error) { errors.add(stage); }
    }

    private void assertEmpty(Adapter adapter) {
        assertTrue(adapter.holders.isEmpty());
        assertTrue(adapter.tinyHolders.isEmpty());
        assertTrue(adapter.decoHolders.isEmpty());
        assertTrue(adapter.decoLandHolders.isEmpty());
        assertTrue(adapter.dataMap.isEmpty());
    }

    @Test public void missingCapabilityIsReportedOnceAcrossAdaptersAndCleanupBatches() {
        RecordingLogger logger = new RecordingLogger();
        NativeFocusTemplateRenderer renderer = new NativeFocusTemplateRenderer(logger, null, null);
        Holder firstHolder = new Holder(false);
        Holder secondHolder = new Holder(false);
        Adapter first = new Adapter(new Object(), firstHolder);
        Adapter second = new Adapter(new Object(), secondHolder);
        renderer.finishAdapterCleanup(Arrays.asList(first, second));
        renderer.finishAdapterCleanup(Collections.singletonList(first));
        assertEquals(1, logger.errors.size());
        assertTrue(logger.errors.get(0).contains("capability unavailable"));
        assertTrue(logger.errors.get(0).contains("unconfirmed ROM cancellation interface"));
        assertEquals(2, logger.logs.size());
        assertTrue(logger.logs.get(0).contains("cleanupPartial=true"));
        assertTrue(logger.logs.get(0).contains("scopeCancellationFailures=2"));
        assertTrue(logger.logs.get(1).contains("scopeCancellationFailures=1"));
        assertEquals(1, firstHolder.detaches);
        assertEquals(1, secondHolder.detaches);
        assertEmpty(first);
        assertEmpty(second);
    }

    @Test public void absentScopeNeedsNoCancellationAndHolderIsDetachedOnlyOnce() {
        RecordingLogger logger = new RecordingLogger();
        NativeFocusTemplateRenderer renderer = new NativeFocusTemplateRenderer(logger, null, null);
        Holder holder = new Holder(false);
        Adapter adapter = new Adapter(null, holder);
        renderer.finishAdapterCleanup(Collections.singletonList(adapter));
        renderer.finishAdapterCleanup(Collections.singletonList(adapter));
        assertEquals(1, holder.detaches);
        assertTrue(logger.errors.isEmpty());
        assertTrue(logger.logs.isEmpty());
        assertEmpty(adapter);
    }

    @Test public void confirmedSignatureCancelsEveryOwnedScope() {
        RecordingLogger logger = new RecordingLogger();
        NativeFocusTemplateRenderer renderer = new NativeFocusTemplateRenderer(logger, null, null);
        h1.H.Scope firstScope = new h1.H.Scope();
        h1.H.Scope secondScope = new h1.H.Scope();
        Adapter first = new Adapter(firstScope, new Holder(false));
        Adapter second = new Adapter(secondScope, new Holder(false));
        renderer.finishAdapterCleanup(Arrays.asList(first, second));
        assertEquals(1, firstScope.cancellations);
        assertEquals(1, secondScope.cancellations);
        assertTrue(logger.errors.isEmpty());
        assertTrue(logger.logs.isEmpty());
        assertEmpty(first);
        assertEmpty(second);
    }

    @Test public void invocationFailureIsNotCachedAsSuccessOrCapabilityAbsence() {
        RecordingLogger logger = new RecordingLogger();
        NativeFocusTemplateRenderer renderer = new NativeFocusTemplateRenderer(logger, null, null);
        h1.H.Scope scope = new h1.H.Scope();
        scope.fail = true;
        Adapter adapter = new Adapter(scope, new Holder(false));
        renderer.finishAdapterCleanup(Collections.singletonList(adapter));
        renderer.finishAdapterCleanup(Collections.singletonList(adapter));
        assertEquals(2, scope.cancellations);
        assertEquals(2, logger.errors.size());
        assertEquals("independent adapter scope cancellation", logger.errors.get(0));
        assertTrue(logger.logs.get(0).contains("scopeCancellationFailures=1"));
        assertTrue(logger.logs.get(1).contains("cleanupPartial=true"));
        assertEmpty(adapter);
    }

    @Test public void detachFailureStillClearsMapsAndReportsPartialCleanup() {
        RecordingLogger logger = new RecordingLogger();
        NativeFocusTemplateRenderer renderer = new NativeFocusTemplateRenderer(logger, null, null);
        Holder holder = new Holder(true);
        Adapter adapter = new Adapter(null, holder);
        renderer.finishAdapterCleanup(Collections.singletonList(adapter));
        assertEquals(1, holder.detaches);
        assertEquals(1, logger.errors.size());
        assertEquals("independent holder detach", logger.errors.get(0));
        assertTrue(logger.logs.get(0).contains("cleanupPartial=true"));
        assertTrue(logger.logs.get(0).contains("holderDetachFailures=1"));
        assertFalse(logger.logs.get(0).contains("cleanupPartial=false"));
        assertEmpty(adapter);
    }
}
