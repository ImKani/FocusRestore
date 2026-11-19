/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.List;

/** C2-1 纯内存缓存；无线程、监听器、设置读取或 SystemUI 接入。 */
public final class IslandRuleSetCache {
    private final IslandRuleSetLoader loader = new IslandRuleSetLoader();
    private volatile IslandRuleSetLoader.Snapshot snapshot = IslandRuleSetLoader.unloaded(0L);

    /** 热路径仅取 volatile 引用，绝不补读文件或自动触发重建。 */
    public IslandRuleSetLoader.Snapshot current() {
        return snapshot;
    }

    /** 规则或相关设置变更时由外层显式调用；旧规则立即不再由缓存提供。 */
    public synchronized void invalidate() {
        snapshot = IslandRuleSetLoader.unloaded(Math.incrementExact(snapshot.generation));
    }

    synchronized boolean publishVerified(long generation, IslandRuleSetLoader.Snapshot candidate,
            boolean keepCurrent) {
        if (snapshot.generation != generation || candidate == null
                || candidate.generation != generation || !candidate.loaded || !candidate.failures.isEmpty()) return false;
        // 同一来源续租仍须完整校验；沿用快照对象可避免无内容变化时刷新正在显示的正文。
        if (!keepCurrent) snapshot = candidate;
        return true;
    }

    /**
     * 调用方在非热路径显式重建。开始时即失效旧结果；IO 在锁外进行。
     * 返回 false 表示加载期间发生了再次失效/重建，较老结果已丢弃。
     */
    public boolean rebuild(List<IslandRuleSetLoader.Source> orderedSources) {
        final IslandRuleSetLoader.Snapshot pending;
        synchronized (this) {
            invalidate();
            pending = snapshot;
        }
        IslandRuleSetLoader.Snapshot loaded = loader.load(pending.generation, orderedSources);
        synchronized (this) {
            // 用对象身份阻止任何旧重建覆盖新状态，不仅比较可能变化的规则 revision。
            if (snapshot != pending) return false;
            snapshot = loaded;
            return true;
        }
    }
}
