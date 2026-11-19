/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 规范正文路径登记；来源为本项目 IslandPayloadParser，未引入 ROM 实现。 */
public final class IslandRulePaths {
    // 跨组选择器只改变绑定的组范围，每条路径仍须对应下面的登记项。
    static final String MIXED_TEMPLATE = "mixed";

    static boolean matchesTemplate(String template, Entry registered) {
        return MIXED_TEMPLATE.equals(template) || registered.templateGroup.equals(template);
    }

    public enum SecurityLevel {
        STRUCTURAL, FREE_TEXT, UNSAFE
    }

    public static final class Entry {
        public final String path;
        public final String templateGroup;
        public final SecurityLevel securityLevel;
        public final String sourceFile;
        public final int sourceLine;

        private Entry(String path, String templateGroup, SecurityLevel securityLevel,
                      String sourceFile, int sourceLine) {
            this.path = path;
            this.templateGroup = templateGroup;
            this.securityLevel = securityLevel;
            this.sourceFile = sourceFile;
            this.sourceLine = sourceLine;
        }
    }

    private static final String SOURCE_FILE =
            "app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java";
    private static final List<Entry> ENTRIES;
    private static final Map<String, Entry> BY_PATH;

    static {
        List<Entry> entries = new ArrayList<>();
        // 基线 2b1d30d0ca507b14fd143a6ecaf3da14de1ce1a8；自由文本记录读取方法/调用起点，
        // 结构字段由 structuralSourceLine 精确记录实际叶读取行。
        add(entries, "baseInfo", 99,
                "/baseInfo/title",
                "/baseInfo/subTitle",
                "/baseInfo/specialTitle",
                "/baseInfo/extraTitle",
                "/baseInfo/content",
                "/baseInfo/subContent");
        add(entries, "highlightInfo", 104,
                "/highlightInfo/title",
                "/highlightInfo/content",
                "/highlightInfo/subContent");
        add(entries, "highlightInfoV3", 109,
                "/highlightInfoV3/primaryText",
                "/highlightInfoV3/secondaryText",
                "/highlightInfoV3/highLightText",
                "/highlightInfoV3/label");
        add(entries, "chatInfo", 114,
                "/chatInfo/title",
                "/chatInfo/content");
        add(entries, "iconTextInfo", 118,
                "/iconTextInfo/title",
                "/iconTextInfo/content",
                "/iconTextInfo/subContent");
        add(entries, "animTextInfo", 124,
                "/animTextInfo/title",
                "/animTextInfo/content");
        add(entries, "coverInfo", 128,
                "/coverInfo/title",
                "/coverInfo/content",
                "/coverInfo/subContent");
        add(entries, "hintInfo", 134,
                "/hintInfo/title",
                "/hintInfo/subTitle",
                "/hintInfo/content",
                "/hintInfo/subContent");
        add(entries, "stepInfo", 147,
                "/stepInfo/title",
                "/stepInfo/content",
                "/stepInfo/subContent",
                "/stepInfo/step");
        add(entries, "progressInfo", 396,
                "/progressInfo/title",
                "/progressInfo/content",
                "/progressInfo/label",
                "/progressInfo/progress");
        add(entries, "progressInfo", 400,
                "/progressInfo/progressInfo/title",
                "/progressInfo/progressInfo/content",
                "/progressInfo/progressInfo/label",
                "/progressInfo/progressInfo/progress");
        add(entries, "multiProgressInfo", 396,
                "/multiProgressInfo/title",
                "/multiProgressInfo/content",
                "/multiProgressInfo/label",
                "/multiProgressInfo/progress");
        add(entries, "multiProgressInfo", 400,
                "/multiProgressInfo/progressInfo/title",
                "/multiProgressInfo/progressInfo/content",
                "/multiProgressInfo/progressInfo/label",
                "/multiProgressInfo/progressInfo/progress");
        add(entries, "param_island", 412,
                "/param_island/title",
                "/param_island/content",
                "/param_island/frontTitle");
        add(entries, "param_island", 416,
                "/param_island/bigIslandArea/imageTextInfoLeft/textInfo/frontTitle",
                "/param_island/bigIslandArea/imageTextInfoLeft/textInfo/title",
                "/param_island/bigIslandArea/imageTextInfoLeft/textInfo/content",
                "/param_island/bigIslandArea/imageTextInfoLeft/textInfo/subContent",
                "/param_island/bigIslandArea/imageTextInfoLeft/miui.focus.paramtextInfo/frontTitle",
                "/param_island/bigIslandArea/imageTextInfoLeft/miui.focus.paramtextInfo/title",
                "/param_island/bigIslandArea/imageTextInfoLeft/miui.focus.paramtextInfo/content",
                "/param_island/bigIslandArea/imageTextInfoLeft/miui.focus.paramtextInfo/subContent");
        add(entries, "param_island", 419,
                "/param_island/bigIslandArea/imageTextInfoRight/textInfo/frontTitle",
                "/param_island/bigIslandArea/imageTextInfoRight/textInfo/title",
                "/param_island/bigIslandArea/imageTextInfoRight/textInfo/content",
                "/param_island/bigIslandArea/imageTextInfoRight/textInfo/subContent",
                "/param_island/bigIslandArea/imageTextInfoRight/miui.focus.paramtextInfo/frontTitle",
                "/param_island/bigIslandArea/imageTextInfoRight/miui.focus.paramtextInfo/title",
                "/param_island/bigIslandArea/imageTextInfoRight/miui.focus.paramtextInfo/content",
                "/param_island/bigIslandArea/imageTextInfoRight/miui.focus.paramtextInfo/subContent");
        add(entries, "param_island", 425,
                "/param_island/bigIslandArea/textInfo/frontTitle",
                "/param_island/bigIslandArea/textInfo/title",
                "/param_island/bigIslandArea/textInfo/content",
                "/param_island/bigIslandArea/textInfo/subContent",
                "/param_island/bigIslandArea/miui.focus.paramtextInfo/frontTitle",
                "/param_island/bigIslandArea/miui.focus.paramtextInfo/title",
                "/param_island/bigIslandArea/miui.focus.paramtextInfo/content",
                "/param_island/bigIslandArea/miui.focus.paramtextInfo/subContent");
        add(entries, "param_island", 428,
                "/param_island/bigIslandArea/progressTextInfo/title",
                "/param_island/bigIslandArea/progressTextInfo/content",
                "/param_island/bigIslandArea/progressTextInfo/label",
                "/param_island/bigIslandArea/progressTextInfo/progress",
                "/param_island/bigIslandArea/progressTextInfo/progressInfo/title",
                "/param_island/bigIslandArea/progressTextInfo/progressInfo/content",
                "/param_island/bigIslandArea/progressTextInfo/progressInfo/label",
                "/param_island/bigIslandArea/progressTextInfo/progressInfo/progress",
                "/param_island/bigIslandArea/fixedWidthDigitInfo/title",
                "/param_island/bigIslandArea/fixedWidthDigitInfo/content",
                "/param_island/bigIslandArea/fixedWidthDigitInfo/label",
                "/param_island/bigIslandArea/fixedWidthDigitInfo/progress",
                "/param_island/bigIslandArea/fixedWidthDigitInfo/progressInfo/title",
                "/param_island/bigIslandArea/fixedWidthDigitInfo/progressInfo/content",
                "/param_island/bigIslandArea/fixedWidthDigitInfo/progressInfo/label",
                "/param_island/bigIslandArea/fixedWidthDigitInfo/progressInfo/progress",
                "/param_island/bigIslandArea/sameWidthDigitInfo/title",
                "/param_island/bigIslandArea/sameWidthDigitInfo/content",
                "/param_island/bigIslandArea/sameWidthDigitInfo/label",
                "/param_island/bigIslandArea/sameWidthDigitInfo/progress",
                "/param_island/bigIslandArea/sameWidthDigitInfo/progressInfo/title",
                "/param_island/bigIslandArea/sameWidthDigitInfo/progressInfo/content",
                "/param_island/bigIslandArea/sameWidthDigitInfo/progressInfo/label",
                "/param_island/bigIslandArea/sameWidthDigitInfo/progressInfo/progress");
        add(entries, "param_island", 430,
                "/param_island/smallIslandArea/title",
                "/param_island/smallIslandArea/content",
                "/param_island/smallIslandArea/subContent");
        add(entries, "fallback", 167, "/aodTitle");
        add(entries, "fallback", 176, "/ticker");
        add(entries, "legacy", 96, "/title", "/desc1", "/desc2");

        Map<String, Entry> byPath = new LinkedHashMap<>();
        for (Entry entry : entries) {
            byPath.put(entry.path, entry);
        }
        ENTRIES = Collections.unmodifiableList(entries);
        BY_PATH = Collections.unmodifiableMap(byPath);
    }

    private IslandRulePaths() {
    }

    public static List<Entry> all() {
        return ENTRIES;
    }

    public static Entry find(String path) {
        return BY_PATH.get(path);
    }

    /** 未登记项及 unsafe 项不能因导入或模型构造而获得运行读取权限。 */
    public static boolean isRuntimeAllowed(Entry entry) {
        return entry != null && BY_PATH.get(entry.path) == entry
                && entry.securityLevel != SecurityLevel.UNSAFE;
    }

    private static void add(List<Entry> entries, String templateGroup, int sourceLine,
                            String... paths) {
        // 分级描述捕获导出政策：结构值默认保留，自由文本强制占位；运行正文均不脱敏。
        // 结构值是 progress/step 的字段语义，并不声称旧解析器已校验其输入数值类型。
        for (String path : paths) {
            int structuralLine = structuralSourceLine(path);
            entries.add(new Entry(path, templateGroup,
                    structuralLine == 0 ? SecurityLevel.FREE_TEXT : SecurityLevel.STRUCTURAL,
                    SOURCE_FILE, structuralLine == 0 ? sourceLine : structuralLine));
        }
    }

    private static int structuralSourceLine(String path) {
        switch (path) {
            case "/stepInfo/step":
                // step 的声明键在 148 行；joinTexts 经 firstText 于 460 行读取叶值。
                return 460;
            case "/progressInfo/progress":
            case "/progressInfo/progressInfo/progress":
            case "/multiProgressInfo/progress":
            case "/multiProgressInfo/progressInfo/progress":
            case "/param_island/bigIslandArea/progressTextInfo/progress":
            case "/param_island/bigIslandArea/progressTextInfo/progressInfo/progress":
            case "/param_island/bigIslandArea/fixedWidthDigitInfo/progress":
            case "/param_island/bigIslandArea/fixedWidthDigitInfo/progressInfo/progress":
            case "/param_island/bigIslandArea/sameWidthDigitInfo/progress":
            case "/param_island/bigIslandArea/sameWidthDigitInfo/progressInfo/progress":
                return 406;
            default:
                return 0;
        }
    }
}
