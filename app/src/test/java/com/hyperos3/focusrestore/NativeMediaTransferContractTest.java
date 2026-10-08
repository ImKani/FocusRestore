/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/** 源码契约守卫：Android View/ROM 私有 CTA 的实际读取仍需真机验证。 */
public class NativeMediaTransferContractTest {
    private String source(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/com/hyperos3/focusrestore", name)),
                StandardCharsets.UTF_8);
    }

    @Test public void seamlessButtonUsesHostContextRatherThanNotificationPackage() throws Exception {
        String source = source("MediaNotificationBannerSource.java");
        assertTrue(source.contains("ImageView seamlessIcon = new ImageView(systemUiContext)"));
        assertFalse(source.contains("ImageView seamlessIcon = new ImageView(packageContext)"));
    }

    @Test public void applicationContextIsValidatedBeforeNativeListenerDispatch() throws Exception {
        String source = source("NativeMediaTransferEntry.java");
        int validation = source.indexOf("applicationContext == null");
        int dispatch = source.indexOf("((View.OnClickListener) listener).onClick(anchor)");
        assertTrue(validation >= 0 && dispatch > validation);
        assertTrue(source.contains("anchorContext.getApplicationContext()"));
        assertTrue(source.contains("\"com.android.systemui\".equals(applicationContext.getPackageName())"));
        String guard = source.substring(validation, source.indexOf("Object lazy", validation));
        assertTrue(guard.contains("return false;"));
    }

    @Test public void adapterDoesNotWriteConsentOrReplaceNativeCtaDecision() throws Exception {
        String source = source("NativeMediaTransferEntry.java");
        assertFalse(source.contains("Settings.Secure.put"));
        assertFalse(source.contains("settings_key_interconnection_privacy_state"));
        assertFalse(source.contains("setResult(Boolean.TRUE)"));
        assertFalse(source.contains("showCtaPage("));
        assertTrue(source.contains("((View.OnClickListener) listener).onClick(anchor)"));
    }
}
