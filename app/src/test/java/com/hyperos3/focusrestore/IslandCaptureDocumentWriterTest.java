/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class IslandCaptureDocumentWriterTest {
    @Test public void cancelBeforeWritingPerformsNoIpcOrOutput() {
        AtomicInteger operations = new AtomicInteger();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IslandCaptureDocumentWriter.Source source = new IslandCaptureDocumentWriter.Source() {
            @Override public boolean authorized() { operations.incrementAndGet(); return true; }
            @Override public byte[] read(int offset) { operations.incrementAndGet(); return new byte[] {1}; }
        };
        assertEquals("CAPTURE_WRITE_CANCELLED", assertThrows(IOException.class,
                () -> IslandCaptureDocumentWriter.write(out, 1, source, () -> true)).getMessage());
        assertEquals(0, operations.get());
        assertEquals(0, out.size());
    }

    @Test public void revocationDuringReadPreventsTheNextChunkFromLanding() {
        int limit = IslandCaptureRuntime.CHUNK_BYTES;
        AtomicBoolean allowed = new AtomicBoolean(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IslandCaptureDocumentWriter.Source source = new IslandCaptureDocumentWriter.Source() {
            @Override public boolean authorized() { return allowed.get(); }
            @Override public byte[] read(int offset) {
                if (offset == 0) return new byte[limit];
                assertEquals(limit, offset);
                allowed.set(false);
                return new byte[] {7};
            }
        };
        assertEquals("CAPTURE_WRITE_INVALIDATED", assertThrows(IOException.class,
                () -> IslandCaptureDocumentWriter.write(out, limit + 1, source, () -> false)).getMessage());
        assertEquals(limit, out.size());
    }

    @Test public void lossDuringFlushIsNotReportedAsSuccess() {
        AtomicBoolean allowed = new AtomicBoolean(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override public void flush() { allowed.set(false); }
        };
        IslandCaptureDocumentWriter.Source source = new IslandCaptureDocumentWriter.Source() {
            @Override public boolean authorized() { return allowed.get(); }
            @Override public byte[] read(int offset) { return new byte[] {1}; }
        };
        assertEquals("CAPTURE_WRITE_INVALIDATED", assertThrows(IOException.class,
                () -> IslandCaptureDocumentWriter.write(out, 1, source, () -> false)).getMessage());
        assertEquals(1, out.size());
    }
}
