/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static org.junit.Assert.*;

public class SettingsSearchIndexTest {
    @Test public void advancedMaterialCanBeFoundByNameAndGlassKeyword() {
        assertEquals("advancedMaterial", SettingsSearchIndex.search("高级材质接入", false).get(0).id);
        assertEquals("advancedMaterial", SettingsSearchIndex.search("玻璃", false).get(0).id);
    }

    @Test public void exactTitleComesBeforeRelatedKeywords() {
        List<SettingsSearchIndex.Entry> result = SettingsSearchIndex.search("最大宽度", false);
        assertEquals("width", result.get(0).id);
        assertTrue(ids(result).contains("landscapeWidth"));
    }

    @Test public void keywordsLocateSettingsWithoutMatchingPrivateValues() {
        assertEquals("mediaCast", SettingsSearchIndex.search("妙播", false).get(0).id);
        assertEquals("iconOrder", SettingsSearchIndex.search("图标排序", false).get(0).id);
        assertEquals("iconSize", SettingsSearchIndex.search("图标大小", false).get(0).id);
        assertEquals("iconOrder", SettingsSearchIndex.search("smallIcon 排序", false).get(0).id);
        assertEquals("generalSeparator", SettingsSearchIndex.search("普通 分隔符", false).get(0).id);
        assertEquals("dividerStart", SettingsSearchIndex.search("时间侧", false).get(0).id);
        assertTrue(SettingsSearchIndex.search("my-private-app-package", true).isEmpty());
    }

    @Test public void normalizationSupportsFullWidthMixedCaseAndUnicodeWhitespace() {
        assertEquals(ids(SettingsSearchIndex.search("OS4", false)),
                ids(SettingsSearchIndex.search("　Ｏｓ４\t", false)));
        assertEquals(ids(SettingsSearchIndex.search("smallIcon 排序", false)),
                ids(SettingsSearchIndex.search("ＳＭＡＬＬＩＣＯＮ\u00a0排序", false)));
    }

    @Test public void allTermsMustMatchEvenWhenTheyAreInDifferentFields() {
        assertEquals("iconGap", SettingsSearchIndex.search("高级 图文距离", false).get(0).id);
        assertTrue(SettingsSearchIndex.search("图文距离 不存在的词", false).isEmpty());
        assertEquals("export", SettingsSearchIndex.search("关于 JSON 导出", false).get(0).id);
    }

    @Test public void releaseNeverOffersUnavailableDebugSwitches() {
        assertTrue(SettingsSearchIndex.search("feature.island.debug", false).isEmpty());
        assertEquals("debugProperty", SettingsSearchIndex.search("feature.island.debug", true).get(0).id);
        assertEquals(SettingsSearchIndex.search("", false).size() + 2,
                SettingsSearchIndex.search("", true).size());
    }

    @Test public void emptySearchIsCompleteStableAndHasUniqueNavigationIds() {
        List<SettingsSearchIndex.Entry> all = SettingsSearchIndex.search(null, true);
        assertTrue(all.size() >= 48);
        Set<String> unique = new HashSet<>();
        for (SettingsSearchIndex.Entry entry : all) {
            assertTrue(unique.add(entry.id));
            assertTrue(entry.page >= 0 && entry.page <= 2);
            assertFalse(entry.title.trim().isEmpty());
            assertFalse(entry.section.trim().isEmpty());
        }
        assertEquals(ids(all), ids(SettingsSearchIndex.search("   ", true)));
        assertEquals("mode", all.get(0).id);
        assertThrows(UnsupportedOperationException.class, () -> all.clear());
    }

    @Test public void lowercaseDoesNotDependOnDeviceLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("iconOrder", SettingsSearchIndex.search("SMALLICON 排序", false).get(0).id);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test public void movedControlsNavigateToAdvancedWhileModeStaysHome() {
        for (SettingsSearchIndex.Entry entry : SettingsSearchIndex.search("", false)) {
            if (entry.id.equals("mode")) assertEquals(0, entry.page);
            else if (!entry.id.equals("export") && !entry.id.equals("import")) assertEquals(1, entry.page);
        }
        assertEquals("width", SettingsSearchIndex.search("高级 最大宽度", false).get(0).id);
        assertTrue(SettingsSearchIndex.search("主页 白名单", false).isEmpty());
    }

    private static Set<String> ids(List<SettingsSearchIndex.Entry> entries) {
        Set<String> ids = new HashSet<>();
        for (SettingsSearchIndex.Entry entry : entries) ids.add(entry.id);
        return ids;
    }
}
