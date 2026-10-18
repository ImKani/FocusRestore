/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class FocusTimerTickerTest {
    private static final class Host implements FocusTimerTicker.Scheduler, FocusTimerTicker.Target {
        long wall = 110000L;
        long elapsed = 5000L;
        boolean current = true;
        List<Runnable> pending = new ArrayList<>();
        String text;
        int writes;
        @Override public long wallNow() { return wall; }
        @Override public long elapsedNow() { return elapsed; }
        @Override public void postDelayed(Runnable task, long delay) {
            assertEquals(1000L, delay);
            pending.add(task);
        }
        @Override public void remove(Runnable task) { pending.remove(task); }
        @Override public boolean isCurrent() { return current; }
        @Override public void show(String text) { this.text = text; writes++; }
        void advance(long millis) {
            elapsed += millis;
            for (Runnable task : new ArrayList<>(pending)) task.run();
        }
        FocusTimerTicker bind(int type, long when, long currentTime) {
            IslandClockTimer timer = IslandClockTimer.parse(IslandClockTimerTest.CLOCK,
                    IslandClockTimerTest.payload(type, when, currentTime));
            return new FocusTimerTicker(timer, this, this);
        }
    }

    @Test public void runningTimerTicksAndRepeatedRenderKeepsOneCallback() {
        Host host = new Host();
        FocusTimerTicker ticker = host.bind(1, 100000L, 100000L);
        ticker.setVisible(true);
        assertEquals("00:10", host.text);
        host.advance(2000L);
        assertEquals("00:12", host.text);
        ticker.render();
        assertEquals(1, host.pending.size());
    }

    @Test public void pauseResumeAndResetReplaceTheOldBinding() {
        Host host = new Host();
        FocusTimerTicker running = host.bind(1, 100000L, 100000L);
        running.setVisible(true);
        running.close();
        FocusTimerTicker paused = host.bind(2, 100000L, 112345L);
        paused.setVisible(true);
        host.advance(20000L);
        assertEquals("00:12", host.text);
        assertTrue(host.pending.isEmpty());
        paused.close();
        host.wall = 130000L;
        FocusTimerTicker resumed = host.bind(1, 117655L, 130000L);
        resumed.setVisible(true);
        host.advance(1000L);
        assertEquals("00:13", host.text);
        resumed.close();
        FocusTimerTicker reset = host.bind(1, 130000L, 130000L);
        reset.setVisible(true);
        assertEquals("00:00", host.text);
    }

    @Test public void hiddenOrDetachedTimerStopsCallbacksAndReattachCatchesUp() {
        Host host = new Host();
        FocusTimerTicker ticker = host.bind(1, 100000L, 100000L);
        ticker.setVisible(true);
        ticker.setVisible(false);
        host.advance(30000L);
        assertEquals("00:10", host.text);
        assertTrue(host.pending.isEmpty());
        ticker.setVisible(true);
        assertEquals("00:40", host.text);
        assertEquals(1, host.pending.size());
    }

    @Test public void removedReusedOrDisabledTargetCannotBeWrittenByQueuedTick() {
        Host host = new Host();
        FocusTimerTicker ticker = host.bind(1, 100000L, 100000L);
        ticker.setVisible(true);
        Runnable stale = host.pending.get(0);
        host.current = false;
        int before = host.writes;
        stale.run();
        host.current = true;
        stale.run();
        ticker.setVisible(true);
        assertEquals(before, host.writes);
        assertTrue(host.pending.isEmpty());
    }

    @Test public void closeIsIdempotentAndOldTickCannotOverwriteNewGeneration() {
        Host host = new Host();
        FocusTimerTicker old = host.bind(1, 100000L, 100000L);
        old.setVisible(true);
        Runnable stale = host.pending.get(0);
        old.close();
        old.close();
        FocusTimerTicker newer = host.bind(1, 110000L, 110000L);
        newer.setVisible(true);
        stale.run();
        assertEquals("00:00", host.text);
        assertEquals(1, host.pending.size());
    }

    @Test public void wallClockChangeDoesNotJumpRunningTimer() {
        Host host = new Host();
        FocusTimerTicker ticker = host.bind(1, 100000L, 100000L);
        ticker.setVisible(true);
        host.wall += 3600000L;
        host.advance(1000L);
        assertEquals("00:11", host.text);
    }

    @Test public void countdownStopsAtZeroAndNeverBecomesNegative() {
        Host host = new Host();
        FocusTimerTicker ticker = host.bind(-1, 111000L, 100000L);
        ticker.setVisible(true);
        assertEquals("00:01", host.text);
        host.advance(2000L);
        assertEquals("00:00", host.text);
        assertTrue(host.pending.isEmpty());
    }

    @Test public void targetClosingDuringWriteDoesNotScheduleAnotherCallback() {
        Host host = new Host();
        FocusTimerTicker[] binding = new FocusTimerTicker[1];
        IslandClockTimer timer = IslandClockTimer.parse(IslandClockTimerTest.CLOCK,
                IslandClockTimerTest.payload(1, 100000L, 100000L));
        binding[0] = new FocusTimerTicker(timer, host, new FocusTimerTicker.Target() {
            @Override public boolean isCurrent() { return true; }
            @Override public void show(String text) { binding[0].close(); }
        });
        binding[0].setVisible(true);
        assertTrue(host.pending.isEmpty());
    }
}
