/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import java.util.List;
import java.util.Objects;

/** 正文片段的不可变投影顺序，不含任何 View 或图标/计时器能力。 */
public final class DisplaySpec {
    public final List<Projection> order;
    public final String separator;
    public final boolean dedupe;

    DisplaySpec(List<Projection> order, String separator, boolean dedupe) {
        this.order = IslandRule.immutableList(order, "order");
        this.separator = Objects.requireNonNull(separator, "separator");
        this.dedupe = dedupe;
    }

    public static final class Projection {
        public final String slot;
        public final String ref;

        Projection(String slot, String ref) {
            this.slot = Objects.requireNonNull(slot, "slot");
            this.ref = Objects.requireNonNull(ref, "ref");
        }
    }
}
