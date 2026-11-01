/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focustestnotifier;

/**
 * 基于设备日志已观测的Focus/岛协议结构独立构造测试载荷。
 * 商家、分店、车次、站名、配送时间/距离/进度均使用明确的虚构测试值，
 * 不保留真实订单URL/ID、序列号、用户或地址标识。字段层级、图标引用、
 * 彩色font标记和重复aodTitle结构继续用于解析与横幅回归。
 * 原始日志证据留在工作区，不作为本工具发布数据。
 */
final class FocusPayloads {
    /** 出行协议结构来自已观测通知；可见车次等内容均为虚构测试值。 */
    static final String TRAVEL_PARAM_CUSTOM =
            "{\"timeout\":470,\"enableFloat\":true,\"updatable\":true,\"reopen\":\"reopen\","
                    + "\"business\":\"train\",\"protocol\":1,\"scene\":\"personal-assistant-travel\","
                    + "\"aodTitle\":\"检票口 检票口\",\"aodPic\":\"miui.focus.travel_aod_pic\","
                    + "\"param_island\":{\"islandProperty\":1,\"highlightColor\":\"#3482FF\","
                    + "\"islandTimeout\":28200,\"bigIslandArea\":{"
                    + "\"imageTextInfoLeft\":{\"type\":1,\"picInfo\":{\"type\":1,\"pic\":\"island_pic\"},"
                    + "\"textInfo\":{\"title\":\"测试车次\",\"showHighlightColor\":true}},"
                    + "\"textInfo\":{\"title\":\"检票口\",\"showHighlightColor\":true}},"
                    + "\"smallIslandArea\":{\"picInfo\":{\"type\":1,\"pic\":\"island_pic_small\"}}}}";

    /**
     * Same payload without {@code aodTitle}. Full mode should yield exactly {@code 测试车次·检票口};
     * this isolates the parser from the always-on-display label.
     */
    static final String TRAVEL_WITHOUT_AOD_TITLE =
            TRAVEL_PARAM_CUSTOM.replace(",\"aodTitle\":\"检票口 检票口\"", "");

    /** {@code miui.focus.param} of the SystemUI HyperIsland notification (focusType=PARAMS). */
    static final String ISLAND_PARAM =
            "{\"param_v2\":{\"protocol\":3,\"business\":\"hyper_island_dispatch\",\"updatable\":true,"
                    + "\"ticker\":\"欢迎使用\",\"enableFloat\":false,\"isShowNotification\":false,"
                    + "\"islandFirstFloat\":false,\"param_island\":{\"islandProperty\":1,"
                    + "\"islandPriority\":2,\"islandTimeout\":5,\"islandOrder\":false,"
                    + "\"dismissIsland\":false,\"maxSize\":false,\"needCloseAnimation\":true,"
                    + "\"highlightColor\":\"#E040FB\",\"bigIslandArea\":{"
                    + "\"imageTextInfoLeft\":{\"type\":1,\"picInfo\":{\"type\":1,"
                    + "\"pic\":\"miui.focus.pic_key_island_icon\",\"loop\":false,\"autoplay\":false,"
                    + "\"number\":0},\"textInfo\":{\"title\":\"欢迎使用\",\"showHighlightColor\":false,"
                    + "\"narrowFont\":false}},\"imageTextInfoRight\":{\"type\":2,"
                    + "\"textInfo\":{\"title\":\"HyperIsland\",\"showHighlightColor\":false,"
                    + "\"narrowFont\":false}}},\"smallIslandArea\":{\"picInfo\":{\"type\":1,"
                    + "\"pic\":\"miui.focus.pic_key_island_icon\",\"loop\":false,\"autoplay\":false,"
                    + "\"number\":0}},\"outEffectSrc\":\"outer_glow\"},\"iconTextInfo\":{"
                    + "\"animIconInfo\":{\"type\":0,\"src\":\"miui.focus.pic_key_focus_icon\","
                    + "\"loop\":true,\"autoplay\":true},\"title\":\"欢迎使用\",\"content\":\"HyperIsland\"},"
                    + "\"aodTitle\":\"HyperIsland\",\"aodPic\":\"miui.focus.pic_aod\"},"
                    + "\"isShowNotification\":true}";

    /**
     * Left/right island areas. Full mode joins them with the general separator while compact mode
     * uses the side separator, so this single payload distinguishes 焦点内容模式.
     */
    static final String ISLAND_SIDES =
            "{\"param_v2\":{\"param_island\":{\"bigIslandArea\":{"
                    + "\"imageTextInfoLeft\":{\"textInfo\":{\"title\":\"测试起点站\"}},"
                    + "\"imageTextInfoRight\":{\"textInfo\":{\"title\":\"测试终点站\"}}}}}}";

    /** Island content that only exists in the compact/small area. */
    static final String ISLAND_SMALL_ONLY =
            "{\"protocol\":1,\"scene\":\"small-only\",\"param_island\":{"
                    + "\"smallIslandArea\":{\"title\":\"进站中\"}}}";

    /**
     * Island payload whose only job is to reference {@code miui.focus.pic_island_test} in the
     * small/big island areas. Combined with {@code FocusTestNotifier#pictures()} publishing that
     * exact key under {@code miui.focus.pics}, this exercises the island-icon main contract
     * ({@code island:<ref>}) instead of the app-icon fallback.
     */
    static final String ISLAND_ICON_PARAM =
            "{\"param_v2\":{\"param_island\":{"
                    + "\"bigIslandArea\":{\"imageTextInfoLeft\":{\"type\":1,"
                    + "\"picInfo\":{\"type\":1,\"pic\":\"miui.focus.pic_island_test\"},"
                    + "\"textInfo\":{\"title\":\"岛图标测试\",\"showHighlightColor\":false}}},"
                    + "\"smallIslandArea\":{\"picInfo\":{\"type\":1,"
                    + "\"pic\":\"miui.focus.pic_island_test\"}}}}}";

    /**
     * 0.40.2 日志中 {@code com.taobao.taobao} 外卖通知的 {@code miui.focus.param} 结构
     * （{@code log/focus-restore-os3-0.40.2.log}）；本样本使用虚构配送时间
     * {@code <font color='#0FAD50'>12:00</font>}，用于验证彩色字体渲染。
     *
     * <p>为避免把真实订单地址写入本地测试工具，去掉了 {@code url}/{@code orderId}/{@code sequence}；
     * 商家及分店改为虚构名称，时间/距离/进度为合成值；保留协议层级与font标记。
     */
    static final String TAKEOUT_COLORED_FONT =
            "{\"progressCount\":2,\"enableFloat\":false,\"title\":\"预计12:00送达\","
                    + "\"content\":\"骑士已接单\",\"timeout\":60,\"scene\":\"foodDelivery\","
                    + "\"desc1\":\"骑士已接单\",\"desc2\":\"距测试商家100m，预计2分钟到达 \","
                    + "\"protocol\":1,\"progress\":50,\"updatable\":true,"
                    + "\"param_v2\":{\"baseInfo\":{\"showDivider\":true,"
                    + "\"title\":\"预计<font color='#0FAD50'>12:00</font>送达\",\"type\":2,"
                    + "\"colorSubTitle\":\"#FF6200\",\"content\":\"骑士已接单\","
                    + "\"subContent\":\"测试餐厅·示例分店\"},"
                    + "\"param_island\":{\"highlightColor\":\"#FF6200\",\"islandProperty\":1,"
                    + "\"bigIslandArea\":{\"imageTextInfoLeft\":{\"textInfo\":{"
                    + "\"showHighlightColor\":false,\"title\":\"骑士接单\"},"
                    + "\"picInfo\":{\"pic\":\"miui.focus.pic_app_icon\",\"type\":1},\"type\":1},"
                    + "\"textInfo\":{\"showHighlightColor\":true,\"title\":\"12:00\","
                    + "\"content\":\"送达\"}},"
                    + "\"smallIslandArea\":{\"picInfo\":{\"pic\":\"miui.focus.pic_app_icon\","
                    + "\"type\":1}}},"
                    + "\"aodPic\":\"miui.focus.pic_app_icon\",\"business\":\"food_delivery\","
                    + "\"picInfo\":{\"pic\":\"miui.focus.pic_app_icon\",\"type\":1},"
                    + "\"enableFloat\":false,\"protocol\":1,\"filterWhenNoPermission\":true,"
                    + "\"reopen\":\"close\",\"aodTitle\":\"12:00送达\",\"updatable\":true,"
                    + "\"progressInfo\":{\"picEnd\":\"miui.focus.pic_end\","
                    + "\"picEndUnselected\":\"miui.focus.pic_end_unselected\","
                    + "\"colorProgressEnd\":\"#FF6200\",\"picForward\":\"miui.focus.pic_forward\","
                    + "\"progress\":50,\"colorProgress\":\"#FF6200\","
                    + "\"picMiddleUnselected\":\"miui.focus.pic_middel_unselected\","
                    + "\"picMiddle\":\"miui.focus.pic_middle\"},\"islandFirstFloat\":false},"
                    + "\"colorDesc\":\"#FF48D0FF\",\"colorDescDark\":\"#FF0073DD\","
                    + "\"islandFirstFloat\":false}";

    private FocusPayloads() {
    }
}
