/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

/** 电池通知只在接电/快充状态变化时产生，不把每次电量广播都当成一次新提示。 */
final class DeviceChargingEventPolicy {
    enum Event { INITIAL, NONE, START, UPDATE, CANCEL }
    private boolean initialized;
    private boolean connected;
    private int speed;
    private int wattage;
    private int wireState;

    Event observe(int plugged, int status, int nextSpeed, int nextWattage, int nextWireState) {
        // 接口事实：K80u SystemUI 17.03.260226.r BatteryStatus 的有效插电值 1/2/4，状态 2/4/5。
        boolean nextConnected = (plugged == 1 || plugged == 2 || plugged == 4)
                && (status == 2 || status == 4 || status == 5) && nextWireState != -1;
        Event event;
        if (!initialized) event = Event.INITIAL;
        else if (!nextConnected && connected) event = Event.CANCEL;
        else if (nextConnected && !connected) event = Event.START;
        else if (nextConnected && (nextWireState != wireState
                || nextSpeed > speed || nextWattage != wattage)) event = Event.UPDATE;
        else event = Event.NONE;
        initialized = true;
        connected = nextConnected;
        speed = nextSpeed;
        wattage = nextWattage;
        wireState = nextWireState;
        return event;
    }
}