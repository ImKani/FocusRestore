/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focustestnotifier;

import org.json.JSONObject;
import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

/** 使用主项目已有的离线JUnit/JSON依赖运行，不给工具增加运行时或构建依赖。 */
public class NotificationTestCasesTest {
    @Test public void samplesKeepUniqueIdsAndImmutableOrder() {
        assertEquals(9, NotificationTestCases.ALL.size());
        Set<Integer> ids = new HashSet<>();
        for (NotificationTestCases.Spec spec : NotificationTestCases.ALL) {
            assertTrue(ids.add(spec.id));
            assertFalse(spec.label.isEmpty());
            assertFalse(spec.hint.isEmpty());
        }
        assertThrows(UnsupportedOperationException.class, () -> NotificationTestCases.ALL.clear());
    }
    @Test public void plainAndNativeAreIndependentControls() {
        assertTrue(NotificationTestCases.at(0).plain);
        assertNull(NotificationTestCases.at(0).payload());
        assertTrue(NotificationTestCases.at(1).nativeFocus);
        assertNull(NotificationTestCases.at(1).payload());
    }
    @Test public void everyIslandSampleProvidesRomAreaABody() throws Exception {
        for (NotificationTestCases.Spec spec : NotificationTestCases.ALL) {
            if (spec.payload() == null) continue;
            JSONObject v2 = new JSONObject(spec.payload()).getJSONObject("param_v2");
            JSONObject base = v2.getJSONObject("baseInfo");
            assertEquals(2, base.getInt("type"));
            assertEquals(spec.left, base.getString("title"));
            assertFalse(base.getString("title").trim().isEmpty());
            assertTrue(v2.getBoolean("isShowNotification"));
            assertFalse(v2.getBoolean("enableFloat"));
            assertFalse(v2.getBoolean("islandFirstFloat"));
            assertEquals("reopen", v2.getString("reopen"));
            assertNotNull(v2.getJSONObject("param_island").getJSONObject("smallIslandArea"));
        }
    }
    @Test public void onlyIslandIconCaseContainsPictureReference() throws Exception {
        for (NotificationTestCases.Spec spec : NotificationTestCases.ALL) {
            if (spec.payload() == null) continue;
            JSONObject island = new JSONObject(spec.payload()).getJSONObject("param_v2").getJSONObject("param_island");
            assertEquals(spec.icons == NotificationTestCases.ISLAND,
                    island.getJSONObject("smallIslandArea").has("picInfo"));
        }
    }
    @Test public void sidesAreAvailableInBothBodyAndIsland() throws Exception {
        JSONObject v2 = new JSONObject(NotificationTestCases.at(4).payload()).getJSONObject("param_v2");
        assertEquals("起点", v2.getJSONObject("baseInfo").getString("title"));
        assertEquals("终点", v2.getJSONObject("baseInfo").getString("content"));
        assertEquals("终点", v2.getJSONObject("param_island").getJSONObject("bigIslandArea")
                .getJSONObject("imageTextInfoRight").getJSONObject("textInfo").getString("title"));
    }
    @Test public void longTextAndColorRemainActualInputs() {
        assertTrue(NotificationTestCases.at(3).left.length() > NotificationTestCases.at(2).left.length() * 5);
        assertTrue(NotificationTestCases.at(5).payload().contains("<font color='#0FAD50'>"));
        assertFalse(NotificationTestCases.at(5).payload().contains("com.miui.voiceassist"));
    }
    @Test public void restoredPositionIsBounded() {
        assertSame(NotificationTestCases.ALL.get(0), NotificationTestCases.at(-1));
        assertSame(NotificationTestCases.ALL.get(8), NotificationTestCases.at(Integer.MAX_VALUE));
    }
}
