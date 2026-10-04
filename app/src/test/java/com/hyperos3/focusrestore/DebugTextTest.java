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

import org.junit.Test;

/**
 * Covers the diagnostic text rendering.
 *
 * <p>Regression source: a field log captured through a non-UTF-8 console turned the focus text
 * {@code 检票口} into {@code 妫€绁ㄥ彛}, which made the very diagnostic that proves the parser correct
 * unreadable. The escaped copy is ASCII-only and therefore survives any capture encoding.
 */
public class DebugTextTest {
    @Test
    public void passesAsciiTextThroughUnchanged() {
        assertEquals("D8396", DebugText.preview("D8396"));
        assertFalse(DebugText.preview("D8396-10:14").contains("[escaped="));
    }

    @Test
    public void appendsAnAsciiEscapedCopyForNonAsciiText() {
        String rendered = DebugText.preview("检票口");
        assertTrue(rendered, rendered.startsWith("检票口"));
        assertTrue(rendered, rendered.contains("[escaped="));
        // 检 U+68C0, 票 U+7968, 口 U+53E3
        assertTrue(rendered, rendered.contains("\\u68c0\\u7968\\u53e3"));
    }

    @Test
    public void escapesTheRealDuplicatedFieldTextReadably() {
        // The text that the 0.14.2 fix produced for the travel notification.
        String rendered = DebugText.preview("D8396·检票口");
        assertTrue(rendered, rendered.contains("\\u68c0\\u7968\\u53e3"));
        assertTrue(rendered, rendered.contains("D8396"));
    }

    @Test
    public void flattensNewlinesAndTrims() {
        assertEquals("a b", DebugText.preview("  a\nb  "));
        assertEquals("a b", DebugText.preview("a\r\nb"));
    }

    @Test
    public void truncatesLongAsciiTextWithoutAnEscapeSuffix() {
        StringBuilder longText = new StringBuilder();
        for (int index = 0; index < 200; index++) longText.append('a');
        String rendered = DebugText.preview(longText.toString());
        assertEquals(123, rendered.length());
        assertTrue(rendered, rendered.endsWith("..."));
        assertFalse(rendered, rendered.contains("[escaped="));
    }

    @Test
    public void handlesNullAndControlCharacters() {
        assertEquals("null", DebugText.preview(null));
        assertEquals("null", DebugText.escapeNonAscii(null));
        assertEquals("a\\u0009b", DebugText.escapeNonAscii("a\tb"));
        assertEquals("", DebugText.escapeNonAscii(""));
    }
}