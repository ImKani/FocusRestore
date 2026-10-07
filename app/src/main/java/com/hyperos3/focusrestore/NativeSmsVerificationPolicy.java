/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.json.JSONObject;

/** 只适配已验证的短信旧协议，不把普通短信或新模板误交给 TemplateRevert。 */
final class NativeSmsVerificationPolicy {
    private NativeSmsVerificationPolicy() { }

    static boolean accepts(String packageName, String payload) {
        if (!"com.android.mms".equals(packageName) || !InputLimits.isPayloadAllowed(payload)) return false;
        try {
            JSONObject root = new JSONObject(payload);
            // 任意 v2 字段都交回原通路，包括错误类型，避免把坏的新协议当作旧协议。
            return !root.has("param_v2") && !root.has("param_voip_v2")
                    && root.opt("protocol") instanceof Number
                    && ((Number) root.opt("protocol")).doubleValue() == 1d
                    && "verifyCode".equals(root.opt("scene"));
        } catch (Exception ignored) {
            return false;
        }
    }
}
