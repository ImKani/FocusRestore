/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.*;

public class FocusSelectionDiagnosticsTest {
    @Test public void repostOfSameKeyReportsSameRejectionAgain() {
        FocusSelectionDiagnostics log = new FocusSelectionDiagnostics();
        assertTrue(log.changed("k", 10, 1000, true, "device-locked").contains("device-locked"));
        assertNull(log.changed("k", 10, 900, true, "device-locked"));
        assertTrue(log.changed("k", 11, 1000, true, "device-locked").contains("postTime=11"));
        log.removed("k");
        assertNotNull(log.changed("k", 11, 1000, true, "device-locked"));
    }

    @Test public void gateAndExpiryTransitionsReportWithoutCountdownSpam() {
        FocusSelectionDiagnostics log = new FocusSelectionDiagnostics();
        assertTrue(log.changed("k", 10, 1000, true, null).contains("entryBlocked=allowed"));
        assertNull(log.changed("k", 10, 500, true, null));
        assertTrue(log.changed("k", 10, 0, true, null).contains("remainingMs=0"));
        assertTrue(log.changed("k", 10, 0, false, "not-checked-expired").contains("nativeChipAllowed=false"));
        assertNull(log.changed("k", 10, 0, false, "not-checked-expired"));
    }

    @Test public void pipelineReplacementClearsOnlyDiagnosticMemory() {
        FocusSelectionDiagnostics log = new FocusSelectionDiagnostics();
        assertNotNull(log.changed("a", 10, Long.MAX_VALUE, true, null));
        assertNotNull(log.changed("b", 11, Long.MAX_VALUE, true, null));
        log.removed("a");
        assertNull(log.changed("b", 11, Long.MAX_VALUE, true, null));
        log.clear();
        assertNotNull(log.changed("b", 11, Long.MAX_VALUE, true, null));
    }
}
