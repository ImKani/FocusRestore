/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

/** Pure state gate for reusing an observed native V3 template source. */
final class NativeFocusTemplateStatePolicy {
    enum Rejection {
        NONE,
        SOURCE_CHANGED,
        LOADER_DISCONNECTED,
        NOTIFICATION_GENERATION_CHANGED,
        NOTIFICATION_REMOVED,
        TEMPLATE_CHANGED,
        BUILDER_CHANGED
    }

    static Rejection evaluate(boolean currentSource, boolean loaderConnected,
                              boolean generationMatches, boolean notificationRemoved,
                              boolean templateMatches, Object observedBuilder,
                              Object currentBuilder) {
        if (!currentSource) return Rejection.SOURCE_CHANGED;
        if (!loaderConnected) return Rejection.LOADER_DISCONNECTED;
        if (!generationMatches) return Rejection.NOTIFICATION_GENERATION_CHANGED;
        if (notificationRemoved) return Rejection.NOTIFICATION_REMOVED;
        if (!templateMatches) return Rejection.TEMPLATE_CHANGED;
        if (currentBuilder != null && currentBuilder != observedBuilder) {
            return Rejection.BUILDER_CHANGED;
        }
        return Rejection.NONE;
    }

    private NativeFocusTemplateStatePolicy() { }
}
