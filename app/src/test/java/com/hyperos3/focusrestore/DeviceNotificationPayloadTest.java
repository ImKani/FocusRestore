/* SPDX-License-Identifier: GPL-3.0-only; Copyright (C) ImKani; FocusRestore: https://github.com/ImKani/FocusRestore */
package com.hyperos3.focusrestore;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceNotificationPayloadTest {
    private final StringBuilder diagnostics = new StringBuilder();
    private DeviceNotificationPayload read(Object model, Map<String, Object> metadata, boolean os4) {
        return DeviceNotificationPayload.read(model, metadata, os4,
                message -> diagnostics.append(message).append('\n'));
    }
    private Map<String, Object> metadata() { return new HashMap<>(); }

    @Test public void directModelPreservesBothSidesAndRightIcon() {
        DeviceNotificationPayload result = read(new Model("充电", "68%"), metadata(), true);
        assertEquals("充电 68%", result.text);
        assertEquals("right_icon", result.iconName);
        assertEquals(2500L, result.visibleMs);
        assertTrue(diagnostics.toString().contains("durationSource=default"));
    }
    @Test public void bundleDurationAndIdentityHavePriorityOnOs4() {
        Map<String, Object> extras = metadata();
        extras.put("duration", 4200L);
        extras.put("notifyId", "charge");
        DeviceNotificationPayload result = read(new Legacy(), extras, true);
        assertEquals(4200L, result.visibleMs);
        assertEquals("charge", result.eventId);
    }
    @Test public void legacyGuideKeepsFirstTextAndTarget() {
        Legacy legacy = new Legacy();
        DeviceNotificationPayload result = read(legacy, metadata(), false);
        assertEquals("静音", result.text);
        assertEquals("right_icon", result.iconName);
        assertEquals(3300L, result.visibleMs);
        assertSame(legacy.target, result.target);
        assertEquals("statusBarGuideModel", result.source);
    }
    @Test public void chargingTextWorksWithoutGuide() {
        DeviceNotificationPayload result = read(new Charge(), metadata(), false);
        assertEquals("正在充电", result.text);
        assertEquals("charge", result.source);
        assertEquals(5000L, result.visibleMs);
    }
    @Test public void repeatedSidesAreNotDuplicated() {
        assertEquals("已开启", read(new Model("已开启", "已开启"), metadata(), true).text);
    }
    @Test public void rightOnlyTextIsNotLost() {
        assertEquals("勿扰", read(new Model("  ", "勿扰"), metadata(), true).text);
    }
    @Test public void invalidDurationUsesModeDefault() {
        for (Object duration : new Object[]{0L, -1L, "5000"}) {
            Map<String, Object> extras = metadata();
            extras.put("duration", duration);
            assertEquals(2500L, read(new Model("静音", null), extras, true).visibleMs);
        }
    }
    @Test public void integerDurationIsSupported() {
        Map<String, Object> extras = metadata();
        extras.put("duration", 6500);
        assertEquals(6500L, read(new Model("静音", null), extras, true).visibleMs);
    }
    @Test public void missingModelHasNoInventedText() {
        DeviceNotificationPayload result = read(null, metadata(), true);
        assertNull(result.text);
        assertNull(result.iconName);
        assertNull(result.target);
    }
    @Test public void getterFailureIsDiagnosedAndDoesNotCrash() {
        assertNull(read(new Broken(), metadata(), true).text);
        assertTrue(diagnostics.toString().contains("model-read failed"));
    }
    @Test public void inheritedPrivateFieldsAreSupported() {
        assertEquals("正在充电", read(new InheritedCharge(), metadata(), false).text);
    }
    @Test public void malformedSideMembersDoNotBecomeText() {
        assertNull(read(new Malformed(), metadata(), true).text);
    }
    @Test public void upstreamIslandJsonWorksWithoutRomHandler() {
        DeviceNotificationPayload result = DeviceNotificationPayload.readJson(
                "{\"left\":{\"textParams\":{\"text\":\"勿扰\"}},\"right\":{\"iconParams\":{\"iconResName\":\"dnd\"}}}",
                metadata(), true, message -> diagnostics.append(message));
        assertEquals("勿扰", result.text);
        assertEquals("dnd", result.iconName);
    }
    @Test public void legacyParamJsonAndBundleTargetRemainSupported() {
        Map<String, Object> extras = metadata();
        Object intent = new Object();
        extras.put("target", intent);
        DeviceNotificationPayload result = DeviceNotificationPayload.readJson(
                "{\"statusBarGuideModel\":{\"left\":{\"textParams\":{\"text\":\"静音\"}}}}",
                extras, true, message -> diagnostics.append(message));
        assertEquals("静音", result.text);
        assertSame(intent, result.target);
    }
    @Test public void malformedJsonIsDiagnosed() {
        assertNull(DeviceNotificationPayload.readJson("{bad", metadata(), true,
                message -> diagnostics.append(message)));
        assertTrue(diagnostics.toString().contains("json failed"));
    }
    @Test public void emptyAndOversizedJsonAreRejected() {
        assertNull(DeviceNotificationPayload.readJson("", metadata(), true,
                message -> diagnostics.append(message)));
        StringBuilder oversized = new StringBuilder();
        for (int index = 0; index <= InputLimits.MAX_PAYLOAD_CHARS; index++) oversized.append(' ');
        assertNull(DeviceNotificationPayload.readJson(oversized.toString(), metadata(), true,
                message -> diagnostics.append(message)));
    }
    @Test public void directOs3GuidePreservesCenterOnlyContent() {
        DeviceNotificationPayload result = DeviceNotificationPayload.readJson(
                "{\"center\":{\"textParams\":{\"text\":\"静音\"},\"iconParams\":{\"iconResName\":\"mute\"}}}",
                metadata(), false, message -> diagnostics.append(message));
        assertEquals("静音", result.text);
        assertEquals("mute", result.iconName);
    }
    @Test public void bundleChargeTextWorksWithoutModel() {
        Map<String, Object> extras = metadata();
        extras.put("charge", "正在充电");
        assertEquals("正在充电", read(null, extras, false).text);
    }
    public static class Model {
        private final Part left, right;
        Model(String left, String right) {
            this.left = new Part(left, "left_icon"); this.right = new Part(right, "right_icon");
        }
        public Part getLeft() { return left; }
        public Part getRight() { return right; }
    }
    public static class Part {
        private final Text textParams;
        private final Picture iconParams;
        Part(String text, String icon) { textParams = new Text(text); iconParams = new Picture(icon); }
        public Text getTextParams() { return textParams; }
        public Picture getIconParams() { return iconParams; }
    }
    public static class Text {
        private final String text;
        Text(String text) { this.text = text; }
        public String getText() { return text; }
    }
    public static class Picture {
        private final String iconResName;
        Picture(String name) { iconResName = name; }
        public String getIconResName() { return iconResName; }
    }
    public static class Legacy {
        private final Model statusBarGuideModel = new Model("静音", "右侧旧文案");
        private final long duration = 3300L;
        private final Object target = new Object();
    }
    public static class Charge { private final String charge = "正在充电"; }
    public static class InheritedCharge extends Charge {}
    public static class Broken { public Part getLeft() { throw new IllegalStateException("bad model"); } }
    public static class Malformed { public Object getLeft() { return 123; } }
}
