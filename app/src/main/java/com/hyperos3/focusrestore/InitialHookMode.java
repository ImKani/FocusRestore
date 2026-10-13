/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 仅无用户配置的首次初始化使用，不参与 SystemUI Hook 的运行时模式选择。 */
final class InitialHookMode {
    private static final String TAG = "HyperOS3FocusRestore";
    private static final Pattern VERSION = Pattern.compile(
            "^(?:HyperOS|OS)?\\s*([34])(?:\\.\\d+)*$", Pattern.CASE_INSENSITIVE);

    private InitialHookMode() { }

    static int detect() {
        // 属性名事实来源：HyperIsland（1812z，MIT），HyperOsVersionUtil.kt；
        // https://github.com/1812z/HyperIsland 。参考快照版本未确认，仅独立读取属性，未复制实现。
        int mode = parse(readProperty("ro.mi.os.version.name"));
        if (mode == 0) mode = parse(readProperty("ro.mi.os.version.code"));
        if (mode == 0) {
            Log.w(TAG, "initial HyperOS version unavailable or unsupported; keeping OS3 default");
            return FocusRestoreSettings.DEFAULT_HOOK_MODE;
        }
        Log.i(TAG, "initial HyperOS version detected: OS" + mode);
        return mode;
    }

    static int parse(String property) {
        if (property == null) return 0;
        Matcher match = VERSION.matcher(property.trim());
        return match.matches() ? Integer.parseInt(match.group(1)) : 0;
    }

    private static String readProperty(String name) {
        Process process = null;
        try {
            // 普通设置进程不依赖被限制的隐藏 SystemProperties API；只读两项版本属性。
            process = new ProcessBuilder("/system/bin/getprop", name).start();
            if (!process.waitFor(500L, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "initial HyperOS property read timed out: " + name);
                return "";
            }
            if (process.exitValue() != 0) {
                Log.w(TAG, "initial HyperOS property read failed: " + name + " exit=" + process.exitValue());
                return "";
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String property = reader.readLine();
                return property == null ? "" : property;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "initial HyperOS property read interrupted: " + name, interrupted);
            return "";
        } catch (Exception failure) {
            Log.w(TAG, "initial HyperOS property unavailable: " + name, failure);
            return "";
        } finally {
            if (process != null) process.destroy();
        }
    }
}
