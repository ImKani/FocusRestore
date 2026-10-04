/* SPDX-License-Identifier: GPL-3.0-only; Copyright (C) ImKani; FocusRestore: https://github.com/ImKani/FocusRestore */
package com.hyperos3.focusrestore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IslandPayloadParserTest {
    /**
     * Verbatim {@code miui.focus.param.custom} value of the field sample
     * {@code 0|com.miui.personalassistant|0|火车_sdk#...|10138} captured with
     * {@code adb shell dumpsys notification --noredact}. Keeping the real payload (including the
     * duplicated {@code aodTitle}) is what makes the merge regression reproducible.
     */
    private static final String TRAVEL_PAYLOAD =
            "{\"timeout\":470,\"enableFloat\":true,\"updatable\":true,\"reopen\":\"reopen\","
                    + "\"business\":\"train\",\"protocol\":1,\"scene\":\"personal-assistant-travel\","
                    + "\"aodTitle\":\"检票口 检票口\",\"aodPic\":\"miui.focus.travel_aod_pic\","
                    + "\"param_island\":{\"islandProperty\":1,\"highlightColor\":\"#3482FF\","
                    + "\"islandTimeout\":28200,\"bigIslandArea\":{"
                    + "\"imageTextInfoLeft\":{\"type\":1,\"picInfo\":{\"type\":1,\"pic\":\"island_pic\"},"
                    + "\"textInfo\":{\"title\":\"D8396\",\"showHighlightColor\":true}},"
                    + "\"textInfo\":{\"title\":\"检票口\",\"showHighlightColor\":true}},"
                    + "\"smallIslandArea\":{\"picInfo\":{\"type\":1,\"pic\":\"island_pic_small\"}}}}";

    @Test
    public void compactOfTheTravelPayloadKeepsBothPillSides() {
        // The ROM's collapsed pill shows the left image-text plus the area's own label (D8396 and
        // 检票口). The payload has no imageTextInfoRight, so reading only the explicit right side
        // dropped half the pill and left just "D8396".
        IslandPayloadParser.ParsedText value =
                IslandPayloadParser.parseCompact(TRAVEL_PAYLOAD, "·");
        assertEquals("D8396·检票口", value.text);
        assertEquals("param_island.compact", value.source);
    }

    @Test
    public void compactPrefersAnExplicitRightSideWithoutDuplicating() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parseCompact(
                "{\"param_island\":{\"bigIslandArea\":{"
                        + "\"imageTextInfoLeft\":{\"textInfo\":{\"title\":\"北京南\"}},"
                        + "\"imageTextInfoRight\":{\"textInfo\":{\"title\":\"上海虹桥\"}},"
                        + "\"textInfo\":{\"title\":\"车次\"}}}}",
                "·");
        assertEquals("北京南·上海虹桥", value.text);
    }

    @Test
    public void compactDoesNotRepeatALabelEqualToTheLeftSide() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parseCompact(
                "{\"param_island\":{\"bigIslandArea\":{"
                        + "\"imageTextInfoLeft\":{\"textInfo\":{\"title\":\"同文\"}},"
                        + "\"textInfo\":{\"title\":\"同文\"}}}}",
                "·");
        assertEquals("同文", value.text);
    }

    /**
     * The UMetrip (航旅纵横) flight SDK's first layout, decoded from {@code
     * com.umetrip.flightsdk.external.notification.IslandParams.apply}: it puts {@code statusTitle} on
     * {@code imageTextInfoLeft} and {@code statusInfo} straight onto {@code bigIslandArea.textInfo},
     * with {@code smallIslandArea} carrying only a picture. Same shape as the train payload.
     */
    private static final String FLIGHT_PAYLOAD_LEFT_ONLY =
            "{\"param_island\":{\"islandProperty\":1,\"highlightColor\":\"#FF6600\","
                    + "\"bigIslandArea\":{"
                    + "\"imageTextInfoLeft\":{\"type\":1,\"textInfo\":{\"title\":\"CA1234\","
                    + "\"showHighlightColor\":true}},"
                    + "\"textInfo\":{\"title\":\"登机口 B12\",\"showHighlightColor\":true}},"
                    + "\"smallIslandArea\":{\"picInfo\":{\"type\":1,\"pic\":\"island_pic_small\"}}}}";

    /**
     * The same SDK's second layout: {@code statusInfo} moves to an explicit
     * {@code imageTextInfoRight} that also carries the big-island picture.
     */
    private static final String FLIGHT_PAYLOAD_RIGHT_SIDE =
            "{\"param_island\":{\"islandProperty\":1,\"highlightColor\":\"#FF6600\","
                    + "\"bigIslandArea\":{"
                    + "\"imageTextInfoLeft\":{\"type\":1,\"textInfo\":{\"title\":\"CA1234\","
                    + "\"showHighlightColor\":true}},"
                    + "\"imageTextInfoRight\":{\"type\":3,\"picInfo\":{\"type\":1,\"pic\":\"island_pic\"},"
                    + "\"textInfo\":{\"title\":\"登机口 B12\",\"showHighlightColor\":true}}},"
                    + "\"smallIslandArea\":{\"picInfo\":{\"type\":1,\"pic\":\"island_pic_small\"}}}}";

    @Test
    public void compactOfTheFlightPayloadWithAreaLabelKeepsBothSides() {
        IslandPayloadParser.ParsedText value =
                IslandPayloadParser.parseCompact(FLIGHT_PAYLOAD_LEFT_ONLY, "·");
        assertEquals("CA1234·登机口 B12", value.text);
    }

    @Test
    public void compactOfTheFlightPayloadWithExplicitRightSideKeepsBothSides() {
        IslandPayloadParser.ParsedText value =
                IslandPayloadParser.parseCompact(FLIGHT_PAYLOAD_RIGHT_SIDE, "·");
        assertEquals("CA1234·登机口 B12", value.text);
    }

    @Test
    public void fullParseOfBothFlightShapesAgreesWithCompact() {
        assertEquals("CA1234·登机口 B12",
                IslandPayloadParser.parse(FLIGHT_PAYLOAD_LEFT_ONLY, "·", "·").text);
        assertEquals("CA1234·登机口 B12",
                IslandPayloadParser.parse(FLIGHT_PAYLOAD_RIGHT_SIDE, "·", "·").text);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    public void parsesWeatherAndKeepsHeavySnow() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"param_v2\":{\"baseInfo\":{\"title\":\"Weather\",\"content\":\"Heavy Snow\"}}}",
                "·", "·");
        assertEquals("Weather·Heavy Snow", value.text);
    }

    @Test
    public void parsesVerificationCodeInTitleContentOrder() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"protocol\":1,\"scene\":\"verifyCode\",\"title\":\"验证码\",\"desc1\":\"1234\",\"desc2\":\"Copy\"}",
                "·", "·");
        assertEquals("验证码·1234", value.text);
    }

    @Test
    public void parsesProtocolOnePayloadThatStillCarriesIslandContent() {
        // Field sample: com.miui.personalassistant travel notification. It uses protocol=1 exactly
        // like the SMS verification payload, but the meaningful text lives in param_island, so the
        // legacy title/desc fallback must not win.
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(TRAVEL_PAYLOAD, "·", "·");
        assertNotNull(value);
        // Exact text rather than contains(): the earlier loose assertion happily accepted the
        // duplicated "D8396·检票口·检票口 检票口" that shipped in 0.14.0.
        assertEquals("D8396·检票口", value.text);
        assertEquals("param_island", value.source);
    }

    @Test
    public void neverMergesTheAlwaysOnDisplayTitleIntoFocusText() {
        // This payload's aodTitle is itself duplicated by the app ("检票口 检票口") and repeats the
        // island title, so merging it showed the label three times.
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(TRAVEL_PAYLOAD, "·", "·");
        assertNotNull(value);
        assertEquals(1, countOccurrences(value.text, "检票口"));
        assertFalse(value.text.contains(" 检票口"));
    }

    @Test
    public void usesAodTitleOnlyWhenNothingElseProducedText() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"param_v2\":{\"aodTitle\":\"Gate 3\"}}", "·", "·");
        assertNotNull(value);
        assertEquals("Gate 3", value.text);
        assertEquals("aodTitle", value.source);
    }

    @Test
    public void readsBigIslandAreaTopLevelTextInfo() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"param_island\":{\"bigIslandArea\":{\"textInfo\":{\"title\":\"检票口\"}}}}",
                "·", "·");
        assertNotNull(value);
        assertEquals("检票口", value.text);
    }

    @Test
    public void keepsLegacyProtocolOneFallbackWhenNoIslandContentExists() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"protocol\":1,\"scene\":\"verifyCode\",\"title\":\"验证码\",\"desc1\":\"4321\"}",
                "·", "·");
        assertNotNull(value);
        assertEquals("验证码·4321", value.text);
        assertEquals("protocol1:verifyCode", value.source);
    }

    @Test
    public void usesSeparateSeparatorForIslandSides() {        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"param_v2\":{\"param_island\":{\"bigIslandArea\":{\"imageTextInfoLeft\":{\"textInfo\":{\"title\":\"Left\"}},\"imageTextInfoRight\":{\"textInfo\":{\"title\":\"Right\"}}}}}}}",
                "·", " | ");
        assertTrue(value.text.contains("Left | Right"));
    }

    @Test
    public void allowsEmptySeparators() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"param_v2\":{\"baseInfo\":{\"title\":\"A\",\"content\":\"B\"}}}",
                "", "");
        assertEquals("AB", value.text);
    }

    @Test
    public void mergesChatProgressAndIslandSummary() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"param_v2\":{\"chatInfo\":{\"title\":\"Delivery\",\"content\":\"Arriving\"},\"progressInfo\":{\"progress\":70},\"param_island\":{\"bigIslandArea\":{\"imageTextInfoLeft\":{\"textInfo\":{\"title\":\"Courier\"}},\"imageTextInfoRight\":{\"textInfo\":{\"title\":\"Nearby\"}}}}}}}",
                "·", " | ");
        assertEquals("Delivery·Arriving·70%·Courier | Nearby", value.text);
    }

    @Test
    public void doesNotDuplicateIslandTextAlreadyInMainTemplate() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"param_v2\":{\"baseInfo\":{\"title\":\"Heavy Snow\",\"content\":\"Red Alert\"},\"param_island\":{\"bigIslandArea\":{\"imageTextInfoLeft\":{\"textInfo\":{\"title\":\"Heavy Snow\"}}}}}}}",
                "·", "·");
        assertEquals("Heavy Snow·Red Alert", value.text);
    }

    @Test
    public void capsExtractedTextLength() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"param_v2\":{\"baseInfo\":{\"title\":\""
                        + repeat('x', InputLimits.MAX_OUTPUT_CHARS + 100) + "\"}}}",
                "·", "·");
        assertEquals(InputLimits.MAX_OUTPUT_CHARS, value.text.length());
    }

    @Test
    public void rejectsPayloadAboveUtf8ByteLimit() {
        String payload = "{\"title\":\""
                + repeat('\u4e2d', InputLimits.MAX_PAYLOAD_UTF8_BYTES / 3 + 1) + "\"}";
        assertNull(IslandPayloadParser.parse(payload, "·", "·"));
    }

    @Test
    public void travelPictureUsesExactBundleKeyWithoutPrefix() {
        // 0.29.2 日志确认 Bundle 有此键，旧实现却返回 null，导致回退应用图标。
        assertEquals("island_pic_small",
                IslandPayloadParser.findPictureReference(TRAVEL_PAYLOAD, false));
        assertEquals("island_pic_small",
                IslandPayloadParser.findPictureReference(TRAVEL_PAYLOAD, true));
        assertNull(IslandPayloadParser.drawableNameFromReference("island_pic_small"));
        assertEquals("weather_icon", IslandPayloadParser.drawableNameFromReference(
                "miui.focus.pic_weather_icon"));
    }

    @Test
    public void exactPictureKeysRejectNonStringsAndExternalLocations() {
        for (String value : new String[]{"123", "true", "null", "{}", "[]",
                "\"\"", "\"https://example.invalid/icon.png\"", "\"../icon\""}) {
            assertNull(IslandPayloadParser.findPictureReference("{\"pic\":" + value + "}", false));
        }
        assertEquals("island_pic", IslandPayloadParser.findTickerPictureReference(
                "{\"tickerPic\":\"island_pic\"}", false));
    }

    @Test
    public void buttonEightIslandIconPayloadKeepsTextAndPictureReference() {
        String payload = "{\"param_v2\":{\"param_island\":{"
                + "\"bigIslandArea\":{\"imageTextInfoLeft\":{\"type\":1,"
                + "\"picInfo\":{\"type\":1,\"pic\":\"miui.focus.pic_island_test\"},"
                + "\"textInfo\":{\"title\":\"岛图标测试\"}}},"
                + "\"smallIslandArea\":{\"picInfo\":{\"type\":1,"
                + "\"pic\":\"miui.focus.pic_island_test\"}}}}}}";
        IslandPayloadParser.ParsedText parsed = IslandPayloadParser.parse(payload, "·", "·");
        assertNotNull(parsed);
        assertEquals("岛图标测试", parsed.text);
        assertEquals("miui.focus.pic_island_test",
                IslandPayloadParser.findPictureReference(payload, false));
    }

    @Test
    public void extractsSmallIslandPictureBeforeOtherPictures() {
        String payload = "{\"param_v2\":{\"baseInfo\":{\"picFunction\":\"miui.focus.pic_app\"},"
                + "\"param_island\":{\"smallIslandArea\":{\"picInfo\":{"
                + "\"pic\":\"miui.focus.pic_weather\"}}}}}}";
        assertEquals("miui.focus.pic_weather",
                IslandPayloadParser.findPictureReference(payload, false));
    }

    @Test
    public void prefersDarkPictureAndFallsBackToLight() {
        String withDark = "{\"param_v2\":{\"param_island\":{\"bigIslandArea\":{"
                + "\"picFunction\":\"miui.focus.pic_pay\","
                + "\"picFunctionDark\":\"miui.focus.pic_pay_dark\"}}}}}";
        assertEquals("miui.focus.pic_pay_dark",
                IslandPayloadParser.findPictureReference(withDark, true));
        assertEquals("miui.focus.pic_pay",
                IslandPayloadParser.findPictureReference(withDark, false));
        assertEquals("miui.focus.pic_pay",
                IslandPayloadParser.findPictureReference(
                        "{\"pic\":\"miui.focus.pic_pay\"}", true));
    }

    @Test
    public void usesDeterministicPictureKeyPriority() {
        String payload = "{\"param_v2\":{\"baseInfo\":{"
                + "\"picFunction\":\"miui.focus.pic_function\","
                + "\"pic\":\"miui.focus.pic_primary\"}}}";
        assertEquals("miui.focus.pic_primary",
                IslandPayloadParser.findPictureReference(payload, false));
    }

    @Test
    public void acceptsAnimationSourceOnlyInsideAnimIconInfo() {
        String payload = "{\"param_v2\":{\"animTextInfo\":{\"animIconInfo\":{"
                + "\"src\":\"miui.focus.pic_animation\"}}}}";
        assertEquals("miui.focus.pic_animation",
                IslandPayloadParser.findPictureReference(payload, false));
        assertNull(IslandPayloadParser.findPictureReference(
                "{\"src\":\"miui.focus.pic_background\"}", false));
    }

    @Test
    public void rejectsNonIconPictureReference() {
        assertNull(IslandPayloadParser.findPictureReference(
                "{\"pic\":\"https://example.invalid/icon.png\"}", false));
        assertNull(IslandPayloadParser.findPictureReference(
                "{\"picCover\":\"miui.focus.pic_album_art\"}", false));
        assertNull(IslandPayloadParser.findPictureReference(
                "{\"picBg\":\"miui.focus.pic_background\"}", false));
    }

    @Test
    public void keepsTickerPictureSeparateFromPayloadPicture() {
        String payload = "{\"param_v2\":{\"tickerPic\":\"miui.focus.pic_lyric\","
                + "\"baseInfo\":{\"pic\":\"miui.focus.pic_album\"}}}";
        assertEquals("miui.focus.pic_lyric",
                IslandPayloadParser.findTickerPictureReference(payload, false));
        assertEquals("miui.focus.pic_album",
                IslandPayloadParser.findPictureReference(payload, false));
    }

    @Test
    public void tickerDarkFallsBackToLightAndDoesNotBecomePayload() {
        String payload = "{\"tickerPic\":\"miui.focus.pic_lyric\"}";
        assertEquals("miui.focus.pic_lyric",
                IslandPayloadParser.findTickerPictureReference(payload, true));
        assertNull(IslandPayloadParser.findPictureReference(payload, false));
    }
    @Test
    public void rejectsInvalidJson() {
        assertNull(IslandPayloadParser.parse("{invalid", "·", "·"));
        assertNull(IslandPayloadParser.findPictureReference("{invalid", false));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
