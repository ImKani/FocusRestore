/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package h1;

import java.util.concurrent.CancellationException;

/** 测试用的既有签名假 helper；不构成 ROM 接口的新证据。 */
public final class H {
    public static final class Scope {
        public int cancellations;
        public boolean fail;
    }

    public static void e(Scope scope, CancellationException cause, int mask, Object marker) {
        if (cause != null || mask != 1 || marker != null) {
            throw new IllegalArgumentException("incorrect default cancellation arguments");
        }
        scope.cancellations++;
        if (scope.fail) throw new IllegalStateException("cancel failed");
    }
}
