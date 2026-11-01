/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focustestnotifier;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 合成样本仅沿用已核查的字段结构，不携带真实订单、应用内容或设备身份。 */
final class NotificationTestCases {
    static final int TICKER = 0;
    static final int ISLAND = 1;
    static final int SMALL_ONLY = 2;
    static final class Spec {
        final int id;
        final String label;
        final String hint;
        final String left;
        final String right;
        final boolean plain;
        final boolean nativeFocus;
        final int icons;

        Spec(int id, String label, String hint, String left, String right,
             boolean plain, boolean nativeFocus, int icons) {
            this.id = id; this.label = label; this.hint = hint;
            this.left = left; this.right = right;
            this.plain = plain; this.nativeFocus = nativeFocus; this.icons = icons;
        }

        @Override public String toString() { return label; }

        String payload() {
            if (plain || nativeFocus) return null;
            try {
                JSONObject leftInfo = new JSONObject().put("textInfo", new JSONObject().put("title", left));
                JSONObject big = new JSONObject().put("imageTextInfoLeft", leftInfo);
                JSONObject island = new JSONObject().put("islandProperty", 1)
                        .put("bigIslandArea", big).put("smallIslandArea", new JSONObject());
                if (!right.isEmpty()) big.put("imageTextInfoRight",
                        new JSONObject().put("textInfo", new JSONObject().put("title", right)));
                if (icons == ISLAND) {
                    JSONObject picture = new JSONObject().put("type", 1).put("pic", "miui.focus.pic_test_island");
                    leftInfo.put("type", 1).put("picInfo", picture);
                    island.put("smallIslandArea", new JSONObject().put("picInfo", picture));
                }
                // 小米指南（2026-01-29）区分正文与岛摘要；插件18.3.2.22.0的
                // TemplateFactoryV3.chooseModule(area_a)只从正文模块选A区，不能用
                // param_island代替。缺baseInfo时checkAreaA会写show_notification=false。
                // 证据：Knowledge/sources/decompiled/notification-tests-20261101。
                JSONObject base = new JSONObject().put("type", 2).put("title", left);
                if (!right.isEmpty()) base.put("content", right);
                return new JSONObject().put("param_v2", new JSONObject().put("protocol", 1)
                        .put("business", "focusrestore_test").put("baseInfo", base)
                        .put("isShowNotification", true).put("islandFirstFloat", false)
                        .put("aodTitle", label).put("reopen", "reopen")
                        .put("updatable", true).put("enableFloat", false)
                        .put("param_island", island)).toString();
            } catch (JSONException failure) {
                throw new IllegalStateException("Cannot construct notification test id=" + id, failure);
            }
        }
    }

    static final List<Spec> ALL = Collections.unmodifiableList(Arrays.asList(
            new Spec(1, "普通通知（对照）", "只发送普通通知，应保留在通知栏，不主动转换成焦点。",
                    "普通通知对照", "", true, false, SMALL_ONLY),
            new Spec(2, "原生焦点布局", "使用自带焦点布局，检查原生显示；一般不经过超级岛文字转换。",
                    "原生焦点测试", "", false, true, TICKER),
            new Spec(3, "超级岛 · 短文字", "检查图标、间距和短文字宽度；需开启超级岛内容转换。",
                    "短文字测试", "", false, false, TICKER),
            new Spec(4, "超级岛 · 长文字滚动", "检查限宽、滚动延迟与往返效果；实际可见范围以状态栏为准。",
                    "这是一条用于检查焦点通知最大宽度、文字裁切和滚动效果的长文字，请回到桌面观察完整滚动过程。",
                    "", false, false, TICKER),
            new Spec(5, "超级岛 · 左右分区", "左侧为“起点”，右侧为“终点”；用于对照内容模式和两种连接符。",
                    "起点", "终点", false, false, TICKER),
            new Spec(6, "超级岛 · 彩色文字", "开启彩色字体时“绿色文字”按绿色渲染，关闭后保留原始标记。",
                    "普通文字 <font color='#0FAD50'>绿色文字</font>", "", false, false, TICKER),
            new Spec(7, "图标来源 · 优先级", "同时提供三角焦点图标、圆形通知小图标及本应用图标，按当前优先级选择一个。",
                    "图标优先级测试", "", false, false, TICKER),
            new Spec(8, "图标来源 · 超级岛", "提供菱形岛图标，不提供焦点专用图标；开启岛图标优先时应选择菱形。",
                    "超级岛图标测试", "", false, false, ISLAND),
            new Spec(9, "图标来源 · 缺少焦点图标", "只保留圆形通知小图标和本应用图标，观察缺少焦点图标时的回退。",
                    "图标回退测试", "", false, false, SMALL_ONLY)
    ));

    static Spec at(int position) { return ALL.get(Math.max(0, Math.min(ALL.size() - 1, position))); }
    private NotificationTestCases() { }
}
