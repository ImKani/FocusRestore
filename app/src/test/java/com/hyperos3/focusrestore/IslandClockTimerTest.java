/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

public class IslandClockTimerTest {
    static final String CLOCK = "com.android.deskclock";

    static String info(int type, long when, long current) {
        return "{\"timerType\":" + type + ",\"timerWhen\":" + when
                + ",\"timerSystemCurrent\":" + current + "}";
    }

    static String payload(int type, long when, long current) {
        return "{\"protocol\":1,\"scene\":\"stopWatch\",\"param_v2\":{\"highlightInfo\":{\"timerInfo\":"
                + info(type, when, current) + "}}}";
    }

    @Test public void realStopwatchUsesNestedStartInsteadOfZeroRoot() {
        String payload = "{\"protocol\":1,\"scene\":\"stopWatch\",\"timerType\":1,"
                + "\"timerWhen\":0,\"timerSystemCurrent\":0,\"param_v2\":{\"highlightInfo\":{"
                + "\"timerInfo\":" + info(1, 1792289034026L, 1792289034026L)
                + ",\"subContent\":\"秒表\"}}}";
        IslandClockTimer timer = IslandClockTimer.parse(CLOCK, payload);
        assertNotNull(timer);
        assertEquals(1792289034026L, timer.when);
        assertEquals(36036L, timer.durationAt(1792289070062L, 0L));
    }

    @Test public void nestedSnapshotWinsEvenWhenRootHasNonzeroStaleTimes() {
        String payload = payload(2, 100000L, 112345L).replace("\"protocol\":1,",
                "\"timerType\":1,\"timerWhen\":50000,\"protocol\":1,");
        IslandClockTimer timer = IslandClockTimer.parse(CLOCK, payload);
        assertEquals(2, timer.type);
        assertEquals(12345L, timer.durationAt(999999L, 60000L));
    }

    @Test public void islandOnlyAndAnimationOnlyTimersAreRead() {
        String timer = info(1, 100000L, 100000L);
        for (String section : new String[]{
                "\"param_island\":{\"bigIslandArea\":{\"sameWidthDigitInfo\":{\"timerInfo\":" + timer + "}}}",
                "\"animTextInfo\":{\"timerInfo\":" + timer + "}"}) {
            assertNotNull(IslandClockTimer.parse(CLOCK,
                    "{\"protocol\":1,\"scene\":\"stopWatch\",\"param_v2\":{" + section + "}}"));
        }
    }

    @Test public void supportedContainersAndRootTimerAreRead() {
        for (String container : new String[]{"param_v2", "param_v3", "param_voip_v2"}) {
            assertNotNull(IslandClockTimer.parse(CLOCK, payload(1, 100000L, 0L)
                    .replace("param_v2", container)));
        }
        assertNotNull(IslandClockTimer.parse(CLOCK,
                "{\"protocol\":1,\"scene\":\"timer\",\"timerType\":-1,\"timerWhen\":200000}"));
    }

    @Test public void unrelatedAppsScenesAndInvalidPayloadsAreNotTakenOver() {
        assertNull(IslandClockTimer.parse("other.clock", payload(1, 100000L, 100000L)));
        assertNull(IslandClockTimer.parse(CLOCK, payload(1, 100000L, 100000L)
                .replace("stopWatch", "alarm")));
        assertNull(IslandClockTimer.parse(CLOCK, payload(1, 100000L, 100000L)
                .replace("\"protocol\":1", "\"protocol\":2")));
        assertNull(IslandClockTimer.parse(CLOCK, null));
        assertNull(IslandClockTimer.parse(CLOCK, "{"));
    }

    @Test public void zeroStartUnknownTypeAndPauseWithoutSnapshotFallBackToText() {
        assertNull(IslandClockTimer.parse(CLOCK, payload(1, 0L, 0L)));
        assertNull(IslandClockTimer.parse(CLOCK, payload(5, 100000L, 100000L)));
        assertNull(IslandClockTimer.parse(CLOCK, payload(0, 100000L, 100000L)));
        assertNull(IslandClockTimer.parse(CLOCK, payload(2, 100000L, 0L)));
        assertNull(IslandClockTimer.parse(CLOCK, payload(-2, 100000L, 0L)));
    }

    @Test public void runningStopwatchAdvancesFromBindTimeNotNotificationSnapshot() {
        IslandClockTimer timer = IslandClockTimer.parse(CLOCK, payload(1, 100000L, 100000L));
        assertEquals(12345L, timer.durationAt(110000L, 2345L));
        assertTrue(timer.running());
    }

    @Test public void pausedStopwatchAndCountdownKeepTheirSnapshot() {
        IslandClockTimer stopwatch = IslandClockTimer.parse(CLOCK, payload(2, 100000L, 112345L));
        IslandClockTimer countdown = IslandClockTimer.parse(CLOCK, payload(-2, 160000L, 112345L));
        assertEquals(12345L, stopwatch.durationAt(500000L, 600000L));
        assertEquals(47655L, countdown.durationAt(500000L, 600000L));
        assertFalse(stopwatch.running());
        assertFalse(countdown.running());
    }

    @Test public void realCountdownClampsAtZero() {
        IslandClockTimer timer = IslandClockTimer.parse(CLOCK,
                payload(-1, 1792297250298L, 1792297189414L));
        assertEquals(60790L, timer.durationAt(1792297189508L, 0L));
        assertEquals(59790L, timer.durationAt(1792297189508L, 1000L));
        assertEquals(0L, timer.durationAt(1792297189508L, 61000L));
    }

    @Test public void hoursAndMinuteTypesUseRomFormats() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            IslandClockTimer seconds = IslandClockTimer.parse(CLOCK, payload(1, 100000L, 100000L));
            assertEquals("00:00", seconds.format(0L));
            assertEquals("00:59", seconds.format(59999L));
            assertEquals("1:01:01", seconds.format(3661000L));
            IslandClockTimer minutes = IslandClockTimer.parse(CLOCK, payload(3, 100000L, 100000L));
            assertEquals("61:01", minutes.format(3661000L));
            assertTrue(minutes.running());
            assertFalse(IslandClockTimer.parse(CLOCK, payload(-4, 100000L, 90000L)).running());
        } finally { Locale.setDefault(previous); }
    }
}
