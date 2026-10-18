/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.Locale;
import org.json.JSONObject;

/** 时钟岛协议的计时快照；不改变通知或注册 ROM 的全局计时器。 */
final class IslandClockTimer {
    /*
     * 接口事实来源：作者提供的 OS3 系统界面组件 17.1.4.71.0，
     * http://192.168.31.21:8787/mcp；NotificationTimeKeeper 的 timerType 常量、
     * shouldBeStarted/updateInfo/updateText。具体版权所有者及私有协议许可未确认。
     * 此处独立实现快照解析、elapsedRealtime 推进和文字格式，不复制 ROM 实现。
     * 时钟 0.40.0 日志中的顶层秒表时间为 0，有效值在 highlightInfo.timerInfo。
     */
    final int type;
    final long when;
    final long current;

    private IslandClockTimer(int type, long when, long current) {
        this.type = type;
        this.when = when;
        this.current = current;
    }

    static IslandClockTimer parse(String packageName, String payload) {
        if (!"com.android.deskclock".equals(packageName)
                || !InputLimits.isPayloadAllowed(payload) || payload.trim().isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(payload);
            String scene = root.optString("scene");
            if (root.optInt("protocol", -1) != 1
                    || !("stopWatch".equals(scene) || "timer".equals(scene))) return null;
            for (String key : new String[]{"param_v2", "param_v3", "param_voip_v2"}) {
                JSONObject container = root.optJSONObject(key);
                IslandClockTimer timer = fromContainer(container);
                if (timer != null) return timer;
            }
            IslandClockTimer timer = fromContainer(root);
            return timer != null ? timer : from(root);
        } catch (Exception invalidPayload) {
            // 不完整或未知协议保持普通文字降级，不能用 0 起点生成巨大的秒表时间。
            return null;
        }
    }

    private static IslandClockTimer fromContainer(JSONObject container) {
        if (container == null) return null;
        for (String key : new String[]{"highlightInfo", "highlightInfoV3", "animTextInfo"}) {
            JSONObject section = container.optJSONObject(key);
            IslandClockTimer timer = from(section == null ? null : section.optJSONObject("timerInfo"));
            if (timer != null) return timer;
        }
        JSONObject island = container.optJSONObject("param_island");
        JSONObject big = island == null ? null : island.optJSONObject("bigIslandArea");
        JSONObject digits = big == null ? null : big.optJSONObject("sameWidthDigitInfo");
        return from(digits == null ? null : digits.optJSONObject("timerInfo"));
    }

    private static IslandClockTimer from(JSONObject info) {
        if (info == null) return null;
        int type = info.optInt("timerType", 0);
        long when = info.optLong("timerWhen", 0L);
        long current = info.optLong("timerSystemCurrent", 0L);
        if (type == 0 || type < -4 || type > 4 || when <= 0L) return null;
        // 暂停必须有快照；运行态按绑定时的墙钟换算，之后仅用单调时钟推进。
        if ((type == 2 || type == -2 || type == 4 || type == -4) && current <= 0L) return null;
        return new IslandClockTimer(type, when, current);
    }

    boolean running() { return type == 1 || type == -1 || type == 3 || type == -3; }

    long durationAt(long wallNow, long elapsedSinceBind) {
        long reference = running() ? wallNow : current;
        long duration = type < 0 ? when - reference : reference - when;
        if (running()) {
            long delta = Math.max(0L, elapsedSinceBind);
            if (type < 0) duration = duration <= delta ? 0L : duration - delta;
            else duration = duration > Long.MAX_VALUE - delta ? Long.MAX_VALUE : duration + delta;
        }
        return Math.max(0L, duration);
    }

    String format(long durationMillis) {
        long seconds = Math.max(0L, durationMillis) / 1000L;
        if (Math.abs(type) >= 3 || seconds < 3600L) {
            return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60L, seconds % 60L);
        }
        return String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600L,
                seconds / 60L % 60L, seconds % 60L);
    }
}
