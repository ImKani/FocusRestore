/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** 关于页完整备份：设置 JSON + 规则包 FRPK，放在单个 ZIP 中。 */
final class SettingsArchiveCodec {
    static final String FORMAT = "FocusRestore.backup";
    static final int FORMAT_VERSION = 1;
    static final int MAX_BYTES = SettingsBackupCodec.MAX_FILE_BYTES + IslandRulePack.MAX_BYTES + 64 * 1024;
    private static final String MANIFEST = "manifest.json";
    private static final String SETTINGS = "settings.json";
    private static final String RULES = "rules.frpk";
    private static final int MAX_MANIFEST_BYTES = 4 * 1024;

    static final class Decoded {
        final FocusRestoreSettings settings;
        final byte[] rules;
        final boolean legacy;

        Decoded(FocusRestoreSettings settings, byte[] rules, boolean legacy) {
            this.settings = settings;
            this.rules = rules;
            this.legacy = legacy;
        }
    }

    static String defaultFileName(long timeMillis) {
        return SettingsBackupCodec.defaultFileName(timeMillis).replaceAll("\\.json$", ".zip");
    }

    static byte[] encode(FocusRestoreSettings settings, byte[] rules) throws IOException {
        if (settings == null || rules == null || rules.length == 0 || rules.length > IslandRulePack.MAX_BYTES) {
            throw invalid("规则包为空或超过大小上限");
        }
        byte[] settingsJson = SettingsBackupCodec.encode(settings);
        if (settingsJson.length > SettingsBackupCodec.MAX_FILE_BYTES) throw invalid("配置超过大小上限");
        String manifestText = "{\"format\":\"" + FORMAT + "\",\"version\":" + FORMAT_VERSION + "}";
        byte[] manifestBytes = manifestText.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, MANIFEST, manifestBytes);
            put(zip, SETTINGS, settingsJson);
            put(zip, RULES, rules);
        }
        byte[] encoded = bytes.toByteArray();
        if (encoded.length > MAX_BYTES) throw invalid("备份压缩包超过大小上限");
        return encoded;
    }

    static Decoded decode(InputStream input) throws IOException {
        byte[] all = readBounded(input, MAX_BYTES);
        if (all.length >= 2 && all[0] == 'P' && all[1] == 'K') return decodeZip(all);
        // 兼容旧版仅设置 JSON；规则保持当前内容，确认文案会说明只覆盖设置。
        return new Decoded(SettingsBackupCodec.decode(all), null, true);
    }

    private static Decoded decodeZip(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkInterrupted();
                String name = entry.getName();
                if (!MANIFEST.equals(name) && !SETTINGS.equals(name) && !RULES.equals(name)) {
                    throw invalid("备份包含未知文件");
                }
                if (entries.containsKey(name)) throw invalid("备份包含重复文件");
                int limit = MANIFEST.equals(name) ? MAX_MANIFEST_BYTES
                        : SETTINGS.equals(name) ? SettingsBackupCodec.MAX_FILE_BYTES : IslandRulePack.MAX_BYTES;
                byte[] data = readEntry(zip, limit);
                total += data.length;
                if (total > MAX_BYTES) throw invalid("备份内容超过大小上限");
                entries.put(name, data);
            }
        }
        byte[] manifestBytes = entries.get(MANIFEST);
        byte[] settingsBytes = entries.get(SETTINGS);
        byte[] rules = entries.get(RULES);
        if (manifestBytes == null || settingsBytes == null || rules == null) {
            throw invalid("备份缺少设置或规则包");
        }
        try {
            JSONObject manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
            if (!FORMAT.equals(manifest.optString("format"))
                    || manifest.optInt("version", -1) != FORMAT_VERSION) {
                throw invalid("不支持此备份格式或版本");
            }
        } catch (org.json.JSONException malformed) {
            throw invalid("备份清单格式无效");
        }
        return new Decoded(SettingsBackupCodec.decode(settingsBytes), rules, false);
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] readEntry(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkInterrupted();
            if (bytes.size() > limit - count) throw invalid("备份文件超过大小上限");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        if (input == null) throw invalid("无法打开备份文件");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkInterrupted();
            if (bytes.size() > limit - count) throw invalid("备份文件超过大小上限");
            bytes.write(buffer, 0, count);
        }
        if (bytes.size() == 0) throw invalid("备份文件为空");
        return bytes.toByteArray();
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("backup interrupted");
    }

    private static SettingsBackupCodec.InvalidBackupException invalid(String reason) {
        return new SettingsBackupCodec.InvalidBackupException(reason);
    }

    private SettingsArchiveCodec() { }
}
