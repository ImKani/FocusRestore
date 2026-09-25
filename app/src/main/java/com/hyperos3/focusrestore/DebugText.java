package com.hyperos3.focusrestore;

import java.util.Locale;

/**
 * Formatting for the debug-only diagnostics. Deliberately free of Android APIs so the rendering and
 * escaping rules stay unit-testable.
 */
final class DebugText {
    private static final int MAX_PREVIEW_CHARS = 120;

    private DebugText() {
    }

    /**
     * Bounded single-line preview of a value. When the text contains non-ASCII characters an escaped
     * copy is appended, because a log captured through a non-UTF-8 console mangles the raw bytes and
     * turns a diagnostic into noise: {@code 检票口} arrived in the field log as {@code 妫€绁ㄥ彛}.
     * The escaped form is pure ASCII, so it survives any capture encoding.
     */
    static String preview(String value) {
        if (value == null) return "null";
        String bounded = flatten(value);
        if (bounded.length() > MAX_PREVIEW_CHARS) {
            bounded = bounded.substring(0, MAX_PREVIEW_CHARS) + "...";
        }
        String escaped = escapeNonAscii(bounded);
        return escaped.equals(bounded) ? bounded : bounded + " [escaped=" + escaped + "]";
    }

    /**
     * Collapses line breaks and tabs (a CRLF pair must not become two spaces) and trims the result,
     * while deliberately preserving ordinary spaces: they carry information when the point of the
     * preview is to check how a payload's segments were joined.
     */
    private static String flatten(String value) {
        StringBuilder text = new StringBuilder(value.length());
        boolean lastWasBreak = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\n' || character == '\r' || character == '\t') {
                if (!lastWasBreak && text.length() > 0) text.append(' ');
                lastWasBreak = true;
                continue;
            }
            lastWasBreak = false;
            text.append(character);
        }
        return text.toString().trim();
    }

    /** Renders every non-ASCII or control code point as a backslash-u escape. Never returns null. */
    static String escapeNonAscii(String value) {
        if (value == null) return "null";
        StringBuilder text = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= 0x20 && character < 0x7F) {
                text.append(character);
            } else {
                text.append(String.format(Locale.US, "\\u%04x", (int) character));
            }
        }
        return text.toString();
    }
}
