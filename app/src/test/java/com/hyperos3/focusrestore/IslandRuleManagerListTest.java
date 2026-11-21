/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import static com.hyperos3.focusrestore.IslandRuleManagerUi.DisplayOrder.*;
import static com.hyperos3.focusrestore.IslandRuleManagerUi.StatusFilter.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class IslandRuleManagerListTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test public void searchesLabelsAndPackagesWithoutDroppingHiddenSelection() throws Exception {
        List<IslandRuleManagerUi.Row> rows = rows();
        assertEquals(Arrays.asList(rows.get(2), rows.get(0)), IslandRuleManagerUi.visibleRows(rows, "  ZULU ", ALL, SAVED));
        assertEquals(Collections.singletonList(rows.get(1)), IslandRuleManagerUi.visibleRows(rows, "COM.TEST.ALPHA", ALL, SAVED));
        List<IslandRuleManagerUi.Row> disabled = IslandRuleManagerUi.visibleRows(rows, "test", DISABLED, SAVED);
        assertEquals(Collections.singletonList(rows.get(0)), disabled);
        // 搜索和筛选只影响列表展示。已选条目即使被筛掉仍保留，批量操作会提示当前结果外的数量。
        Set<String> selected = new HashSet<>(Arrays.asList(rows.get(0).entry.id, rows.get(1).entry.id));
        assertTrue(IslandRuleManagerUi.visibleRows(rows, "different", ALL, APP).isEmpty());
        assertEquals(2, selected.size());
        assertTrue(selected.contains(rows.get(0).entry.id));
        assertTrue(selected.contains(rows.get(1).entry.id));
    }

    @Test public void appliesDisplaySortingWithoutChangingDeclarationOrder() throws Exception {
        List<IslandRuleManagerUi.Row> rows = rows();
        IslandRuleManagerUi.Row a = rows.get(0), b = rows.get(1), c = rows.get(2);
        assertEquals(Arrays.asList(b, c, a), IslandRuleManagerUi.visibleRows(rows, "", ALL, SAVED));
        assertEquals(Arrays.asList(b, a, c), IslandRuleManagerUi.visibleRows(rows, "", ALL, APP));
        assertEquals(Arrays.asList(b, c, a), IslandRuleManagerUi.visibleRows(rows, "", ALL, STATE));
        assertEquals(Arrays.asList(a, b, c), rows);
        assertEquals(0, a.entry.order);
        assertEquals(9, a.entry.priority);
    }

    private List<IslandRuleManagerUi.Row> rows() throws Exception {
        IslandRuleRepository repository = new IslandRuleRepository(folder.newFolder().toPath(), ignored -> {});
        String fixture = new String(Files.readAllBytes(Paths.get("docs", "samples", "rule-travel.valid.json")),
                StandardCharsets.UTF_8);
        List<IslandRuleManagerUi.Row> rows = new ArrayList<>();
        add(rows, repository, fixture, "Zulu", "com.test.beta", 3, 9, false);
        add(rows, repository, fixture, "Alpha", "com.test.alpha", 5, 2, true);
        add(rows, repository, fixture, "Zulu", "com.test.zeta", 5, 1, true);
        return rows;
    }

    private void add(List<IslandRuleManagerUi.Row> rows, IslandRuleRepository repository, String fixture,
            String name, String pkg, long timestamp, int priority, boolean enabled) throws Exception {
        byte[] bytes = fixture.replace("com.miui.personalassistant", pkg).getBytes(StandardCharsets.UTF_8);
        try (IslandRuleRepository.Pending pending = repository.prepare(new ByteArrayInputStream(bytes), null,
                enabled, priority, null)) {
            repository.commit(pending, false, null);
            rows.add(new IslandRuleManagerUi.Row(pending.entry, name, new IslandRuleCodec().decode(bytes).rule,
                    null, timestamp));
        }
    }
}
