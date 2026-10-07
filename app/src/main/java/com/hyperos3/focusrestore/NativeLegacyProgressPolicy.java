/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.json.JSONObject;

/** 只接受 ROM 旧协议进度场景，不把新协议或其他旧模板误交给进度模板。 */
final class NativeLegacyProgressPolicy {
    private NativeLegacyProgressPolicy() { }

    static boolean accepts(String payload) {
        if (!InputLimits.isPayloadAllowed(payload)) return false;
        try {
            JSONObject root = new JSONObject(payload);
            Object protocol = root.opt("protocol");
            Object scene = root.opt("scene");
            // 来源：OS3 17.1.4.71.0 / OS4 18.2.2.2.0 插件 FocusTemplateKt.initTemplateMap。
            // ROM 私有实现版权及许可未确认；仅依据接口映射独立实现筛选，不复制 ROM 代码。
            return !root.has("param_v2") && !root.has("param_voip_v2")
                    && protocol instanceof Number && ((Number) protocol).doubleValue() == 1d
                    && ("foodDelivery".equals(scene) || "templateRevertProgressScene".equals(scene));
        } catch (Exception ignored) {
            return false;
        }
    }
}
