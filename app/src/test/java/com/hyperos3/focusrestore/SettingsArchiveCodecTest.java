/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class SettingsArchiveCodecTest {
    @Test public void archiveRoundTripsSettingsAndRules() throws Exception {
        FocusRestoreSettings settings = FocusRestoreSettings.defaults();
        byte[] rules = new byte[]{1, 2, 3, 4};
        byte[] zip = SettingsArchiveCodec.encode(settings, rules);
        SettingsArchiveCodec.Decoded decoded = SettingsArchiveCodec.decode(new ByteArrayInputStream(zip));
        assertFalse(decoded.legacy);
        assertArrayEquals(rules, decoded.rules);
        assertArrayEquals(SettingsBackupCodec.encode(settings), SettingsBackupCodec.encode(decoded.settings));
    }

    @Test public void legacyJsonRemainsSettingsOnly() throws Exception {
        FocusRestoreSettings settings = FocusRestoreSettings.defaults();
        byte[] json = SettingsBackupCodec.encode(settings);
        SettingsArchiveCodec.Decoded decoded = SettingsArchiveCodec.decode(new ByteArrayInputStream(json));
        assertTrue(decoded.legacy);
        assertNull(decoded.rules);
        assertArrayEquals(json, SettingsBackupCodec.encode(decoded.settings));
    }

    @Test public void zipWithoutRulesIsRejected() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "manifest.json", "{\"format\":\"FocusRestore.backup\",\"version\":1}");
            put(zip, "settings.json", new String(SettingsBackupCodec.encode(FocusRestoreSettings.defaults()),
                    StandardCharsets.UTF_8));
        }
        try {
            SettingsArchiveCodec.decode(new ByteArrayInputStream(bytes.toByteArray()));
            fail("zip without rules must be rejected");
        } catch (SettingsBackupCodec.InvalidBackupException expected) {
            assertTrue(expected.getMessage().contains("规则"));
        }
    }

    private static void put(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
