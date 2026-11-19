/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 本次导出的冻结输入；元素有效性留给生成器返回结构化拒绝。 */
public final class IslandExportRequest {
    public enum Mode { REDACTED, FULL }

    public final List<IslandCaptureRecord> records;
    public final List<Integer> selection;
    public final Set<String> allowedPackages;
    public final Mode mode;

    /** 不指定模式时使用脱敏，完整模式必须由调用方显式选择。 */
    public IslandExportRequest(List<IslandCaptureRecord> records, List<Integer> selection,
            Set<String> allowedPackages) {
        this(records, selection, allowedPackages, Mode.REDACTED);
    }

    public IslandExportRequest(List<IslandCaptureRecord> records, List<Integer> selection,
            Set<String> allowedPackages, Mode mode) {
        if (records == null) throw new IllegalArgumentException("records must not be null");
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
        this.records = Collections.unmodifiableList(new ArrayList<IslandCaptureRecord>(records));
        this.selection = selection == null ? null
                : Collections.unmodifiableList(new ArrayList<Integer>(selection));
        this.allowedPackages = allowedPackages == null ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new HashSet<String>(allowedPackages));
        this.mode = mode;
    }
}
