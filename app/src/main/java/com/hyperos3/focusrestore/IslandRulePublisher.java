/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 单一发布者。任务、来源和 lease 在同一锁内检查，候选始终在锁外构造。 */
final class IslandRulePublisher {
    static final long ATTEMPT_MS = 5_000;
    static final long LEASE_MS = 30_000;
    private final IslandRuleSetCache cache = new IslandRuleSetCache();
    private final IslandRuleSetLoader loader = new IslandRuleSetLoader();
    private final LongSupplier clock;
    private Task latest;
    private Head applied;
    private long expires;

    static final class Head {
        final String session;
        final long revision;

        Head(String session, long revision) {
            this.session = Objects.requireNonNull(session);
            this.revision = revision;
        }

        boolean sameAs(Head other) {
            return other != null && session.equals(other.session) && revision == other.revision;
        }
    }

    static final class Task {
        final long generation;
        final long deadline;
        private Head target;

        private Task(long generation, long deadline) {
            this.generation = generation;
            this.deadline = deadline;
        }
    }

    IslandRulePublisher(LongSupplier clock) { this.clock = clock; }

    synchronized Task begin() {
        expire();
        if (!cache.current().loaded) cache.invalidate();
        latest = new Task(cache.current().generation, clock.getAsLong() + ATTEMPT_MS);
        return latest;
    }

    synchronized void invalidate() {
        latest = null;
        applied = null;
        expires = 0;
        cache.invalidate();
    }

    synchronized boolean target(Task task, Head head) {
        if (!live(task) || task.target != null || head == null || head.revision <= 0) return false;
        task.target = head;
        return true;
    }

    IslandRuleSetLoader.Snapshot load(Task task, long revision, List<IslandRuleSetLoader.Source> sources) {
        return loader.load(task.generation, revision, sources);
    }

    synchronized boolean finish(Task task, Head confirmed, IslandRuleSetLoader.Snapshot candidate) {
        if (!live(task) || task.target == null || !task.target.sameAs(confirmed)
                || candidate == null || candidate.ruleSet.revision != confirmed.revision) return false;
        boolean sameHead = applied != null && applied.sameAs(confirmed);
        if (!cache.publishVerified(task.generation, candidate, sameHead)) return false;
        latest = null;
        applied = confirmed;
        expires = clock.getAsLong() + LEASE_MS;
        return true;
    }

    synchronized void fail(Task task) {
        if (task == latest) latest = null;
    }

    synchronized IslandRuleSetLoader.Snapshot current() {
        expire();
        return cache.current();
    }

    synchronized Head applied() {
        expire();
        return applied;
    }

    static final class Applied {
        final Head head;
        final long expires;

        Applied(Head head, long expires) {
            this.head = head;
            this.expires = expires;
        }
    }

    synchronized Applied status() {
        expire();
        return new Applied(applied, expires);
    }

    private boolean live(Task task) {
        return task != null && task == latest && clock.getAsLong() < task.deadline;
    }

    private void expire() {
        if (applied != null && clock.getAsLong() >= expires) invalidate();
    }
}
