/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.*;

/** 只验证能力发布范围与降级，不替代 ROM 属性写入、小爱分支及黑底屏蔽的实机验收。 */
public class IslandCapabilityPolicyTest {
    private static final String KEY = IslandCapabilityPolicy.DEVICE_CAPABILITY;

    @Test public void localDisableDoesNotPublishUnsupportedForCapableDevice() {
        assertEquals("1", IslandCapabilityPolicy.publishedValue(true, true, KEY, "0", true));
        assertEquals("1", IslandCapabilityPolicy.publishedValue(true, true, KEY, "1", true));
    }

    @Test public void unsupportedDeviceNeverBecomesSupported() {
        assertEquals("0", IslandCapabilityPolicy.publishedValue(true, false, KEY, "0", true));
        assertEquals("0", IslandCapabilityPolicy.publishedValue(true, false, KEY, "1", true));
    }

    @Test public void unknownNativeCapabilityKeepsOriginalPublication() {
        assertEquals("0", IslandCapabilityPolicy.publishedValue(true, null, KEY, "0", true));
        assertEquals("1", IslandCapabilityPolicy.publishedValue(true, null, KEY, "1", true));
    }

    @Test public void disabledSuppressionPreservesRomChoice() {
        assertEquals("0", IslandCapabilityPolicy.publishedValue(false, true, KEY, "0", true));
        assertEquals("1", IslandCapabilityPolicy.publishedValue(false, false, KEY, "1", true));
    }

    @Test public void unrelatedCallersAndPropertiesAreUntouched() {
        assertEquals("0", IslandCapabilityPolicy.publishedValue(true, true, KEY, "0", false));
        assertEquals("0", IslandCapabilityPolicy.publishedValue(true, true, KEY + ".animation", "0", true));
        assertEquals("0", IslandCapabilityPolicy.publishedValue(true, true, null, "0", true));
    }

    @Test public void unfamiliarValuesAndNullAreNotReinterpreted() {
        assertEquals("false", IslandCapabilityPolicy.publishedValue(true, true, KEY, "false", true));
        assertEquals("", IslandCapabilityPolicy.publishedValue(true, true, KEY, "", true));
        assertNull(IslandCapabilityPolicy.publishedValue(true, true, KEY, null, true));
    }

    @Test public void onlyVerifiedStartupAndUserSwitchPublishersMatch() {
        assertTrue(IslandCapabilityPolicy.isPublisher(new StackTraceElement[]{
                frame("android.os.SystemProperties", "set"),
                frame("com.android.systemui.application.impl.SystemUIApplicationImpl", "onCreate")}));
        assertTrue(IslandCapabilityPolicy.isPublisher(new StackTraceElement[]{
                frame("com.miui.systemui.MiuiVendorServices$1", "onUserChanged")}));
    }

    @Test public void SimilarNamesAndOtherLifecycleMethodsDoNotMatch() {
        assertFalse(IslandCapabilityPolicy.isPublisher(new StackTraceElement[0]));
        assertFalse(IslandCapabilityPolicy.isPublisher(new StackTraceElement[]{
                frame("com.android.systemui.application.impl.SystemUIApplicationImpl", "onDestroy"),
                frame("com.miui.systemui.MiuiVendorServices$2", "onUserChanged"),
                frame("com.miui.systemui.MiuiVendorServices$1extra", "onUserChanged")}));
    }

    private static StackTraceElement frame(String owner, String method) {
        return new StackTraceElement(owner, method, "SourceFile", 1);
    }
}
