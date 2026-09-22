package com.hyperos3.focusrestore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class FocusBannerModelTest {
    @Test
    public void weatherKeepsMainAndSecondaryContentInsteadOfCategoryTicker() throws Exception {
        FocusBannerModel model = parseV2(new JSONObject()
                .put("ticker", "Weather")
                .put("baseInfo", new JSONObject().put("title", "Heavy Snow")
                        .put("subTitle", "Red Alert").put("content", "Roads closed")));
        assertEquals("Heavy Snow", model.title);
        assertTrue(model.text.contains("Red Alert"));
        assertTrue(model.text.contains("Roads closed"));
        assertFalse(model.text.contains("Weather"));
        assertEquals(0, model.progressMax);
        assertNull(model.timer);
    }

    @Test
    public void officialProtocolOneInsideV2StillUsesBaseAndHintComponents() throws Exception {
        FocusBannerModel model = parseV2(new JSONObject().put("protocol", 1)
                .put("baseInfo", new JSONObject().put("title", "待取件")
                        .put("content", "安宁华庭2区8号底商店菜鸟驿站"))
                .put("hintInfo", new JSONObject().put("title", "2件包裹")
                        .put("actionInfo", new JSONObject().put("action", "miui.focus.action_test"))));
        assertEquals("待取件", model.title);
        assertTrue(model.text.contains("菜鸟驿站"));
        assertTrue(model.text.contains("2件包裹"));
        assertEquals("miui.focus.action_test", model.actions.get(0).key);
    }

    @Test
    public void legacyVerificationContentAndButtonLabelsAreNotFilteredAsStatusBarText() throws Exception {
        FocusBannerModel model = FocusBannerModel.parse(new JSONObject().put("protocol", 1)
                .put("scene", "verifyCode").put("title", "验证码")
                .put("desc1", "123456").put("desc2", "Copy")
                .put("actions", new JSONArray().put(new JSONObject()
                        .put("action", "copy").put("actionTitle", "Copy"))).toString());
        assertNotNull(model);
        assertEquals("验证码", model.title);
        assertEquals("123456\nCopy", model.text);
        assertEquals("Copy", model.actions.get(0).title);
    }

    @Test
    public void documentedIslandSidesAndImageReferencesAreExtracted() throws Exception {
        JSONObject side = new JSONObject()
                .put("picInfo", new JSONObject().put("pic", "miui.focus.pic_large"))
                .put("miui.focus.paramtextInfo", new JSONObject()
                        .put("frontTitle", "充电中").put("title", "24%").put("content", "剩5分钟"));
        FocusBannerModel model = parseV2(new JSONObject().put("param_island", new JSONObject()
                .put("bigIslandArea", new JSONObject().put("imageTextInfoLeft", side))
                .put("smallIslandArea", new JSONObject().put("picInfo", new JSONObject()
                        .put("pic", "miui.focus.pic_small")))));
        assertEquals("充电中", model.title);
        assertTrue(model.text.contains("24%"));
        assertTrue(model.text.contains("剩5分钟"));
        assertEquals("miui.focus.pic_small", model.iconKey);
    }

    @Test
    public void voipWrapperPreservesTextAndRunningWallClockTimer() throws Exception {
        long start = 1717470687604L;
        FocusBannerModel model = FocusBannerModel.parse(new JSONObject().put("param_voip_v2",
                new JSONObject().put("chatInfo", new JSONObject().put("title", "18212345678")
                        .put("content", "北京联通").put("timerInfo", new JSONObject()
                                .put("timerType", 1).put("timerWhen", start)))).toString());
        assertNotNull(model);
        assertEquals("18212345678", model.title);
        assertEquals("北京联通", model.text);
        assertEquals(start, model.timer.when);
        assertFalse(model.timer.countDown);
        assertEquals(0, model.timer.clockType);
    }

    @Test
    public void countdownHasExplicitDirectionAndDoesNotUseGuessedClockType() throws Exception {
        FocusBannerModel model = withTimer(new JSONObject().put("timerType", -1)
                .put("timerWhen", 1717470687604L).put("clockType", 99));
        assertNotNull(model.timer);
        assertTrue(model.timer.countDown);
        assertEquals(0, model.timer.clockType);
    }

    @Test
    public void pausedUnknownAndMalformedTimersKeepTextWithoutRunningClock() throws Exception {
        for (int type : new int[]{-2, 0, 2, 99}) {
            FocusBannerModel model = withTimer(new JSONObject().put("timerType", type)
                    .put("timerWhen", 1717470687604L));
            assertNull(model.timer);
            assertEquals("Timer", model.title);
        }
        assertNull(withTimer(new JSONObject().put("timerType", 1)).timer);
        assertNull(withTimer(new JSONObject().put("timerType", 1).put("timerWhen", -1)).timer);
        assertNull(withTimer(new JSONObject().put("timerType", 1).put("timerWhen", "1234%")).timer);
        assertNull(withTimer(new JSONObject().put("timerType", 1).put("timerWhen", "1717470687604")).timer);
        assertNull(withTimer(new JSONObject().put("when", 10000L).put("countDown", false)).timer);
    }

    @Test
    public void progressDisplaysActualScaleAndRejectsInvalidBounds() throws Exception {
        FocusBannerModel percentage = withProgress(new JSONObject().put("progress", 40));
        assertEquals(40, percentage.progress);
        assertEquals(100, percentage.progressMax);
        assertTrue(percentage.text.contains("40%"));
        FocusBannerModel scaled = withProgress(new JSONObject().put("progress", 50).put("max", 200));
        assertEquals(50, scaled.progress);
        assertEquals(200, scaled.progressMax);
        assertTrue(scaled.text.contains("50/200"));
        assertFalse(scaled.text.contains("50%"));
        for (JSONObject invalid : new JSONObject[]{
                new JSONObject().put("progress", -1),
                new JSONObject().put("progress", 101),
                new JSONObject().put("progress", 1).put("max", 0),
                new JSONObject().put("progress", 1).put("max", Long.MAX_VALUE),
                new JSONObject().put("progress", "bad")}) {
            FocusBannerModel model = withProgress(invalid);
            assertEquals(0, model.progressMax);
            assertEquals("Task", model.title);
        }
        assertTrue(withProgress(new JSONObject().put("indeterminate", true)).progressIndeterminate);
    }

    @Test
    public void actionsKeepExactKeysDeduplicateCapAndCannotBeMutated() throws Exception {
        JSONArray actions = new JSONArray().put(new JSONObject().put("action", " exact ")
                .put("actionTitle", "稍后提醒"));
        actions.put(new JSONObject().put("action", " exact "));
        actions.put(17).put(true).put(JSONObject.NULL);
        for (int i = 0; i < 8; i++) actions.put(new JSONObject().put("action", "key" + i));
        FocusBannerModel model = parseV2(new JSONObject().put("actions", actions));
        assertEquals(4, model.actions.size());
        assertEquals(" exact ", model.actions.get(0).key);
        assertEquals("稍后提醒", model.actions.get(0).title);
        assertEquals("key0", model.actions.get(1).key);
        assertThrows(UnsupportedOperationException.class, () -> model.actions.clear());
    }

    @Test
    public void overlongKeysAreRejectedInsteadOfBecomingDifferentReferences() throws Exception {
        String longKey = repeat('a', InputLimits.MAX_OUTPUT_CHARS + 1);
        FocusBannerModel model = parseV2(new JSONObject()
                .put("baseInfo", new JSONObject().put("title", "Task").put("iconKey", longKey))
                .put("actions", new JSONArray().put(longKey)
                        .put(new JSONObject().put("action", longKey))));
        assertTrue(model.actions.isEmpty());
        assertEquals("", model.iconKey);
    }

    @Test
    public void arbitraryIntentDescriptionsAndUnreferencedCoverImagesAreNotActionsOrIcons() throws Exception {
        FocusBannerModel model = parseV2(new JSONObject()
                .put("baseInfo", new JSONObject().put("title", "Task")
                        .put("picCover", "miui.focus.pic_cover").put("picBg", "miui.focus.pic_bg"))
                .put("actions", new JSONArray().put(new JSONObject().put("actionTitle", "Open")
                        .put("actionIntent", "intent://example").put("actionIntentType", 1))));
        assertTrue(model.actions.isEmpty());
        assertEquals("", model.iconKey);
        FocusBannerModel remote = parseV2(new JSONObject().put("baseInfo", new JSONObject()
                .put("title", "Task").put("picFunction", "https://example.invalid/image.png")));
        assertEquals("", remote.iconKey);
    }

    @Test
    public void malformedUnknownAndEmptyPayloadsPermitNotificationFallback() throws Exception {
        for (String payload : new String[]{null, "", " ", "{invalid", "{}", "[]",
                "{\"unrelated\":\"data\"}", "{\"param_v2\":{}}",
                "{\"param_v2\":{\"baseInfo\":{\"title\":null,\"content\":true}}}"}) {
            assertNull(payload, FocusBannerModel.parse(payload));
        }
    }

    @Test
    public void payloadTextAndNestingAreBoundedBeforeRecursiveJsonParsing() throws Exception {
        String oversized = repeat('x', InputLimits.MAX_PAYLOAD_CHARS + 1);
        assertNull(FocusBannerModel.parse(oversized));
        assertNull(FocusBannerModel.parse(new JSONObject().put("param_v2", new JSONObject()
                .put("baseInfo", new JSONObject().put("title",
                        repeat('中', InputLimits.MAX_PAYLOAD_UTF8_BYTES / 3 + 1)))).toString()));
        assertNull(FocusBannerModel.parse(repeat('[', 1000) + "0" + repeat(']', 1000)));
        String text = repeat('x', InputLimits.MAX_OUTPUT_CHARS - 1) + "😀";
        FocusBannerModel model = parseV2(new JSONObject().put("baseInfo", new JSONObject().put("title", text)));
        assertEquals(InputLimits.MAX_OUTPUT_CHARS - 1, model.title.length());
        assertFalse(Character.isHighSurrogate(model.title.charAt(model.title.length() - 1)));
    }

    @Test
    public void nestingGuardDoesNotCountBracketsOrEscapedQuotesInsideText() throws Exception {
        String text = "[ { \"quoted\" \\ path } ]";
        FocusBannerModel model = parseV2(new JSONObject().put("baseInfo", new JSONObject().put("title", text)));
        assertEquals(text, model.title);
    }

    private static FocusBannerModel parseV2(JSONObject content) throws Exception {
        FocusBannerModel model = FocusBannerModel.parse(new JSONObject().put("param_v2", content).toString());
        assertNotNull(model);
        assertTrue(model.hasContent());
        return model;
    }

    private static FocusBannerModel withTimer(JSONObject timer) throws Exception {
        return parseV2(new JSONObject().put("highlightInfo", new JSONObject()
                .put("title", "Timer").put("timerInfo", timer)));
    }

    private static FocusBannerModel withProgress(JSONObject progress) throws Exception {
        return parseV2(new JSONObject().put("baseInfo", new JSONObject().put("title", "Task"))
                .put("progressInfo", progress));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
