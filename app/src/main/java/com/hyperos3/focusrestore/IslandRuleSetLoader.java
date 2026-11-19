/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** C2-1 的显式加载边界；调用方在热路径之外提供按导入/添加顺序排列的来源。 */
public final class IslandRuleSetLoader {
    private final IslandRuleCodec codec = new IslandRuleCodec();

    public interface StreamSource {
        /** 每次返回新的流，所有权交给加载器；不得返回共享的已消费流。 */
        InputStream open() throws IOException;
    }

    /** 外部条目元数据，不从规则 JSON 推导 id、revision、enabled 或 priority。 */
    public static final class Source {
        public final String id;
        public final long revision;
        public final boolean enabled;
        public final int priority;
        public final StreamSource streamSource;
        private final boolean verifiedObject;

        static Source fromVerifiedObject(String id, long revision, boolean enabled, int priority, StreamSource bytes) {
            return new Source(id, revision, enabled, priority, bytes, true);
        }

        public Source(String id, long revision, boolean enabled, StreamSource streamSource) {
            this(id, revision, enabled, 0, streamSource);
        }

        public Source(String id, long revision, boolean enabled, int priority,
                StreamSource streamSource) {
            this(id, revision, enabled, priority, streamSource, false);
        }

        private Source(String id, long revision, boolean enabled, int priority,
                StreamSource streamSource, boolean verifiedObject) {
            this.id = id;
            this.revision = revision;
            this.enabled = enabled;
            this.priority = priority;
            this.streamSource = streamSource;
            this.verifiedObject = verifiedObject;
        }

        /** 不在构造时读取文件，不扫描目录或决定文件的声明顺序。 */
        public static Source fromFile(String id, long revision, boolean enabled, int priority,
                final File file) {
            return new Source(id, revision, enabled, priority, new StreamSource() {
                @Override public InputStream open() throws IOException {
                    return new FileInputStream(file);
                }
            });
        }
    }

    public enum Code {
        SOURCE_METADATA, SOURCE_IO, SOURCE_EXCEPTION, DUPLICATE_ID,
        DECODE_REJECTED, SET_BYTE_CAPACITY, ACTIVE_RULE_CAPACITY
    }

    /** 不记录正文、文件路径或异常原文；decodeError 保留既有 L1 定位信息。 */
    public static final class Failure {
        public final int declarationIndex;
        public final String ruleId;
        public final Code code;
        public final IslandRuleCodec.Error decodeError;
        public final String reason;

        private Failure(int declarationIndex, String ruleId, Code code,
                IslandRuleCodec.Error decodeError, String reason) {
            this.declarationIndex = declarationIndex;
            this.ruleId = ruleId;
            this.code = code;
            this.decodeError = decodeError;
            this.reason = reason;
        }
    }

    /** 两份不可变视图：Set 保持声明顺序，orderedActiveEntries 用于后续阶段选择。 */
    public static final class Snapshot {
        public final long generation;
        public final boolean loaded;
        public final IslandRuleSet ruleSet;
        public final List<IslandRuleSet.Entry> orderedActiveEntries;
        public final List<Failure> failures;

        private Snapshot(long generation, boolean loaded, List<IslandRuleSet.Entry> entries,
                List<Failure> failures) {
            this(generation, generation, loaded, entries, failures);
        }

        private Snapshot(long generation, long revision, boolean loaded, List<IslandRuleSet.Entry> entries,
                List<Failure> failures) {
            this.generation = generation;
            this.loaded = loaded;
            this.ruleSet = new IslandRuleSet(revision, entries, true);
            List<IslandRuleSet.Entry> ordered = new ArrayList<>();
            for (IslandRuleSet.Entry entry : ruleSet.entries) {
                if (entry.enabled) ordered.add(entry);
            }
            // List.sort/Collections.sort 在 Java 8 保证稳定，不用减法比较以免 priority 溢出。
            Collections.sort(ordered, new Comparator<IslandRuleSet.Entry>() {
                @Override public int compare(IslandRuleSet.Entry left, IslandRuleSet.Entry right) {
                    return Integer.compare(right.priority, left.priority);
                }
            });
            this.orderedActiveEntries = Collections.unmodifiableList(ordered);
            this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
        }
    }

    static Snapshot unloaded(long generation) {
        return new Snapshot(generation, false, Collections.<IslandRuleSet.Entry>emptyList(),
                Collections.<Failure>emptyList());
    }

    /** 逐条有界解码；拒绝项不占已接纳 Set 的容量，后续较小条目仍可被接纳。 */
    public Snapshot load(long generation, List<Source> sources) {
        return load(generation, Math.max(0L, generation), sources);
    }

    Snapshot load(long generation, long revision, List<Source> sources) {
        // generation 是调用方代次，不属于规则数据；无效输入同样以固定原因返回空结果。
        List<Failure> failures = new ArrayList<>();
        List<IslandRuleSet.Entry> entries = new ArrayList<>();
        if (generation < 0 || sources == null) {
            failures.add(new Failure(-1, null, Code.SOURCE_METADATA, null,
                    "nonnegative generation and ordered sources required"));
            return new Snapshot(Math.max(0L, generation), true, entries, failures);
        }
        List<Source> orderedSources = new ArrayList<>(sources);
        Set<String> acceptedIds = new HashSet<>();
        java.util.IdentityHashMap<StreamSource, IslandRuleCodec.DecodeResult> shared = new java.util.IdentityHashMap<>();
        int acceptedBytes = 0;
        int activeCount = 0;
        for (int index = 0; index < orderedSources.size(); index++) {
            Source source = orderedSources.get(index);
            if (source == null || source.id == null || source.id.isEmpty()
                    || source.revision < 0 || source.streamSource == null) {
                failures.add(new Failure(index, source == null ? null : source.id,
                        Code.SOURCE_METADATA, null, "invalid external entry metadata"));
                continue;
            }
            if (acceptedIds.contains(source.id)) {
                failures.add(new Failure(index, source.id, Code.DUPLICATE_ID, null,
                        "duplicate accepted rule id"));
                continue;
            }
            IslandRuleCodec.DecodeResult decoded = source.verifiedObject ? shared.get(source.streamSource) : null;
            boolean firstObject = decoded == null;
            try {
                if (firstObject) {
                    try (InputStream input = source.streamSource.open()) {
                        decoded = codec.decode(input);
                    }
                }
            } catch (IOException e) {
                // 关闭失败也拒绝该条，不发布无法确认资源释放状态的加载结果。
                failures.add(new Failure(index, source.id, Code.SOURCE_IO, null,
                        "rule stream open or close failed"));
                continue;
            } catch (RuntimeException e) {
                failures.add(new Failure(index, source.id, Code.SOURCE_EXCEPTION, null,
                        "rule source failed"));
                continue;
            }
            if (!decoded.isSuccess()) {
                failures.add(new Failure(index, source.id, Code.DECODE_REJECTED, decoded.error,
                        "strict rule decode rejected"));
                continue;
            }
            // enabled=false 也保留原文容量；先检查总字节，再检查活跃数，优先级不影响接纳顺序。
            int additionalBytes = firstObject ? decoded.utf8Bytes : 0;
            if (acceptedBytes + additionalBytes > IslandRuleSet.MAX_TOTAL_UTF8_BYTES) {
                failures.add(new Failure(index, source.id, Code.SET_BYTE_CAPACITY, null,
                        "accepted rule files exceed 2097152 UTF-8 bytes"));
                continue;
            }
            if (source.enabled && activeCount >= IslandRuleSet.MAX_ACTIVE_RULES) {
                failures.add(new Failure(index, source.id, Code.ACTIVE_RULE_CAPACITY, null,
                        "accepted active rules exceed 50"));
                continue;
            }
            entries.add(new IslandRuleSet.Entry(source.id, source.revision, source.enabled,
                    source.priority, decoded.rule, decoded.utf8Bytes));
            acceptedIds.add(source.id);
            acceptedBytes += additionalBytes;
            if (source.verifiedObject && firstObject) shared.put(source.streamSource, decoded);
            if (source.enabled) activeCount++;
        }
        return new Snapshot(generation, revision, true, entries, failures);
    }
}
