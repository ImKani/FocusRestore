/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focustestnotifier;

/**
 * Focus/island payloads captured from the device with
 * {@code adb shell dumpsys notification --noredact} or the module's bounded payload log, plus small
 * derived variants.
 *
 * <p>Keeping the observed strings here is the point: the module's parser and banner behaviour were
 * debugged against exactly these values, so a test notification built from them reproduces field
 * behaviour (including the duplicated {@code aodTitle}) instead of an idealised approximation.
 * The takeout payload is de-identified by removing its real order URL/ID; its visible text and
 * island structure are unchanged.
 */
final class FocusPayloads {
    /** {@code miui.focus.param.custom} of 0|com.miui.personalassistant|0|火车_sdk#...|10138. */
    static final String TRAVEL_PARAM_CUSTOM =
            "{\"timeout\":470,\"enableFloat\":true,\"updatable\":true,\"reopen\":\"reopen\","
                    + "\"business\":\"train\",\"protocol\":1,\"scene\":\"personal-assistant-travel\","
                    + "\"aodTitle\":\"检票口 检票口\",\"aodPic\":\"miui.focus.travel_aod_pic\","
                    + "\"param_island\":{\"islandProperty\":1,\"highlightColor\":\"#3482FF\","
                    + "\"islandTimeout\":28200,\"bigIslandArea\":{"
                    + "\"imageTextInfoLeft\":{\"type\":1,\"picInfo\":{\"type\":1,\"pic\":\"island_pic\"},"
                    + "\"textInfo\":{\"title\":\"D8396\",\"showHighlightColor\":true}},"
                    + "\"textInfo\":{\"title\":\"检票口\",\"showHighlightColor\":true}},"
                    + "\"smallIslandArea\":{\"picInfo\":{\"type\":1,\"pic\":\"island_pic_small\"}}}}";

    /**
     * Same payload without {@code aodTitle}. Full mode should yield exactly {@code D8396·检票口};
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
                    + "\"imageTextInfoLeft\":{\"textInfo\":{\"title\":\"北京南\"}},"
                    + "\"imageTextInfoRight\":{\"textInfo\":{\"title\":\"上海虹桥\"}}}}}}";

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
     * （{@code log/focus-restore-os3-0.40.2.log}）：标题含
     * {@code <font color='#0FAD50'>18:26</font>}，用于验证彩色字体渲染。
     *
     * <p>为避免把真实订单地址写入本地测试工具，去掉了 {@code url}/{@code orderId}/{@code sequence}；
     * 标题、进度、岛区域和 aodTitle 保持日志观测值。
     */
    static final String TAKEOUT_COLORED_FONT =
            "{\"progressCount\":2,\"enableFloat\":false,\"title\":\"预计18:26送达\","
                    + "\"content\":\"骑士已接单\",\"timeout\":60,\"scene\":\"foodDelivery\","
                    + "\"desc1\":\"骑士已接单\",\"desc2\":\"距商家228m，预计4分钟到达 \","
                    + "\"protocol\":1,\"progress\":35,\"updatable\":true,"
                    + "\"param_v2\":{\"baseInfo\":{\"showDivider\":true,"
                    + "\"title\":\"预计<font color='#0FAD50'>18:26</font>送达\",\"type\":2,"
                    + "\"colorSubTitle\":\"#FF6200\",\"content\":\"骑士已接单\","
                    + "\"subContent\":\"干饭人·小炒快餐(兴业店)\"},"
                    + "\"param_island\":{\"highlightColor\":\"#FF6200\",\"islandProperty\":1,"
                    + "\"bigIslandArea\":{\"imageTextInfoLeft\":{\"textInfo\":{"
                    + "\"showHighlightColor\":false,\"title\":\"骑士接单\"},"
                    + "\"picInfo\":{\"pic\":\"miui.focus.pic_app_icon\",\"type\":1},\"type\":1},"
                    + "\"textInfo\":{\"showHighlightColor\":true,\"title\":\"18:26\","
                    + "\"content\":\"送达\"}},"
                    + "\"smallIslandArea\":{\"picInfo\":{\"pic\":\"miui.focus.pic_app_icon\","
                    + "\"type\":1}}},"
                    + "\"aodPic\":\"miui.focus.pic_app_icon\",\"business\":\"food_delivery\","
                    + "\"picInfo\":{\"pic\":\"miui.focus.pic_app_icon\",\"type\":1},"
                    + "\"enableFloat\":false,\"protocol\":1,\"filterWhenNoPermission\":true,"
                    + "\"reopen\":\"close\",\"aodTitle\":\"18:26送达\",\"updatable\":true,"
                    + "\"progressInfo\":{\"picEnd\":\"miui.focus.pic_end\","
                    + "\"picEndUnselected\":\"miui.focus.pic_end_unselected\","
                    + "\"colorProgressEnd\":\"#FF6200\",\"picForward\":\"miui.focus.pic_forward\","
                    + "\"progress\":35,\"colorProgress\":\"#FF6200\","
                    + "\"picMiddleUnselected\":\"miui.focus.pic_middel_unselected\","
                    + "\"picMiddle\":\"miui.focus.pic_middle\"},\"islandFirstFloat\":false},"
                    + "\"colorDesc\":\"#FF48D0FF\",\"colorDescDark\":\"#FF0073DD\","
                    + "\"islandFirstFloat\":false}";

    private FocusPayloads() {
    }
}