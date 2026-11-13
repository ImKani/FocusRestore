/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * 源码契约守卫：ROM 的真实窗口合成仍需设备像素验证；这里锁定已核对的调用顺序。
 */
public class NativeBannerMaterialContractTest {
    private String source() throws Exception {
        return new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/hyperos3/focusrestore/NativeBannerMaterial.java")),
                StandardCharsets.UTF_8);
    }

    @Test public void followsRomModalBlurWindowSetupOrder() throws Exception {
        String source = source();
        int pass = source.indexOf("invoke(pass, true)");
        int mode = source.indexOf("invoke(mode, 1)", pass);
        int exception = source.indexOf("invoke(windowException, \"NotificationShade\")", mode);
        int radius = source.indexOf("invoke(radius, value)", exception);
        assertTrue(pass >= 0);
        assertTrue(mode > pass);
        assertTrue(exception > mode);
        assertTrue(radius > exception);
        assertTrue(source.contains("setMiBlurWinExcMethod$delegate"));
    }

    @Test public void invokesMiBlurMethodsOnViewReceiver() throws Exception {
        String source = source();
        assertTrue(source.contains("Object result = method.invoke(target, args)"));
        assertTrue(!source.contains("Object result = method.invoke(null, args)"));
        assertTrue(source.contains("method.invoke(view, args)"));
    }

    @Test public void decorativeLayerDoesNotContributeToWrapContentMeasurement() throws Exception {
        String source = source();
        assertTrue(source.contains("new ViewGroup.LayoutParams(0, 0)"));
        assertTrue(!source.contains("ViewGroup.LayoutParams.MATCH_PARENT"));
        assertTrue(source.contains("blendLayer.layout(0, 0, width, height)"));
        assertTrue(source.contains("int height = target.getHeight()"));
    }

    @Test public void materialIsPreparedBeforeBothHostDrawPaths() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/hyperos3/focusrestore/FocusBannerController.java")),
                StandardCharsets.UTF_8);
        for (String draw : new String[]{"@Override public void draw(Canvas canvas)",
                "@Override protected void dispatchDraw(Canvas canvas)"}) {
            int start = source.indexOf(draw);
            int prepare = source.indexOf("material.beforeDraw()", start);
            int render = source.indexOf(draw.contains("dispatchDraw")
                    ? "super.dispatchDraw(canvas)" : "super.draw(canvas)", start);
            assertTrue(start >= 0 && prepare > start && prepare < render);
        }
    }

    @Test public void glassOutlineIsOptionalAndClearsOnlyItsOwnedFlag() throws Exception {
        String source = source();
        int update = source.indexOf("private void updateGlassOutline(boolean useGlass)");
        int release = source.indexOf("private boolean clearGlassOutline()", update);
        String optionalStage = source.substring(update, release);
        assertTrue(optionalStage.contains("if (!useGlass) { clearGlassOutline(); return; }"));
        int contract = optionalStage.indexOf("compat.getDeclaredMethod(\"setGlassOutlineEnable\", View.class, boolean.class)");
        int apply = optionalStage.indexOf("invoke(glassOutline, 8192, 8192)");
        assertTrue(contract >= 0 && apply > contract);
        assertTrue(optionalStage.contains("keep blur and blend"));
        assertTrue(!optionalStage.contains("failed = true"));
        assertTrue(optionalStage.indexOf("clearGlassOutline();", optionalStage.indexOf("catch (Throwable error)")) >= 0);
        assertTrue(source.contains("if (!useGlass || glassOutlineFailed) clearGlassOutline();"));
        String cleanup = source.substring(release, source.indexOf("private void resolve()", release));
        assertTrue(cleanup.contains("invoke(glassOutline, 0, 8192)"));
        assertTrue(!cleanup.contains("invoke(glassOutline, 0, 0)"));
        assertTrue(source.substring(source.indexOf("void clear()")).contains("boolean success = clearGlassOutline()"));
    }

    @Test public void explicitOptOutClearsBeforeFailedStateOrRomReads() throws Exception {
        String source = source();
        int start = source.indexOf("void refresh()");
        int optOut = source.indexOf("if (!NativeFocusAppearance.useNativeMaterial()) { clear(); return; }", start);
        int failed = source.indexOf("if (failed) return;", start);
        int romRead = source.indexOf("Object current = state.get();", start);
        assertTrue(start >= 0 && optOut > start && failed > optOut && romRead > failed);
    }

    @Test public void keepsMediaRootOutOfNativeMaterialUntilItsSurfaceIsValidated() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/hyperos3/focusrestore/MediaNotificationBannerSource.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("implements FocusBannerSource"));
        assertTrue(source.contains("private final View root"));
        assertTrue(!source.contains("materialTarget()"));
    }
}
