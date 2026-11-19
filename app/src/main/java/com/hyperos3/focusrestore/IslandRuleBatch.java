/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 批次格式 1：固定清单、按首次出现顺序传输的唯一对象、修订尾标及 EOF。 */
final class IslandRuleBatch {
    static final int VERSION = 1;
    private static final int MAGIC = 0x46524231; // FRB1
    final long revision;
    final List<IslandRuleSetLoader.Source> sources;

    private IslandRuleBatch(long revision, List<IslandRuleSetLoader.Source> sources) {
        this.revision = revision;
        this.sources = Collections.unmodifiableList(sources);
    }

    static void write(OutputStream stream, IslandRuleRepository.ReadView view) throws IOException {
        byte[] manifest = view.exportManifest();
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeLong(view.revision);
        out.writeInt(manifest.length);
        out.write(manifest);
        Set<String> written = new HashSet<>();
        for (IslandRuleRepository.Entry entry : view.entries) {
            if (written.add(entry.blob)) out.write(view.read(entry));
        }
        out.writeLong(view.revision);
        out.flush();
    }

    static IslandRuleBatch read(InputStream stream, long expected,
            IslandRuleImportValidator.Environment environment) throws IOException {
        if (environment == null || environment.systemUiName == null || environment.systemUiName.isEmpty()
                || environment.systemUiCode == null || environment.systemUiCode < 0) {
            throw new IOException("BATCH_SYSTEM_UI_VERSION_UNKNOWN");
        }
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != MAGIC || in.readInt() != VERSION || expected <= 0 || in.readLong() != expected) {
            throw new IOException("BATCH_HEADER");
        }
        int length = in.readInt();
        if (length <= 0 || length > IslandRuleRepository.MAX_MANIFEST_BYTES) throw new IOException("BATCH_MANIFEST_SIZE");
        byte[] manifestBytes = new byte[length];
        in.readFully(manifestBytes);
        IslandRuleRepository.Manifest manifest = IslandRuleRepository.decodeManifest(manifestBytes);
        if (manifest.revision != expected) throw new IOException("BATCH_MANIFEST_REVISION");
        IslandRuleImportValidator validator = new IslandRuleImportValidator();
        Map<String, Blob> blobs = new HashMap<>();
        List<IslandRuleSetLoader.Source> sources = new ArrayList<>();
        for (IslandRuleRepository.Entry entry : manifest.entries) {
            Blob blob = blobs.get(entry.blob);
            if (blob == null) {
                byte[] bytes = new byte[entry.byteCount];
                in.readFully(bytes);
                if (!entry.sha256.equals(IslandRuleRepository.digest(bytes))) throw new IOException("BATCH_DIGEST");
                blob = new Blob(bytes, validator.validate(bytes, environment));
                blobs.put(entry.blob, blob);
            }
            checkApproval(entry, blob.validation);
            sources.add(IslandRuleSetLoader.Source.fromVerifiedObject(entry.id, entry.revision,
                    entry.enabled, entry.priority, blob));
        }
        if (in.readLong() != expected || in.read() != -1) throw new IOException("BATCH_TRAILER");
        return new IslandRuleBatch(expected, sources);
    }

    private static void checkApproval(IslandRuleRepository.Entry entry, IslandRuleImportValidator.Result result)
            throws IOException {
        if (!result.canApprove(entry.forced)) throw new IOException("BATCH_RULE_VALIDATION");
        for (IslandRuleImportValidator.Finding finding : result.findings) {
            if (finding.blocking && !entry.skipped.contains(finding.code + " " + finding.path + " " + finding.reason)) {
                throw new IOException("BATCH_APPROVAL");
            }
        }
    }

    private static final class Blob implements IslandRuleSetLoader.StreamSource {
        final byte[] bytes;
        final IslandRuleImportValidator.Result validation;

        Blob(byte[] bytes, IslandRuleImportValidator.Result validation) {
            this.bytes = bytes;
            this.validation = validation;
        }

        @Override public InputStream open() { return new ByteArrayInputStream(bytes); }
    }
}
