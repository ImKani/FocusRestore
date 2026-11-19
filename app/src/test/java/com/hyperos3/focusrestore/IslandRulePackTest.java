/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public class IslandRulePackTest {
    private static final int HEADER = 12;
    private static final int END = 32;
    private static final byte[] TEXT = "岛".getBytes(StandardCharsets.UTF_8);

    static IslandRulePack.Entry entry(int id, boolean enabled, int priority, byte[] bytes) {
        return new IslandRulePack.Entry(new UUID(0, id), enabled, priority, bytes);
    }

    static IslandRulePack.Decoded decode(byte[] bytes) throws Exception {
        return IslandRulePack.decode(new ByteArrayInputStream(bytes));
    }

    private static void rejects(IslandRulePack.Code code, byte[] bytes) {
        assertEquals(code, assertThrows(IslandRulePack.Failure.class, () -> decode(bytes)).code);
    }

    private static void writeReject(IslandRulePack.Code code, List<IslandRulePack.Entry> entries) {
        assertEquals(code, assertThrows(IslandRulePack.Failure.class, () -> IslandRulePack.encode(entries)).code);
    }

    private static byte[] seal(byte[] bytes) throws Exception {
        int length = bytes.length - END;
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, length));
        System.arraycopy(hash, 0, bytes, length, END);
        return bytes;
    }

    @Test public void headerOrderSignedPriorityAndExactUtf8RoundTrip() throws Exception {
        byte[] encoded = IslandRulePack.encode(Arrays.asList(entry(6, false, Integer.MIN_VALUE, TEXT),
                entry(4, true, Integer.MAX_VALUE, new byte[] {'a'})));
        ByteBuffer header = ByteBuffer.wrap(encoded);
        assertEquals(0x4652504b, header.getInt());
        assertEquals(1, header.getInt());
        assertEquals(2, header.getInt());
        assertEquals(0, header.getLong());
        assertEquals(6, header.getLong());
        assertEquals(0, header.get());
        assertEquals(Integer.MIN_VALUE, header.getInt());
        assertEquals(TEXT.length, header.getInt());
        IslandRulePack.Decoded decoded = decode(encoded);
        assertEquals(new UUID(0, 6), decoded.entries.get(0).id);
        assertFalse(decoded.entries.get(0).enabled);
        assertArrayEquals(TEXT, decoded.entries.get(0).bytes());
        assertEquals(Integer.MAX_VALUE, decoded.entries.get(1).priority);
        assertTrue(decoded.entries.get(1).enabled);
        assertArrayEquals(encoded, IslandRulePack.encode(decoded.entries));
        byte[] detached = decoded.entries.get(0).bytes();
        detached[0] = 0;
        assertArrayEquals(TEXT, decoded.entries.get(0).bytes());
    }

    @Test public void requiresCompleteAuthenticatedEmptyEnvelopeAndEof() throws Exception {
        byte[] empty = IslandRulePack.encode(Collections.emptyList());
        assertEquals(44, empty.length);
        assertTrue(decode(empty).entries.isEmpty());
        rejects(IslandRulePack.Code.TRUNCATED, new byte[0]);
        rejects(IslandRulePack.Code.TRUNCATED, new byte[] {'[', ']'});
        rejects(IslandRulePack.Code.TRAILING, Arrays.copyOf(empty, empty.length + 1));
        empty[empty.length - 1] ^= 1;
        rejects(IslandRulePack.Code.DIGEST, empty);
    }

    @Test public void rejectsUnknownVersionUntrustedCountsAndLengthsBeforeAllocation() throws Exception {
        byte[] empty = IslandRulePack.encode(Collections.emptyList());
        for (int count : new int[] {-1, 1025, Integer.MAX_VALUE}) {
            byte[] invalid = empty.clone();
            ByteBuffer.wrap(invalid).putInt(8, count);
            rejects(IslandRulePack.Code.ENTRY_COUNT, seal(invalid));
        }
        byte[] invalid = empty.clone();
        ByteBuffer.wrap(invalid).putInt(4, 2);
        rejects(IslandRulePack.Code.VERSION, seal(invalid));
        invalid[0] = 'X';
        rejects(IslandRulePack.Code.MAGIC, seal(invalid));
        byte[] single = IslandRulePack.encode(Collections.singletonList(entry(1, false, 0, TEXT)));
        for (int length : new int[] {0, -1, 65537, Integer.MAX_VALUE}) {
            invalid = single.clone();
            ByteBuffer.wrap(invalid).putInt(HEADER + 21, length);
            rejects(IslandRulePack.Code.RULE_SIZE, seal(invalid));
        }
        invalid = single.clone();
        invalid[HEADER + 16] = 2;
        rejects(IslandRulePack.Code.ENABLED, seal(invalid));
    }

    @Test public void failsDuplicateIdInvalidUtf8EveryTruncationAndDamagedDigest() throws Exception {
        byte[] normal = IslandRulePack.encode(Arrays.asList(entry(1, true, 0, TEXT), entry(2, true, 0, TEXT)));
        for (int length = 0; length < normal.length; length++) {
            rejects(IslandRulePack.Code.TRUNCATED, Arrays.copyOf(normal, length));
        }
        byte[] duplicate = normal.clone();
        ByteBuffer.wrap(duplicate).putLong(HEADER + 25 + TEXT.length + 8, 1L);
        rejects(IslandRulePack.Code.DUPLICATE_ID, seal(duplicate));
        writeReject(IslandRulePack.Code.DUPLICATE_ID, Arrays.asList(entry(1, true, 0, TEXT), entry(1, false, 0, TEXT)));
        byte[] badUtf8 = normal.clone();
        badUtf8[HEADER + 25] = (byte) 0xff;
        rejects(IslandRulePack.Code.ENCODING, seal(badUtf8));
        writeReject(IslandRulePack.Code.ENCODING, Collections.singletonList(entry(1, true, 0, new byte[] {(byte) 0xc0, (byte) 0xaf})));
        normal[normal.length - END] ^= 1;
        rejects(IslandRulePack.Code.DIGEST, normal);
    }

    @Test public void exactEntryRuleContentAndEnvelopeLimitsChargeEveryDisabledCopy() throws Exception {
        List<IslandRulePack.Entry> entries = new ArrayList<>();
        byte[] maximum = new byte[65536];
        byte[] last = new byte[65536 - (1024 - 32)];
        for (int index = 0; index < 1024; index++) {
            entries.add(entry(index, index < 50, index, index < 31 ? maximum : index == 31 ? last : new byte[1]));
        }
        byte[] exact = IslandRulePack.encode(entries);
        assertEquals(2_122_796, IslandRulePack.MAX_BYTES);
        assertEquals(IslandRulePack.MAX_BYTES, exact.length);
        assertEquals(1024, decode(exact).entries.size());
        assertEquals(65536, decode(exact).entries.get(0).bytes().length);
        ByteArrayInputStream overEnvelope = new ByteArrayInputStream(Arrays.copyOf(exact, exact.length + 2));
        assertEquals(IslandRulePack.Code.PACK_SIZE,
                assertThrows(IslandRulePack.Failure.class, () -> IslandRulePack.decode(overEnvelope)).code);
        assertEquals(1, overEnvelope.available());
        entries.set(50, entry(50, true, 0, new byte[1]));
        writeReject(IslandRulePack.Code.ACTIVE_COUNT, entries);
        byte[] activeOverflow = exact.clone();
        int offset = HEADER + 32 * 25 + 31 * maximum.length + last.length + (50 - 32) * 26;
        activeOverflow[offset + 16] = 1;
        rejects(IslandRulePack.Code.ACTIVE_COUNT, seal(activeOverflow));
        entries.set(50, entry(50, false, 0, new byte[2]));
        writeReject(IslandRulePack.Code.CONTENT_SIZE, entries);
        entries.set(50, entry(50, false, 0, new byte[1]));
        entries.add(entry(1024, false, 0, new byte[1]));
        writeReject(IslandRulePack.Code.ENTRY_COUNT, entries);
        writeReject(IslandRulePack.Code.RULE_SIZE, Collections.singletonList(entry(0, false, 0, new byte[65537])));
    }

    @Test public void contentCanOverflowWhileEnvelopeStillFits() throws Exception {
        List<IslandRulePack.Entry> entries = new ArrayList<>();
        byte[] large = new byte[65536];
        for (int index = 0; index < 32; index++) entries.add(entry(index, false, 0, large));
        byte[] exact = IslandRulePack.encode(entries);
        byte[] overflow = Arrays.copyOf(exact, exact.length + 26);
        ByteBuffer.wrap(overflow).putInt(8, 33).position(exact.length - END);
        ByteBuffer frame = ByteBuffer.wrap(overflow);
        frame.position(exact.length - END);
        frame.putLong(0).putLong(32).put((byte) 0).putInt(0).putInt(1).put((byte) 'a');
        rejects(IslandRulePack.Code.CONTENT_SIZE, seal(overflow));
    }
}
