/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class HookInitializationTest {
    @Test
    public void entryPointConstructionDoesNotRequireAndroidLooper() {
        assertNotNull(new HyperOS3FocusRestoreHook());
    }
}