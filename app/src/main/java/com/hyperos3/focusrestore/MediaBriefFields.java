/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 媒体焦点胶囊正文的字段顺序与可选显示。
 *
 * <p>简短显示是独立开关；开启后按此处的顺序依次拼出选中字段，未选字段完全不出现。
 * 默认顺序复现 0.38.0 及更早的完整行为：标题 · 副标题 · 文本，播放状态固定在末尾。
 */
final class MediaBriefFields {
    static final int TITLE = 0;
    static final int SUBTEXT = 1;
    static final int TEXT = 2;
    static final int APP_NAME = 3;
    static final int PLAY_STATE = 4;
    static final int FIELD_COUNT = 5;

    private static final List<Integer> DEFAULT_ORDER =
            Collections.unmodifiableList(Arrays.asList(TITLE, SUBTEXT, TEXT, PLAY_STATE));
    static final String DEFAULT_ENCODED = "0,1,2,4";

    private MediaBriefFields() {
    }

    static String[] labels() {
        return new String[]{"标题", "副标题", "文本", "应用名", "播放状态"};
    }

    static String label(int field) {
        return field >= 0 && field < FIELD_COUNT ? labels()[field] : "";
    }

    static List<Integer> defaultOrder() {
        return DEFAULT_ORDER;
    }

    /**
     * 持久化用逗号连接；顺序即显示顺序，重复字段由 normalize 去重。
     */
    static String encode(List<Integer> fields) {
        List<Integer> normalized = normalize(fields);
        StringBuilder result = new StringBuilder();
        for (int field : normalized) {
            if (result.length() > 0) result.append(',');
            result.append(field);
        }
        return result.toString();
    }

    /**
     * 读取已存储的编码。空串表示用户关掉了全部字段；非空但一个合法字段都解析不出来时按损坏
     * 数据回退默认顺序，避免坏值被静默当成"全部关闭"。
     */
    static List<Integer> parse(String encoded) {
        if (encoded == null || encoded.trim().length() == 0) return Collections.emptyList();
        List<Integer> fields = new ArrayList<>(FIELD_COUNT);
        for (String token : encoded.split(",")) {
            int field = parseField(token);
            if (field >= 0 && !fields.contains(field)) fields.add(field);
        }
        if (fields.isEmpty()) return DEFAULT_ORDER;
        return Collections.unmodifiableList(fields);
    }

    /** 去重、丢弃越界项；空列表允许，表示"全部关闭"。 */
    static List<Integer> normalize(List<Integer> fields) {
        if (fields == null) return Collections.emptyList();
        List<Integer> result = new ArrayList<>(FIELD_COUNT);
        for (Integer value : fields) {
            if (value == null) continue;
            int field = value;
            if (field < 0 || field >= FIELD_COUNT || result.contains(field)) continue;
            result.add(field);
        }
        return Collections.unmodifiableList(result);
    }

    private static int parseField(String token) {
        if (token == null) return -1;
        String value = token.trim();
        if (value.length() != 1) return -1;
        char digit = value.charAt(0);
        if (digit < '0' || digit > '9') return -1;
        int field = digit - '0';
        return field < FIELD_COUNT ? field : -1;
    }
}
