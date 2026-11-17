/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** 配方选择不改变采样/生命周期；OS4 缺当前配置原生样本时保留旧材质。 */
final class BannerMaterialRecipe {
    static final int LEGACY = 0, OS3_FOCUS_HUN = 1, OS4_CAPTURED_HUN = 2;

    static boolean freezeEligibility(boolean eligible, boolean os3, boolean currentSample) {
        return eligible && (os3 || currentSample);
    }

    static int choose(boolean eligible, boolean os3, boolean glass, boolean currentSample) {
        if (!eligible) return LEGACY;
        if (os3) return glass ? LEGACY : OS3_FOCUS_HUN;
        return glass && currentSample ? OS4_CAPTURED_HUN : LEGACY;
    }

    private BannerMaterialRecipe() { }
}
