/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

/**
 * Notification extras keys that carry the island / Focus parameter JSON.
 *
 * <p>Most applications publish the payload under {@link #PRIMARY}. Some system applications use a
 * suffixed key for the same schema: the MIUI travel assistant
 * ({@code com.miui.personalassistant}) publishes {@code miui.focus.param.custom} with
 * {@code protocol=1} and a complete {@code param_island} block, and previously the native banner
 * refused to open ("notification has no native V3 focus parameters") while the parser also failed
 * to produce island content.
 *
 * <p>The selection is shared by the hook and the native renderer so both always agree on which
 * payload a notification carries.
 */
final class FocusParamKeys {
    static final String PRIMARY = "miui.focus.param";
    static final String CUSTOM = "miui.focus.param.custom";

    /** Priority order; the first key with a non-empty string value wins. */
    static final String[] ORDER = {PRIMARY, CUSTOM};

    private FocusParamKeys() {
    }

    /** Reads one extras value by key; implemented by a Bundle lookup or a test map. */
    interface ValueSource {
        Object get(String key);
    }

    /**
     * Returns the payload text for the first key that holds a non-empty string, or {@code null}.
     * Non-string values are ignored instead of being coerced or cast, so a notification that puts an
     * unexpected type under one of these keys cannot break the caller.
     */
    static String pick(ValueSource source) {
        if (source == null) return null;
        for (String key : ORDER) {
            Object value = source.get(key);
            if (!(value instanceof String)) continue;
            String text = ((String) value).trim();
            if (text.length() > 0) return text;
        }
        return null;
    }

    /** The key that {@link #pick(ValueSource)} would use, for diagnostics; never null. */
    static String pickKey(ValueSource source) {
        if (source == null) return PRIMARY;
        for (String key : ORDER) {
            Object value = source.get(key);
            if (value instanceof String && ((String) value).trim().length() > 0) return key;
        }
        return PRIMARY;
    }

    /**
     * Reports every candidate key as {@code absent}, {@code blank}, {@code string:<length>} or
     * {@code type:<class>}, plus the key {@link #pickKey(ValueSource)} selected. Verbose diagnostics
     * only: it is what makes "the banner refused to open" or "the wrong payload won" answerable from
     * a single log line instead of a re-run with extra instrumentation.
     */
    static String describe(ValueSource source) {
        StringBuilder text = new StringBuilder();
        for (String key : ORDER) {
            if (text.length() > 0) text.append(' ');
            text.append(key).append('=');
            Object value;
            try {
                value = source == null ? null : source.get(key);
            } catch (Throwable ignored) {
                // Diagnostics must never be the reason a caller fails.
                text.append("error");
                continue;
            }
            if (value == null) {
                text.append("absent");
            } else if (!(value instanceof String)) {
                text.append("type:").append(value.getClass().getSimpleName());
            } else {
                int length = ((String) value).trim().length();
                text.append(length == 0 ? "blank" : "string:" + length);
            }
        }
        String picked;
        try {
            picked = pickKey(source);
        } catch (Throwable ignored) {
            picked = "error";
        }
        return text.append(" picked=").append(picked).toString();
    }
}