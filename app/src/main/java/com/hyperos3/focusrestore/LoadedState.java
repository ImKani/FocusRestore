/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 展示加载事实，安装就绪不等于ROM功能或画面验收。 */
final class LoadedState {
    static String describe(boolean alive,int loadedCode,int expectedCode,int loadedMode,int savedMode,
                           boolean ready,long applied,long saved) {
        if (!alive) return "当前加载状态未知：尚未收到本次系统界面进程的报告。";
        if (loadedCode != expectedCode) return "等待系统界面重载：当前进程仍加载旧版本。";
        if (loadedMode != savedMode) return "设置已保存，等待系统界面重载以切换模式。";
        if (!ready) return "当前版本已加载，核心 Hook 未全部安装，请查看日志。";
        if (applied < saved) return "当前版本已加载，配置已保存，等待系统界面读取。";
        return "当前版本与模式已加载，配置已读取；实际显示效果待设备确认。";
    }
}
