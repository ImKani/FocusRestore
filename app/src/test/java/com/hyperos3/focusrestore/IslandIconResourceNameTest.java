/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 0.29.0：岛图标兜底把载荷引用名当资源名去应用包里查，这里固定"怎么从引用名得到资源名"。
 *
 * <p>背景：OS3 真机上岛图标通路此前从未成功过（所有日志的 {@code showIslandIcon} 都是 false，
 * 转换结果全是 {@code applicationIcon}）。引用名按契约形如 {@code miui.focus.pic_xxx}，
 * 第三方应用一般不会用这个名字定义 drawable，所以这条兜底很可能落空——正因如此，
 * 推导规则必须是可预测、可测试的，不能靠猜。
 */
public class IslandIconResourceNameTest {
    @Test
    public void stripsTheContractPrefix() {
        assertEquals("weather", IslandPayloadParser.drawableNameFromReference("miui.focus.pic_weather"));
        assertEquals("pay", IslandPayloadParser.drawableNameFromReference("miui.focus.pic_pay"));
        assertEquals("lyric", IslandPayloadParser.drawableNameFromReference("miui.focus.pic_lyric"));
    }

    @Test
    public void onlyTheContractPrefixIsAccepted() {
        // 契约形式固定为 miui.focus.pic_<name>。不带下划线的写法有歧义
        //（"miui.focus.pics" → "s"、"miui.focus.picture_weather" → "ture_weather"），
        // 一律不猜，宁可干净地退回 smallIcon / 应用图标兜底。
        assertNull(IslandPayloadParser.drawableNameFromReference("miui.focus.picweather"));
        assertNull(IslandPayloadParser.drawableNameFromReference("miui.focus.picture_weather"));
        assertNull(IslandPayloadParser.drawableNameFromReference("miui.focus.pics"));
        assertNull(IslandPayloadParser.drawableNameFromReference("miui.focus.pic"));
    }

    @Test
    public void surroundingWhitespaceIsTolerated() {
        assertEquals("weather", IslandPayloadParser.drawableNameFromReference(" miui.focus.pic_weather "));
    }

    @Test
    public void emptyNameAndForeignReferencesAreRejected() {
        assertNull(IslandPayloadParser.drawableNameFromReference("miui.focus.pic_"));
        assertNull(IslandPayloadParser.drawableNameFromReference(null));
        assertNull(IslandPayloadParser.drawableNameFromReference(""));
        assertNull(IslandPayloadParser.drawableNameFromReference("   "));
        // 不是这个前缀就不猜：宁可走通知 smallIcon / 应用图标兜底，也不要按错误的资源名乱查。
        assertNull(IslandPayloadParser.drawableNameFromReference("island_pic_small"));
        assertNull(IslandPayloadParser.drawableNameFromReference("ic_weather"));
    }
}