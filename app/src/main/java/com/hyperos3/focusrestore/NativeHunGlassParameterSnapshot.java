/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.HashMap;
import java.util.Map;

/** Pure-Java validated cache for one current SystemUI configuration generation. */
final class NativeHunGlassParameterSnapshot {
    static final int PARAMETER_COUNT = 42;
    static final String LIGHT_TYPE = "GLASS";
    static final String DARK_TYPE = "GLASS_HEADS_UP_DARK";

    private final String versionName;
    private final long versionCode;
    private final String configurationKey;
    private final long configurationSequence;
    private final String effectType;
    private final long generation;
    private final float[] parameters;

    private NativeHunGlassParameterSnapshot(String versionName, long versionCode,
                                            String configurationKey, long configurationSequence,
                                            String effectType, long generation, float[] parameters) {
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.configurationKey = configurationKey;
        this.configurationSequence = configurationSequence;
        this.effectType = effectType;
        this.generation = generation;
        this.parameters = parameters.clone();
    }

    String versionName() { return versionName; }
    long versionCode() { return versionCode; }
    String effectType() { return effectType; }
    long generation() { return generation; }
    long configurationSequence() { return configurationSequence; }
    String configurationKey() { return configurationKey; }
    float[] parameters() { return parameters.clone(); }

    static boolean valid(float[] values) {
        if (values == null || values.length != PARAMETER_COUNT) return false;
        boolean nonZero = false;
        for (float value : values) {
            if (Float.isNaN(value) || Float.isInfinite(value)) return false;
            nonZero |= value != 0f;
        }
        return nonZero;
    }

    static boolean supportedType(String effectType) {
        return LIGHT_TYPE.equals(effectType) || DARK_TYPE.equals(effectType);
    }

    /** Holds only immutable scalar metadata and copied primitive arrays, never Android objects. */
    static final class Cache {
        private static final String SYSTEM_UI = "com.android.systemui";
        private static final String VERSION_NAME = "17.03.260226.r";
        private static final long VERSION_CODE = 202602260L;

        private final Map<String, NativeHunGlassParameterSnapshot> snapshots = new HashMap<>();
        private String activeConfigurationKey;
        private long activeConfigurationSequence = Long.MIN_VALUE;
        private long generation;

        synchronized boolean observe(String packageName, String versionName, long versionCode,
                                     String configurationKey, long configurationSequence) {
            if (!matchesProfile(packageName, versionName, versionCode)
                    || configurationKey == null || configurationKey.isEmpty()) {
                invalidateLocked();
                return false;
            }
            if (!configurationKey.equals(activeConfigurationKey)
                    || configurationSequence != activeConfigurationSequence) {
                snapshots.clear();
                activeConfigurationKey = configurationKey;
                activeConfigurationSequence = configurationSequence;
                generation++;
            }
            return true;
        }

        synchronized boolean publish(String packageName, String versionName, long versionCode,
                                     String configurationKey, long configurationSequence,
                                     String effectType, float[] values) {
            if (!valid(values) || !supportedType(effectType)
                    || !observe(packageName, versionName, versionCode,
                    configurationKey, configurationSequence)) return false;
            snapshots.put(effectType, new NativeHunGlassParameterSnapshot(versionName, versionCode,
                    configurationKey, configurationSequence, effectType, generation, values));
            return true;
        }

        synchronized NativeHunGlassParameterSnapshot get(String packageName, String versionName,
                                                           long versionCode, String configurationKey,
                                                           long configurationSequence, String effectType) {
            if (!supportedType(effectType)
                    || !observe(packageName, versionName, versionCode,
                    configurationKey, configurationSequence)) return null;
            NativeHunGlassParameterSnapshot snapshot = snapshots.get(effectType);
            if (snapshot == null || snapshot.generation != generation
                    || snapshot.versionCode != versionCode
                    || !snapshot.versionName.equals(versionName)
                    || snapshot.configurationSequence != configurationSequence
                    || !snapshot.configurationKey.equals(configurationKey)) return null;
            return snapshot;
        }

        synchronized void invalidate() { invalidateLocked(); }

        private boolean matchesProfile(String packageName, String versionName, long versionCode) {
            return SYSTEM_UI.equals(packageName) && VERSION_NAME.equals(versionName)
                    && VERSION_CODE == versionCode;
        }

        private void invalidateLocked() {
            snapshots.clear();
            activeConfigurationKey = null;
            activeConfigurationSequence = Long.MIN_VALUE;
            generation++;
        }
    }
}
