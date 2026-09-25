package com.hyperos3.focusrestore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers the payload container keys and the verbose structural diagnostics.
 *
 * <p>Regression source: the ROM declares {@code param_v2}, {@code param_v3} and
 * {@code param_voip_v2} in {@code miui.systemui.notification.focus.Const$Param}, but this parser
 * only read {@code param_v2}. A payload that shipped its content solely under {@code param_v3}
 * therefore fell back to the root object and silently lost every section.
 */
public class IslandPayloadDiagnosticsTest {
    @Test
    public void readsParamV3ContainerWhenParamV2IsAbsent() {
        String payload = "{\"param_v3\":{\"baseInfo\":{\"title\":\"V3 title\",\"content\":\"V3 body\"}}}";
        IslandPayloadParser.ParsedText parsed = IslandPayloadParser.parse(payload, " ", " ");
        assertNotNull(parsed);
        assertEquals("V3 title V3 body", parsed.text);
        assertEquals("baseInfo", parsed.source);
    }

    @Test
    public void paramV2StillWinsOverParamV3() {
        String payload = "{\"param_v2\":{\"baseInfo\":{\"title\":\"two\"}},"
                + "\"param_v3\":{\"baseInfo\":{\"title\":\"three\"}}}";
        IslandPayloadParser.ParsedText parsed = IslandPayloadParser.parse(payload, " ", " ");
        assertNotNull(parsed);
        assertEquals("two", parsed.text);
    }

    @Test
    public void readsParamVoipV2Container() {
        String payload = "{\"param_voip_v2\":{\"chatInfo\":{\"title\":\"call\",\"content\":\"mom\"}}}";
        IslandPayloadParser.ParsedText parsed = IslandPayloadParser.parse(payload, " ", " ");
        assertNotNull(parsed);
        assertEquals("call mom", parsed.text);
        assertEquals("chatInfo", parsed.source);
    }

    @Test
    public void islandContentIsReadThroughTheParamV3Container() {
        String payload = "{\"param_v3\":{\"param_island\":{\"bigIslandArea\":{"
                + "\"textInfo\":{\"title\":\"Gate\"},"
                + "\"imageTextInfoLeft\":{\"textInfo\":{\"content\":\"D8396\"}}}}}}";
        IslandPayloadParser.ParsedText parsed = IslandPayloadParser.parse(payload, " ", " ");
        assertNotNull(parsed);
        assertEquals("param_island", parsed.source);
        assertTrue(parsed.text, parsed.text.contains("Gate"));
        assertTrue(parsed.text, parsed.text.contains("D8396"));
    }

    @Test
    public void compactModeAlsoReadsTheParamV3Container() {
        String payload = "{\"param_v3\":{\"param_island\":{\"smallIslandArea\":{\"title\":\"tiny\"}}}}";
        IslandPayloadParser.ParsedText parsed = IslandPayloadParser.parseCompact(payload, " ");
        assertNotNull(parsed);
        assertEquals("tiny", parsed.text);
    }

    @Test
    public void describesStructureForDiagnostics() {
        String payload = "{\"protocol\":1,\"scene\":\"verifyCode\",\"param_v2\":{"
                + "\"baseInfo\":{\"title\":\"t\"},\"param_island\":{\"bigIslandArea\":{}}}}";
        String described = IslandPayloadParser.describeStructure(payload);
        assertTrue(described, described.contains("param_v2=yes"));
        assertTrue(described, described.contains("container=nested"));
        assertTrue(described, described.contains("protocol=1"));
        assertTrue(described, described.contains("scene=verifyCode"));
        assertTrue(described, described.contains("baseInfo=yes"));
        assertTrue(described, described.contains("param_island=yes"));
        assertTrue(described, described.contains("island=big"));
    }

    @Test
    public void describesStructureOfAFlatPayloadAsRootContainer() {
        String described = IslandPayloadParser.describeStructure(
                "{\"baseInfo\":{\"title\":\"flat\"}}");
        assertTrue(described, described.contains("container=root"));
        assertTrue(described, described.contains("baseInfo=yes"));
        assertTrue(described, described.contains("island=none"));
    }

    @Test
    public void describesUnparsableAndEmptyPayloadsWithoutThrowing() {
        assertTrue(IslandPayloadParser.describeStructure(null).contains("payload=null"));
        assertTrue(IslandPayloadParser.describeStructure("   ").contains("payload=empty"));
        assertTrue(IslandPayloadParser.describeStructure("{not json").contains("unparsable"));
    }
}
