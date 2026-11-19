/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import java.util.List;
import java.util.Objects;

/** 一个固定路径绑定及其有序字面替换声明。 */
public final class ExtractSpec {
    public final String id;
    public final String path;
    public final boolean required;
    public final List<Replacement> replace;

    ExtractSpec(String id, String path, boolean required, List<Replacement> replace) {
        this.id = Objects.requireNonNull(id, "id");
        this.path = Objects.requireNonNull(path, "path");
        this.required = required;
        this.replace = IslandRule.immutableList(replace, "replace");
    }

    public static final class Replacement {
        public final String from;
        public final String to;

        Replacement(String from, String to) {
            this.from = Objects.requireNonNull(from, "from");
            this.to = Objects.requireNonNull(to, "to");
        }
    }
}
