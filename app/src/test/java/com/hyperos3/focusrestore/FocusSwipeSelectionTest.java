/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class FocusSwipeSelectionTest {
    @Test public void oneNotificationCanHideAndRestoreFromEitherDirection() {
        for (boolean right : new boolean[]{false, true}) {
            FocusSwipeSelection queue = new FocusSwipeSelection();
            queue.reconcile(Collections.singletonList("A"), "A");
            assertTrue(queue.swipe(right, queue.revision()));
            assertNull(queue.selected());
            assertTrue(queue.hasCandidates());
            assertTrue(queue.swipe(!right, queue.revision()));
            assertEquals("A", queue.selected());
        }
    }

    @Test public void forwardCycleHasOneEmptyPositionAndReverseRetracesIt() {
        FocusSwipeSelection queue = new FocusSwipeSelection();
        queue.reconcile(Arrays.asList("A", "B", "C"), "A");
        for (String expected : new String[]{"B", "C", null, "A"}) {
            assertTrue(queue.swipe(false, queue.revision()));
            assertEquals(expected, queue.selected());
        }
        for (String expected : new String[]{null, "C", "B", "A"}) {
            assertTrue(queue.swipe(true, queue.revision()));
            assertEquals(expected, queue.selected());
        }
    }

    @Test public void ordinaryUpdatesAndReorderingCannotResurrectHiddenFocus() {
        FocusSwipeSelection queue = new FocusSwipeSelection();
        queue.reconcile(Arrays.asList("A", "B"), "A");
        queue.swipe(true, queue.revision());
        long revision = queue.revision();
        assertNull(queue.reconcile(Arrays.asList("B", "A"), "B"));
        assertEquals(revision, queue.revision());
        assertFalse(queue.allows("B"));
        queue.swipe(true, queue.revision());
        assertEquals("B", queue.selected());
        assertEquals("B", queue.reconcile(Arrays.asList("A", "B"), "A"));
    }

    @Test public void newCandidateResumesAutomaticPriorityAndInvalidatesDownSnapshot() {
        FocusSwipeSelection queue = new FocusSwipeSelection();
        queue.reconcile(Collections.singletonList("A"), "A");
        queue.swipe(false, queue.revision());
        long revision = queue.revision();
        assertEquals("B", queue.reconcile(Arrays.asList("B", "A"), "B"));
        assertFalse(queue.swipe(true, revision));
        assertFalse(queue.manual());
    }

    @Test public void removedAndExpiredCandidatesCannotBeRestored() {
        FocusSwipeSelection queue = new FocusSwipeSelection();
        queue.reconcile(Arrays.asList("A", "B"), "A");
        queue.swipe(true, queue.revision());
        long revision = queue.revision();
        queue.remove("A");
        assertFalse(queue.swipe(true, revision));
        assertNull(queue.reconcile(Collections.singletonList("B"), "B"));
        queue.swipe(true, queue.revision());
        assertEquals("B", queue.selected());
        assertNull(queue.reconcile(Collections.emptyList(), null));
        assertFalse(queue.hasCandidates());
        assertFalse(queue.swipe(true, queue.revision()));
    }

    @Test public void removingSelectedCandidateReturnsToExistingPriority() {
        FocusSwipeSelection queue = new FocusSwipeSelection();
        queue.reconcile(Arrays.asList("A", "B"), "A");
        queue.swipe(false, queue.revision());
        queue.remove("B");
        assertEquals("A", queue.reconcile(Collections.singletonList("A"), "A"));
        assertFalse(queue.manual());
    }

    @Test public void ownerResetDoesNotKeepSelectionOrOldGesture() {
        FocusSwipeSelection queue = new FocusSwipeSelection();
        queue.reconcile(Collections.singletonList("A"), "A");
        long revision = queue.revision();
        queue.clear();
        queue.reconcile(Collections.singletonList("A"), "A");
        assertFalse(queue.swipe(false, revision));
        assertFalse(queue.manual());
    }
}
