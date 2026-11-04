/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;
import org.junit.Test;
import static org.junit.Assert.*;
public class LoadedStateTest {
    @Test public void distinguishesUnknownOldBinaryModeAndPendingConfiguration() {
        assertTrue(LoadedState.describe(false,320,321,4,4,true,5,5).contains("未知"));
        assertTrue(LoadedState.describe(true,320,321,4,4,true,5,5).contains("旧版本"));
        assertTrue(LoadedState.describe(true,321,321,3,4,true,5,5).contains("切换模式"));
        assertTrue(LoadedState.describe(true,321,321,4,4,false,5,5).contains("未全部安装"));
        assertTrue(LoadedState.describe(true,321,321,4,4,true,4,5).contains("等待系统界面读取"));
        assertTrue(LoadedState.describe(true,321,321,4,4,true,5,5).contains("待设备确认"));
    }
}
