/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.Arrays;

/**
 * 自有背景层的玻璃调用状态，不持有ROM View或注册监听器。
 * 调用合同来源：Xiaomi SystemUI 17.03.260226.r 的NotificationRowGlassEffect、
 * MiGlassCompat及NotificationBackgroundViewInjectorImpl；作者工作区
 * Knowledge/sources/decompiled/notification-material-20261115。独立实现状态与失败回收，
 * 不复制ROM参数或算法；参数由设备当前SystemUI资源提供，视觉仍需实机核验。
 */
final class BannerGlassState {
    static final int PARAMETER_COUNT = 42;

    interface Calls {
        void setParameters(float[] values) throws Exception;
        void setMaterialType(int type) throws Exception;
        void setSize(float width, float height) throws Exception;
    }

    private final Calls calls;
    private boolean parametersTouched, typeTouched, active;
    private float[] appliedParameters;
    private int appliedWidth = -1, appliedHeight = -1;

    BannerGlassState(Calls calls) { this.calls = calls; }

    static float[] parseParameters(String[] values) {
        if (values == null || values.length != PARAMETER_COUNT) {
            throw new IllegalArgumentException("unexpected native glass parameter count");
        }
        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) throw new IllegalArgumentException("null native glass parameter: " + i);
            result[i] = Float.parseFloat(values[i]);
        }
        validate(result);
        return result;
    }

    private static void validate(float[] values) {
        if (values == null || values.length != PARAMETER_COUNT) {
            throw new IllegalArgumentException("unexpected native glass parameter count");
        }
        for (float value : values) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new IllegalArgumentException("non-finite native glass parameter");
            }
        }
    }

    // 装饰层由beforeDraw同步实际尺寸；零尺寸时推迟整段玻璃初始化，避免遗留layout回调。
    boolean apply(float[] parameters, int width, int height) throws Exception {
        validate(parameters);
        if (width <= 0 || height <= 0) return false;
        if (active && Arrays.equals(appliedParameters, parameters)) {
            resize(width, height);
            return true;
        }
        active = false;
        typeTouched = true;
        calls.setMaterialType(0);
        parametersTouched = true;
        calls.setParameters(parameters.clone());
        // 参数或材质重设后即使尺寸相同也重放SDF，然后才启用type1。
        calls.setSize(width, height);
        calls.setMaterialType(1);
        appliedParameters = parameters.clone();
        appliedWidth = width;
        appliedHeight = height;
        active = true;
        return true;
    }

    void resize(int width, int height) throws Exception {
        if (!active || width <= 0 || height <= 0
                || (width == appliedWidth && height == appliedHeight)) return;
        calls.setSize(width, height);
        appliedWidth = width;
        appliedHeight = height;
    }

    boolean isActive() { return active; }

    void clear() throws Exception {
        active = false;
        appliedParameters = null;
        appliedWidth = appliedHeight = -1;
        Exception failure = null;
        // 一项失败仍清另一项；只在调用成功后放弃所有权，下一次clear可重试。
        if (parametersTouched) {
            try {
                calls.setParameters(new float[PARAMETER_COUNT]);
                parametersTouched = false;
            } catch (Exception error) { failure = error; }
        }
        if (typeTouched) {
            try {
                calls.setMaterialType(0);
                typeTouched = false;
            } catch (Exception error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
