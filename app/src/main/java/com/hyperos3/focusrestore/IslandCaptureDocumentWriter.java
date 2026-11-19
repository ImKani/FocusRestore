/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;

/** SAF 写入前、每片落地前和完成后都检查同一导出授权。 */
final class IslandCaptureDocumentWriter {
    interface Source {
        boolean authorized() throws IOException;
        byte[] read(int offset) throws IOException;
    }

    static void require(Source source, BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new IOException("CAPTURE_WRITE_CANCELLED");
        if (!source.authorized()) throw new IOException("CAPTURE_WRITE_INVALIDATED");
        if (cancelled.getAsBoolean()) throw new IOException("CAPTURE_WRITE_CANCELLED");
    }

    static void write(OutputStream destination, int size, Source source, BooleanSupplier cancelled) throws IOException {
        if (size <= 0 || size > IslandExportGenerator.MAX_JSON_BYTES) throw new IOException("CAPTURE_WRITE_SIZE");
        for (int offset = 0; offset < size;) {
            require(source, cancelled);
            byte[] chunk = source.read(offset);
            int expected = Math.min(IslandCaptureRuntime.CHUNK_BYTES, size - offset);
            if (chunk == null || chunk.length != expected) throw new IOException("CAPTURE_WRITE_CHUNK");
            require(source, cancelled);
            destination.write(chunk);
            offset += expected;
        }
        require(source, cancelled);
        destination.flush();
        require(source, cancelled);
    }
}
