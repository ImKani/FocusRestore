/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static com.hyperos3.focusrestore.DeviceChargingEventPolicy.Event.*;

public class DeviceChargingEventPolicyTest {
    @Test public void initialStickyDoesNotStartNotification() {
        assertEquals(INITIAL, new DeviceChargingEventPolicy().observe(1, 2, 0, 0, 11));
    }
    @Test public void connectionAndDisconnectionAreSeparateEvents() {
        DeviceChargingEventPolicy policy = new DeviceChargingEventPolicy();
        policy.observe(0, 3, 0, 0, -1);
        assertEquals(START, policy.observe(1, 2, 0, 0, 11));
        assertEquals(CANCEL, policy.observe(0, 3, 0, 0, -1));
        assertEquals(NONE, policy.observe(0, 3, 0, 0, -1));
    }
    @Test public void repeatedLevelEventsDoNotRestart() {
        DeviceChargingEventPolicy policy = new DeviceChargingEventPolicy();
        policy.observe(1, 2, 1, 30, 11);
        assertEquals(NONE, policy.observe(1, 2, 1, 30, 11));
    }
    @Test public void SpeedUpgradeAndWattageChangeUpdate() {
        DeviceChargingEventPolicy policy = new DeviceChargingEventPolicy();
        policy.observe(1, 2, 0, 0, 11);
        assertEquals(UPDATE, policy.observe(1, 2, 2, 50, 11));
        assertEquals(UPDATE, policy.observe(1, 2, 2, 60, 11));
        assertEquals(NONE, policy.observe(1, 2, 1, 60, 11));
    }
    @Test public void wirelessAndFullBatteryRemainValid() {
        DeviceChargingEventPolicy policy = new DeviceChargingEventPolicy();
        policy.observe(0, 3, 0, 0, -1);
        assertEquals(START, policy.observe(4, 5, 0, 0, 10));
        assertEquals(UPDATE, policy.observe(1, 5, 0, 0, 11));
    }
    @Test public void invalidPlugOrWireStateCancels() {
        DeviceChargingEventPolicy policy = new DeviceChargingEventPolicy();
        policy.observe(1, 2, 0, 0, 11);
        assertEquals(CANCEL, policy.observe(8, 2, 0, 0, 11));
        assertEquals(NONE, policy.observe(1, 2, 0, 0, -1));
    }
}