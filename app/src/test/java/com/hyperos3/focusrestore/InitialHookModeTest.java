/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class InitialHookModeTest {
    @Test
    public void supportedHyperOsPropertiesSelectOnlyTheirMajorMode() {
        for (String property : new String[]{"3", "3.0", "OS3.0.302.0", " HyperOS 3.0 "}) {
            assertEquals(property, FocusRestoreSettings.HOOK_MODE_OS3, InitialHookMode.parse(property));
        }
        for (String property : new String[]{"4", "4.0", "OS4.0.1.0", " hyperos 4.0 "}) {
            assertEquals(property, FocusRestoreSettings.HOOK_MODE_OS4, InitialHookMode.parse(property));
        }
    }

    @Test
    public void androidMiuiUnsupportedAndMalformedVersionsCannotSelectAHook() {
        for (String property : new String[]{null, "", " ", "2.0", "5.0", "Android 16",
                "V14", "34", "14", "OS4beta", "4..0", "4.0foo", "4\n3"}) {
            assertEquals(property, 0, InitialHookMode.parse(property));
        }
    }
}
