/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focustestnotifier;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 核对虚构样本和协议层级，不把真实敏感字面量重新写进测试。 */
public class FocusPayloadsTest {
    private static final List<String> PAYLOADS = Arrays.asList(
            FocusPayloads.TRAVEL_PARAM_CUSTOM, FocusPayloads.TRAVEL_WITHOUT_AOD_TITLE,
            FocusPayloads.ISLAND_PARAM, FocusPayloads.ISLAND_SIDES,
            FocusPayloads.ISLAND_SMALL_ONLY, FocusPayloads.ISLAND_ICON_PARAM,
            FocusPayloads.TAKEOUT_COLORED_FONT);

    @Test public void allHistoricalStructuresRemainValidJson() throws Exception {
        for (String payload : PAYLOADS) assertTrue(new JSONObject(payload).length() > 0);
    }

    @Test public void takeoutUsesExplicitlyFictionalShopAndConsistentProgress() throws Exception {
        JSONObject root = new JSONObject(FocusPayloads.TAKEOUT_COLORED_FONT);
        JSONObject v2 = root.getJSONObject("param_v2");
        assertEquals("测试餐厅·示例分店", v2.getJSONObject("baseInfo").getString("subContent"));
        assertEquals(50, root.getInt("progress"));
        assertEquals(50, v2.getJSONObject("progressInfo").getInt("progress"));
        assertEquals("距测试商家100m，预计2分钟到达 ", root.getString("desc2"));
    }

    @Test public void syntheticDeliveryTimeKeepsColorMarkupAndIslandStructure() throws Exception {
        JSONObject root = new JSONObject(FocusPayloads.TAKEOUT_COLORED_FONT);
        JSONObject v2 = root.getJSONObject("param_v2");
        assertEquals("预计12:00送达", root.getString("title"));
        assertEquals("预计<font color='#0FAD50'>12:00</font>送达", v2.getJSONObject("baseInfo").getString("title"));
        assertEquals("12:00送达", v2.getString("aodTitle"));
        assertEquals("12:00", v2.getJSONObject("param_island").getJSONObject("bigIslandArea")
                .getJSONObject("textInfo").getString("title"));
        assertEquals(2, v2.getJSONObject("baseInfo").getInt("type"));
    }

    @Test public void travelUsesFictionalTrainAndEndpoints() throws Exception {
        for (String payload : Arrays.asList(FocusPayloads.TRAVEL_PARAM_CUSTOM, FocusPayloads.TRAVEL_WITHOUT_AOD_TITLE)) {
            assertEquals("测试车次", new JSONObject(payload).getJSONObject("param_island")
                    .getJSONObject("bigIslandArea").getJSONObject("imageTextInfoLeft")
                    .getJSONObject("textInfo").getString("title"));
        }
        JSONObject sides = new JSONObject(FocusPayloads.ISLAND_SIDES).getJSONObject("param_v2")
                .getJSONObject("param_island").getJSONObject("bigIslandArea");
        assertEquals("测试起点站", sides.getJSONObject("imageTextInfoLeft").getJSONObject("textInfo").getString("title"));
        assertEquals("测试终点站", sides.getJSONObject("imageTextInfoRight").getJSONObject("textInfo").getString("title"));
    }

    @Test public void payloadsCarryNoOrderLinksOrPersonalIdentifierFields() throws Exception {
        for (String payload : PAYLOADS) inspect(new JSONObject(payload));
    }

    private static void inspect(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                assertFalse("Unexpected personal/link field: " + key, Arrays.asList(
                        "url", "orderId", "sequence", "phone", "mobile", "address", "userId",
                        "uid", "token", "latitude", "longitude").contains(key));
                inspect(object.get(key));
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) inspect(array.get(i));
        } else if (value instanceof String) {
            assertFalse(((String) value).contains("://"));
        }
    }
}
