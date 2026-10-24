/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FocusRichTextTest {
    @Test
    public void textWithoutMarkupIsReturnedUnchanged() {
        String text = "骑士接单·预计18:26送达";
        FocusRichText.Parsed parsed = FocusRichText.parse(text);
        assertFalse(parsed.changed);
        assertSame(text, parsed.text);
        assertEquals(0, parsed.count);
        String unclosed = "价格 < 10 元";
        assertSame(unclosed, FocusRichText.parse(unclosed).text);
    }

    @Test
    public void observedTaobaoColorMarkupKeepsTextAndRange() {
        FocusRichText.Parsed parsed = FocusRichText.parse(
                "骑士接单·预计<font color='#0FAD50'>18:26</font>送达");
        assertTrue(parsed.changed);
        assertEquals("骑士接单·预计18:26送达", parsed.text);
        assertEquals(1, parsed.count);
        assertEquals(7, parsed.starts[0]);
        assertEquals(12, parsed.ends[0]);
        assertEquals(0xFF0FAD50, parsed.colors[0]);
    }

    @Test
    public void doubleQuoteUnquotedAndUppercaseFormsAreAccepted() {
        FocusRichText.Parsed doubleQuoted = FocusRichText.parse(
                "<font color=\"#FF0000\">红</font>");
        assertEquals("红", doubleQuoted.text);
        assertEquals(0xFFFF0000, doubleQuoted.colors[0]);

        FocusRichText.Parsed unquoted = FocusRichText.parse("<FONT COLOR=#0f0>绿</FONT>");
        assertEquals("绿", unquoted.text);
        assertEquals(0xFF00FF00, unquoted.colors[0]);

        FocusRichText.Parsed shortForm = FocusRichText.parse("<font color='#0AF'>蓝</font>");
        assertEquals("蓝", shortForm.text);
        assertEquals(0xFF00AAFF, shortForm.colors[0]);
    }

    @Test
    public void multipleAndNestedSpansKeepTheirOwnRanges() {
        FocusRichText.Parsed parsed = FocusRichText.parse(
                "a<font color='#111111'>b<font color='#222222'>c</font>d</font>e");
        assertEquals("abcde", parsed.text);
        assertEquals(2, parsed.count);
        // 内层先结束（c），外层覆盖 bcd。
        assertEquals(2, parsed.starts[0]);
        assertEquals(3, parsed.ends[0]);
        assertEquals(0xFF222222, parsed.colors[0]);
        assertEquals(1, parsed.starts[1]);
        assertEquals(4, parsed.ends[1]);
        assertEquals(0xFF111111, parsed.colors[1]);
    }

    @Test
    public void unclosedTagColorsTheRemainderAndUnknownTagsStayLiteral() {
        FocusRichText.Parsed unclosed = FocusRichText.parse(
                "前<font color='#123456'>后");
        assertEquals("前后", unclosed.text);
        assertEquals(1, unclosed.count);
        assertEquals(1, unclosed.starts[0]);
        assertEquals(2, unclosed.ends[0]);

        FocusRichText.Parsed unknown = FocusRichText.parse("a<b>c</b><font color='bad'>d</font>");
        assertEquals("a<b>c</b>d", unknown.text);
        assertEquals(0, unknown.count);
    }

    @Test
    public void invalidOrOversizedTagsDoNotConsumeRealText() {
        FocusRichText.Parsed invalid = FocusRichText.parse(
                "x<font color='#12'>y</font>z");
        assertEquals("xyz", invalid.text);
        assertEquals(0, invalid.count);

        String oversized = "a<font color='" + repeat('#', 300) + "'>b";
        FocusRichText.Parsed parsed = FocusRichText.parse(oversized);
        assertFalse(parsed.changed);
        assertEquals(oversized, parsed.text);
    }

    @Test
    public void strayClosingTagIsLeftAsText() {
        FocusRichText.Parsed parsed = FocusRichText.parse("a</font>b");
        assertFalse(parsed.changed);
        assertEquals("a</font>b", parsed.text);
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
