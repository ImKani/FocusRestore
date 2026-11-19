/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import java.util.Objects;

/** 不可变匹配声明；字段和操作白名单由严格解码边界核对。 */
public final class MatchClause {
    public final String field;
    public final String op;
    public final String value;

    MatchClause(String field, String op, String value) {
        this.field = Objects.requireNonNull(field, "field");
        this.op = Objects.requireNonNull(op, "op");
        this.value = Objects.requireNonNull(value, "value");
    }
}
