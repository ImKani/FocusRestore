/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 有效布局代次测试，不依赖 Android 消息队列或伪称 ROM 运行验收。 */
public class FocusMarqueeLayoutTest {
    @Test
    public void firstValidLayoutStartsOnceAndSameGeometryDoesNotRestart() {
        FocusMarqueeLayout layout = new FocusMarqueeLayout();
        Object content = new Object();
        assertEquals(0L, layout.update(content, 0, 0, 20, 0));
        long first = layout.update(content, 200, 100, 20, 100);
        assertTrue(first > 0L);
        assertTrue(layout.isCurrent(content, first));
        assertEquals(0L, layout.update(content, 200, 100, 20, 100));
        assertTrue(layout.isCurrent(content, first));
    }

    @Test
    public void changedViewportInvalidatesDistanceEvenWhenHostWidthIsUnchanged() {
        FocusMarqueeLayout layout = new FocusMarqueeLayout();
        Object content = new Object();
        long first = layout.update(content, 200, 100, 20, 160);
        long changed = layout.update(content, 200, 80, 20, 160);
        assertTrue(changed > first);
        assertFalse(layout.isCurrent(content, first));
        assertTrue(layout.isCurrent(content, changed));
        long insetChanged = layout.update(content, 200, 80, 30, 160);
        assertTrue(insetChanged > changed);
        assertFalse(layout.isCurrent(content, changed));
    }

    @Test
    public void changedContentAndNaturalWidthRequireNewLayoutRevision() {
        FocusMarqueeLayout layout = new FocusMarqueeLayout();
        Object firstContent = new Object();
        Object secondContent = new Object();
        long first = layout.update(firstContent, 200, 100, 20, 100);
        long second = layout.update(secondContent, 200, 100, 20, 100);
        assertTrue(second > first);
        assertFalse(layout.isCurrent(firstContent, first));
        long resized = layout.update(secondContent, 250, 100, 20, 100);
        assertTrue(resized > second);
        assertFalse(layout.isCurrent(secondContent, second));
    }

    @Test
    public void clearIsIdempotentAndOldCallbacksRemainInvalidAfterRebindingSameContent() {
        FocusMarqueeLayout layout = new FocusMarqueeLayout();
        Object content = new Object();
        long first = layout.update(content, 200, 100, 20, 100);
        layout.clear();
        layout.clear();
        assertFalse(layout.isCurrent(content, first));
        long rebound = layout.update(content, 200, 100, 20, 100);
        assertTrue(rebound > first);
        assertFalse(layout.isCurrent(content, first));
        assertTrue(layout.isCurrent(content, rebound));
    }

    @Test
    public void invalidViewportCannotKeepPreviousGeometryCurrent() {
        FocusMarqueeLayout layout = new FocusMarqueeLayout();
        Object content = new Object();
        long first = layout.update(content, 200, 100, 20, 100);
        assertEquals(0L, layout.update(content, 200, 0, 20, 100));
        assertFalse(layout.isCurrent(content, first));
        long valid = layout.update(content, 200, 100, 20, 100);
        assertTrue(valid > first);
        assertTrue(layout.isCurrent(content, valid));
    }
}
