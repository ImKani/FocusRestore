package com.hyperos3.focustestnotifier;

/**
 * Verbatim focus/island payloads captured from the device with
 * {@code adb shell dumpsys notification --noredact}, plus small derived variants.
 *
 * <p>Keeping the real strings here is the point: the module's parser and banner behaviour were
 * debugged against exactly these values, so a test notification built from them reproduces field
 * behaviour (including the duplicated {@code aodTitle}) instead of an idealised approximation.
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

    private FocusPayloads() {
    }
}
