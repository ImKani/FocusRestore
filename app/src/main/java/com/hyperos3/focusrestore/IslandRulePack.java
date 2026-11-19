/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** FRPK 文件封装。规则语义和本机批准由规则仓库处理。 */
final class IslandRulePack {
    private static final int MAGIC = 0x4652504b;
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 12;
    private static final int ENTRY_BYTES = 25;
    private static final int HASH_BYTES = 32;
    static final int MAX_BYTES = HEADER_BYTES + HASH_BYTES + ENTRY_BYTES * IslandRuleRepository.MAX_ENTRIES
            + IslandRuleRepository.MAX_RULE_FILE_BYTES;

    enum Code {
        MAGIC, VERSION, ENTRY_COUNT, ACTIVE_COUNT, DUPLICATE_ID, ENABLED, RULE_SIZE, CONTENT_SIZE,
        PACK_SIZE, ENCODING, TRUNCATED, DIGEST, TRAILING
    }

    static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        final Code code;
        Failure(Code code) { super("FRPK_" + code.name()); this.code = code; }
    }

    static final class Entry {
        final UUID id;
        final boolean enabled;
        final int priority;
        private final byte[] bytes;

        Entry(UUID id, boolean enabled, int priority, byte[] bytes) {
            this.id = Objects.requireNonNull(id);
            this.enabled = enabled;
            this.priority = priority;
            this.bytes = bytes.clone();
        }

        byte[] bytes() { return bytes.clone(); }
    }

    static final class Decoded {
        final List<Entry> entries;
        final String digest;

        private Decoded(List<Entry> entries, byte[] digest) {
            this.entries = Collections.unmodifiableList(entries);
            this.digest = IslandRuleRepository.hex(digest);
        }
    }

    private IslandRulePack() {}

    /** 根据实际读取字节和 EOF 校验封装。调用方负责关闭流。 */
    static Decoded decode(InputStream input) throws IOException {
        byte[] bytes = IslandRuleRepository.readBounded(input, MAX_BYTES, new Failure(Code.PACK_SIZE));
        ByteBuffer in = ByteBuffer.wrap(bytes);
        requireRemaining(in, HEADER_BYTES);
        if (in.getInt() != MAGIC) throw new Failure(Code.MAGIC);
        if (in.getInt() != VERSION) throw new Failure(Code.VERSION);
        int count = in.getInt();
        checkCount(count);
        List<Entry> entries = new ArrayList<>(count);
        Limits limits = new Limits();
        for (int index = 0; index < count; index++) {
            checkInterrupted();
            requireRemaining(in, ENTRY_BYTES);
            UUID id = new UUID(in.getLong(), in.getLong());
            int enabled = in.get() & 255;
            if (enabled > 1) throw new Failure(Code.ENABLED);
            int priority = in.getInt();
            int size = in.getInt();
            limits.add(id, enabled == 1, size);
            requireRemaining(in, size);
            byte[] rule = new byte[size];
            in.get(rule);
            utf8(rule);
            entries.add(new Entry(id, enabled == 1, priority, rule));
        }
        int bodySize = in.position();
        requireRemaining(in, HASH_BYTES);
        byte[] expected = new byte[HASH_BYTES];
        in.get(expected);
        if (!MessageDigest.isEqual(expected, hash(bytes, bodySize))) throw new Failure(Code.DIGEST);
        if (in.hasRemaining()) throw new Failure(Code.TRAILING);
        return new Decoded(entries, expected);
    }

    /** 仓库出口传入已经重建的条目，完整编码后一次返回。 */
    static byte[] encode(List<Entry> source) throws IOException {
        checkCount(source.size());
        Limits limits = new Limits();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(source.size());
            for (Entry entry : source) {
                checkInterrupted();
                limits.add(entry.id, entry.enabled, entry.bytes.length);
                utf8(entry.bytes);
                out.writeLong(entry.id.getMostSignificantBits());
                out.writeLong(entry.id.getLeastSignificantBits());
                out.writeByte(entry.enabled ? 1 : 0);
                out.writeInt(entry.priority);
                out.writeInt(entry.bytes.length);
                out.write(entry.bytes);
            }
            byte[] body = buffer.toByteArray();
            out.write(hash(body, body.length));
        }
        return buffer.toByteArray();
    }

    private static final class Limits {
        private final Set<UUID> ids = new HashSet<>();
        private int enabledCount;
        private int contentBytes;

        void add(UUID id, boolean enabled, int size) throws Failure {
            if (!ids.add(id)) throw new Failure(Code.DUPLICATE_ID);
            if (enabled && ++enabledCount > IslandRuleRepository.MAX_ACTIVE) throw new Failure(Code.ACTIVE_COUNT);
            if (size < 1 || size > IslandRuleRepository.MAX_RULE_BYTES) throw new Failure(Code.RULE_SIZE);
            if (size > IslandRuleRepository.MAX_RULE_FILE_BYTES - contentBytes) throw new Failure(Code.CONTENT_SIZE);
            contentBytes += size;
        }
    }

    private static void checkCount(int count) throws Failure {
        if (count < 0 || count > IslandRuleRepository.MAX_ENTRIES) throw new Failure(Code.ENTRY_COUNT);
    }

    private static void requireRemaining(ByteBuffer buffer, int count) throws Failure {
        if (buffer.remaining() < count) throw new Failure(Code.TRUNCATED);
    }

    private static void utf8(byte[] bytes) throws Failure {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException failure) {
            throw new Failure(Code.ENCODING);
        }
    }

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("FRPK_INTERRUPTED");
    }

    private static byte[] hash(byte[] bytes, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes, 0, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
