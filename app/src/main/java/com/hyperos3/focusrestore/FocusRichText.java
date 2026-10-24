/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;

/**
 * 把超级岛文本里的 {@code <font color="...">} 标记转换为带颜色的字符序列。
 *
 * <p>来源：作者提供的 0.40.2 设备日志 {@code log/focus-restore-os3-0.40.2.log}
 * 中，{@code com.taobao.taobao} 的 baseInfo 文本包含
 * {@code <font color='#0FAD50'>18:26</font>}；模块此前把整段字符串直接写入焦点 TextView，
 * 标记因此按字面显示。此处只按日志观测到的 font/color 子集独立解析，未复制 ROM 的解析实现；
 * 颜色跨度使用 Android 公开的 {@link ForegroundColorSpan}。
 *
 * <p>解析与应用分开：{@link #parse(String)} 不依赖 Android 文本类，可在纯 JVM 测试中验证；
 * {@link #apply(String)} 才创建 {@link SpannableString}。
 */
final class FocusRichText {
    /** 单个标签的合理上限；超长“标签”按普通文本保留，避免在长文本上反复拼接。 */
    private static final int MAX_TAG_LENGTH = 256;
    /** 无有效颜色时的占位；实际颜色 alpha 位不会为 0，因此可与合法黑色区分。 */
    private static final int INVALID_COLOR = 0;

    private FocusRichText() {
    }

    static final class Parsed {
        final String text;
        final int[] starts;
        final int[] ends;
        final int[] colors;
        final int count;
        final boolean changed;

        Parsed(String text, int[] starts, int[] ends, int[] colors, int count, boolean changed) {
            this.text = text;
            this.starts = starts;
            this.ends = ends;
            this.colors = colors;
            this.count = count;
            this.changed = changed;
        }
    }

    /** 去掉可识别的 font 标记，并记录每段文字的颜色范围。 */
    static Parsed parse(String raw) {
        if (raw == null || raw.indexOf('<') < 0) {
            return new Parsed(raw, new int[0], new int[0], new int[0], 0, false);
        }
        StringBuilder text = new StringBuilder(raw.length());
        IntStack starts = new IntStack();
        IntStack colors = new IntStack();
        IntStack rangesStart = new IntStack();
        IntStack rangesEnd = new IntStack();
        IntStack rangeColors = new IntStack();
        boolean changed = false;
        int index = 0;
        while (index < raw.length()) {
            char current = raw.charAt(index);
            if (current != '<') {
                text.append(current);
                index++;
                continue;
            }
            int tagEnd = tagEnd(raw, index);
            if (tagEnd < 0) {
                text.append(current);
                index++;
                continue;
            }
            String tag = raw.substring(index, tagEnd + 1);
            if (isFontOpen(tag)) {
                changed = true;
                starts.push(text.length());
                colors.push(colorAttribute(tag));
                index = tagEnd + 1;
                continue;
            }
            if (isFontClose(tag) && starts.size() > 0) {
                changed = true;
                int start = starts.pop();
                int color = colors.pop();
                addRange(rangesStart, rangesEnd, rangeColors, start, text.length(), color);
                index = tagEnd + 1;
                continue;
            }
            text.append(current);
            index++;
        }
        // 未闭合的 font 只影响其后的文本，按常见 HTML 容错语义补到结尾。
        while (starts.size() > 0) {
            int start = starts.pop();
            int color = colors.pop();
            addRange(rangesStart, rangesEnd, rangeColors, start, text.length(), color);
        }
        if (!changed) return new Parsed(raw, new int[0], new int[0], new int[0], 0, false);
        return new Parsed(text.toString(), rangesStart.toArray(), rangesEnd.toArray(),
                rangeColors.toArray(), rangesStart.size(), true);
    }

    /** 设备侧应用：无标记时返回原字符串，有标记时返回带颜色跨度的字符序列。 */
    static CharSequence apply(String raw) {
        Parsed parsed = parse(raw);
        if (!parsed.changed) return raw;
        if (parsed.count == 0) return parsed.text;
        SpannableString styled = new SpannableString(parsed.text);
        for (int index = 0; index < parsed.count; index++) {
            styled.setSpan(new ForegroundColorSpan(parsed.colors[index]),
                    parsed.starts[index], parsed.ends[index],
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return styled;
    }

    /**
     * TextView 是否需要重写文本。
     *
     * <p>只比较字符不够：ROM 可能用同样字符的普通字符串覆盖带颜色的文本，或用户刚关闭彩色
     * 字体开关。这里同时比较前景色跨度的数量、位置和颜色；一致时返回 true，避免每个布局帧
     * 都重设 TextView（会打断滚动并刷日志）。
     */
    static boolean displays(CharSequence current, CharSequence expected) {
        if (!TextUtils.equals(current, expected)) return false;
        ForegroundColorSpan[] expectedSpans = spans(expected);
        ForegroundColorSpan[] currentSpans = spans(current);
        if (expectedSpans.length == 0) return currentSpans.length == 0;
        if (currentSpans.length != expectedSpans.length) return false;
        boolean[] matched = new boolean[currentSpans.length];
        for (ForegroundColorSpan expectedSpan : expectedSpans) {
            int expectedStart = ((Spanned) expected).getSpanStart(expectedSpan);
            int expectedEnd = ((Spanned) expected).getSpanEnd(expectedSpan);
            boolean found = false;
            for (int index = 0; index < currentSpans.length; index++) {
                if (matched[index]) continue;
                ForegroundColorSpan currentSpan = currentSpans[index];
                if (currentSpan.getForegroundColor() == expectedSpan.getForegroundColor()
                        && ((Spanned) current).getSpanStart(currentSpan) == expectedStart
                        && ((Spanned) current).getSpanEnd(currentSpan) == expectedEnd) {
                    matched[index] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static ForegroundColorSpan[] spans(CharSequence value) {
        if (!(value instanceof Spanned)) return new ForegroundColorSpan[0];
        Spanned spanned = (Spanned) value;
        return spanned.getSpans(0, spanned.length(), ForegroundColorSpan.class);
    }

    private static void addRange(IntStack starts, IntStack ends, IntStack colors,
                                 int start, int end, int color) {
        if (color == INVALID_COLOR || end <= start) return;
        starts.push(start);
        ends.push(end);
        colors.push(color);
    }

    private static int tagEnd(String raw, int open) {
        int limit = Math.min(raw.length(), open + MAX_TAG_LENGTH + 1);
        for (int index = open + 1; index < limit; index++) {
            if (raw.charAt(index) == '>') return index;
        }
        return -1;
    }

    private static boolean isFontOpen(String tag) {
        int index = 1;
        if (!tag.regionMatches(true, index, "font", 0, 4)) return false;
        index += 4;
        if (index >= tag.length()) return false;
        char next = tag.charAt(index);
        return next == '>' || Character.isWhitespace(next);
    }

    private static boolean isFontClose(String tag) {
        int index = 1;
        if (index >= tag.length() || tag.charAt(index) != '/') return false;
        index++;
        while (index < tag.length() && Character.isWhitespace(tag.charAt(index))) index++;
        if (!tag.regionMatches(true, index, "font", 0, 4)) return false;
        index += 4;
        while (index < tag.length() && Character.isWhitespace(tag.charAt(index))) index++;
        return index == tag.length() - 1 && tag.charAt(index) == '>';
    }

    /** 在开始标签里取 color 属性；只接受 #RGB/#ARGB/#RRGGBB/#AARRGGBB。 */
    private static int colorAttribute(String tag) {
        int index = 5;
        while (index + 5 < tag.length()) {
            if ((tag.charAt(index) == 'c' || tag.charAt(index) == 'C')
                    && (index == 0 || Character.isWhitespace(tag.charAt(index - 1)))
                    && tag.regionMatches(true, index, "color", 0, 5)) {
                int cursor = index + 5;
                while (cursor < tag.length() && Character.isWhitespace(tag.charAt(cursor))) cursor++;
                if (cursor < tag.length() && tag.charAt(cursor) == '=') {
                    cursor++;
                    while (cursor < tag.length()
                            && Character.isWhitespace(tag.charAt(cursor))) cursor++;
                    if (cursor >= tag.length()) return INVALID_COLOR;
                    char quote = tag.charAt(cursor);
                    if (quote == '\'' || quote == '"') {
                        int end = tag.indexOf(quote, cursor + 1);
                        return end < 0 ? INVALID_COLOR : hexColor(tag.substring(cursor + 1, end));
                    }
                    int end = cursor;
                    while (end < tag.length() && !Character.isWhitespace(tag.charAt(end))
                            && tag.charAt(end) != '>') end++;
                    return hexColor(tag.substring(cursor, end));
                }
            }
            index++;
        }
        return INVALID_COLOR;
    }

    private static int hexColor(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '#') return INVALID_COLOR;
        int digits = value.length() - 1;
        if (digits != 3 && digits != 4 && digits != 6 && digits != 8) return INVALID_COLOR;
        int packed = 0;
        for (int index = 1; index < value.length(); index++) {
            int digit = Character.digit(value.charAt(index), 16);
            if (digit < 0) return INVALID_COLOR;
            packed = (packed << 4) | digit;
        }
        switch (digits) {
            case 3: {
                int red = expandNibble((packed >> 8) & 0xF);
                int green = expandNibble((packed >> 4) & 0xF);
                int blue = expandNibble(packed & 0xF);
                return 0xFF000000 | (red << 16) | (green << 8) | blue;
            }
            case 4: {
                int alpha = expandNibble((packed >> 12) & 0xF);
                int red = expandNibble((packed >> 8) & 0xF);
                int green = expandNibble((packed >> 4) & 0xF);
                int blue = expandNibble(packed & 0xF);
                return (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
            case 6:
                return 0xFF000000 | packed;
            default:
                return packed;
        }
    }

    private static int expandNibble(int value) {
        return (value << 4) | value;
    }

    /** 有界栈，避免为每个标签创建集合或对象。 */
    private static final class IntStack {
        private int[] values = new int[4];
        private int size;

        void push(int value) {
            if (size == values.length) {
                int[] grown = new int[values.length * 2];
                System.arraycopy(values, 0, grown, 0, size);
                values = grown;
            }
            values[size++] = value;
        }

        int pop() {
            return values[--size];
        }

        int size() {
            return size;
        }

        int[] toArray() {
            int[] copy = new int[size];
            System.arraycopy(values, 0, copy, 0, size);
            return copy;
        }
    }
}
