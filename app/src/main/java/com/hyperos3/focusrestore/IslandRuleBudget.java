/* SPDX-License-Identifier: GPL-3.0-only
 * ImKani / FocusRestore / https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.function.BooleanSupplier;

/** 单规则共享的消耗账本；不创建线程，不重置计数，也不承诺强抢占。 */
public final class IslandRuleBudget {
    public static final int MAX_FIELD_READS = 100;
    public static final int MAX_STRING_OPERATIONS = 10;
    public static final int MAX_DEDUPE_OPERATIONS = 1;
    public static final long TIMEOUT_NANOS = 50000000L;
    public interface Clock { long nanoTime(); }
    static final BooleanSupplier NEVER_CANCELLED = () -> false;
    private final Clock clock;
    private final BooleanSupplier isCancelled;
    private final long started;
    private int reads;
    private int operations;
    private int dedupes;

    static final class Limit extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final boolean timeout;
        final String reason;
        Limit(boolean timeout, String reason) {
            super(null, null, false, false);
            this.timeout = timeout;
            this.reason = reason;
        }
    }

    static final class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private static final Cancelled INSTANCE = new Cancelled();

        private Cancelled() { super(null, null, false, false); }
    }

    public IslandRuleBudget() {
        this(new Clock() { @Override public long nanoTime() { return System.nanoTime(); } });
    }
    public IslandRuleBudget(Clock clock) {
        this(clock, NEVER_CANCELLED);
    }
    IslandRuleBudget(Clock clock, BooleanSupplier isCancelled) {
        if (clock == null) throw new IllegalArgumentException("clock required");
        this.clock = clock;
        this.isCancelled = isCancelled;
        this.started = clock.nanoTime();
    }
    public void checkTime() {
        long elapsed = clock.nanoTime() - started;
        // 同一检查点观察到取消和超时时，优先结束整个执行。
        if (isCancelled.getAsBoolean()) throw Cancelled.INSTANCE;
        if (elapsed >= TIMEOUT_NANOS) throw new Limit(true, "TIME_LIMIT");
    }
    public void readField() {
        checkTime();
        if (reads == MAX_FIELD_READS) throw new Limit(false, "FIELD_READ_LIMIT");
        reads++;
    }
    public void stringOperation() {
        checkTime();
        if (operations == MAX_STRING_OPERATIONS) throw new Limit(false, "STRING_OPERATION_LIMIT");
        operations++;
    }
    public void dedupeOperation() {
        checkTime();
        if (dedupes == MAX_DEDUPE_OPERATIONS) throw new Limit(false, "DEDUPE_LIMIT");
        dedupes++;
    }
    public int fieldReads() { return reads; }
    public int stringOperations() { return operations; }
    public int dedupeOperations() { return dedupes; }
}
