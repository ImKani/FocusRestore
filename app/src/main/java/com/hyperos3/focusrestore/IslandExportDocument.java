/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 纯 Java 导出结果；仅包含内存字符串、元数据与内容无关原因。 */
public final class IslandExportDocument {
    public enum Reason {
        NONE, INVALID_REQUEST, UNAUTHORIZED, FULL_NOT_CONFIRMED, FULL_LOCKED,
        SESSION_CHANGED, LOCK_STATE_CHANGED, CONFIRMATION_LOST, NOT_FINISHED,
        SAMPLE_LIMIT, JSON_CAPACITY, MARKDOWN_CAPACITY, UNSAFE_EXTRAS, INTERNAL_ERROR
    }

    public final String json;
    public final String markdown;
    public final Metadata meta;
    public final List<Redaction> redactions;
    public final Reason reason;
    public final boolean complete;

    IslandExportDocument(String json, String markdown, Metadata meta,
            List<Redaction> redactions, Reason reason) {
        if (meta == null) throw new IllegalArgumentException("metadata must not be null");
        if (redactions == null) throw new IllegalArgumentException("redactions must not be null");
        this.json = json;
        this.markdown = markdown;
        this.meta = meta;
        this.redactions = Collections.unmodifiableList(new ArrayList<Redaction>(redactions));
        this.reason = reason;
        this.complete = meta.complete;
    }

    public static final class Metadata {
        public final IslandExportRequest.Mode mode;
        public final boolean complete;
        public final int sampleCount;
        public final String sessionId;
        public final boolean replayableOriginal;
        public final Reason reason;

        Metadata(IslandExportRequest.Mode mode, boolean complete, int sampleCount,
                String sessionId, boolean replayableOriginal, Reason reason) {
            this.mode = mode;
            this.complete = complete;
            this.sampleCount = sampleCount;
            this.sessionId = sessionId;
            this.replayableOriginal = replayableOriginal;
            this.reason = reason;
        }
    }

    public static final class Redaction {
        public final String path;
        public final String action;
        public final String originalType;
        public final String exportedType;
        public final Integer originalUtf16Length;
        public final Integer exportedUtf16Length;
        public final String reason;
        public final boolean structureChanged;

        Redaction(String path, String action, String originalType, String exportedType,
                Integer originalUtf16Length, Integer exportedUtf16Length,
                String reason, boolean structureChanged) {
            this.path = path;
            this.action = action;
            this.originalType = originalType;
            this.exportedType = exportedType;
            this.originalUtf16Length = originalUtf16Length;
            this.exportedUtf16Length = exportedUtf16Length;
            this.reason = reason;
            this.structureChanged = structureChanged;
        }
    }
}
