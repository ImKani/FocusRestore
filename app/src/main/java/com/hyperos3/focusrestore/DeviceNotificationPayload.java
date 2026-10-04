/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

/*
 * 接口事实来源：用户提供的 HyperOS SystemUI 16.03.251211.r / K80u 17.03.260226.r，
 * DeviceNotificationListenerImpl.handleDeviceNotification、DeviceNotificationModel 的左右 TextParams /
 * IconParams 与 Bundle.duration；旧 StrongToastModel.statusBarGuideModel / charge / duration / target。
 * 系统接口版权所有者：小米；私有协议许可未确认。仅依据接口独立实现，未复制 ROM 实现。
 */

package com.hyperos3.focusrestore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.json.JSONObject;

/** 与 Android 无关的模型适配层，使缺字段、类型错误及旧模型回退可以在 JVM 覆盖。 */
final class DeviceNotificationPayload {
    interface Logger { void debug(String message); }

    final String text;
    final String iconName;
    final String iconPackage;
    final String iconCategory;
    final String iconFormat;
    final long visibleMs;
    final Object target;
    final String source;
    final String eventId;

    private DeviceNotificationPayload(String text, String iconName, long visibleMs,
                                      Object target, String source, String eventId,
                                      String iconPackage, String iconCategory, String iconFormat) {
        this.text = text;
        this.iconName = iconName;
        this.iconPackage = iconPackage;
        this.iconCategory = iconCategory;
        this.iconFormat = iconFormat;
        this.visibleMs = visibleMs;
        this.target = target;
        this.source = source;
        this.eventId = eventId;
    }

    static DeviceNotificationPayload read(Object model, Map<String, Object> metadata,
                                          boolean os4, Logger logger) {
        Object guide = member(model, "statusBarGuideModel", logger);
        Object content = guide != null ? guide : model;
        String source = guide != null ? "statusBarGuideModel" : "deviceModel";
        String left = partText(content, "left", logger);
        boolean legacyGuide = guide != null || !os4;
        String center = legacyGuide ? partText(content, "center", logger) : null;
        String right = partText(content, "right", logger);
        // OS4 左右都可能带文字（例如充电文案与电量），不能仅保留左侧；旧强提示维持原先首段规则。
        String text = !os4 || guide != null ? first(left, center, right) : combine(left, right);
        if (text == null) {
            text = first(string(member(model, "charge", logger)), string(metadata.get("charge")));
            if (text != null) source = "charge";
        }
        String rightIcon = partIcon(content, "right", logger);
        String leftIcon = partIcon(content, "left", logger);
        String centerIcon = legacyGuide ? partIcon(content, "center", logger) : null;
        String icon = first(rightIcon, leftIcon, centerIcon);
        String iconSide = rightIcon != null ? "right" : leftIcon != null ? "left" : "center";
        Object iconParams = member(member(content, iconSide, logger), "iconParams", logger);
        String iconCategory = string(member(iconParams, "category", logger));
        String iconFormat = string(member(iconParams, "iconFormat", logger));
        String iconPackage = string(metadata.get("package_name"));
        if (iconPackage != null && iconPackage.length() > InputLimits.MAX_PACKAGE_NAME_CHARS) {
            logger.debug("icon-package rejected reason=size-limit");
            iconPackage = null;
        }
        Object modelDuration = member(model, "duration", logger);
        Object bundleDuration = metadata.get("duration");
        // OS4 的时长在监听器 Bundle 中，不在内容模型里；OS3 强提示仍允许模型携带时长。
        Object duration = os4 && bundleDuration != null ? bundleDuration
                : modelDuration != null ? modelDuration : bundleDuration;
        long fallback = os4 ? 2500L : 5000L;
        long visible = duration instanceof Number ? ((Number) duration).longValue() : fallback;
        if (visible <= 0L) visible = fallback;
        Object target = member(model, "target", logger);
        if (target == null) target = metadata.get("target");
        String eventId = string(metadata.get("notifyId"));
        if (eventId == null && "charge".equals(source)) eventId = "charge";
        logger.debug("parse source=" + source + " model=" + type(model)
                + " left=" + DebugText.preview(left) + " right=" + DebugText.preview(right)
                + " text=" + DebugText.preview(text) + " icon=" + icon
                + " modelDuration=" + modelDuration + " bundleDuration=" + bundleDuration
                + " duration=" + visible + " durationSource="
                + (duration == null ? "default" : duration == bundleDuration ? "bundle" : "model")
                + " targetType=" + type(target) + " eventId=" + DebugText.preview(eventId));
        return new DeviceNotificationPayload(InputLimits.limitOutput(text), icon, visible,
                target, source, eventId, iconPackage, iconCategory, iconFormat);
    }

    static DeviceNotificationPayload chargingResource(String romText, int level, boolean wireless) {
        return new DeviceNotificationPayload(InputLimits.limitOutput(romText + " " + level + "%"),
                null, wireless ? 10000L : 5000L, null, "romChargeResource", "charge",
                null, null, null);
    }

    static DeviceNotificationPayload readJson(String json, Map<String, Object> metadata,
                                              boolean os4, Logger logger) {
        if (!InputLimits.isPayloadAllowed(json)) {
            logger.debug("json skipped reason=empty-or-size-limit chars="
                    + (json == null ? 0 : json.length()));
            return null;
        }
        try {
            return read(new JSONObject(json), metadata, os4, logger);
        } catch (Throwable error) {
            logger.debug("json failed chars=" + json.length() + " error=" + error);
            return null;
        }
    }

    private static String partText(Object content, String side, Logger logger) {
        Object part = member(content, side, logger);
        return string(member(member(part, "textParams", logger), "text", logger));
    }

    private static String partIcon(Object content, String side, Logger logger) {
        Object params = member(member(content, side, logger), "iconParams", logger);
        String name = string(member(params, "iconResName", logger));
        if (params != null) {
            logger.debug("icon-model side=" + side + " name=" + name
                    + " format=" + member(params, "iconFormat", logger)
                    + " type=" + member(params, "iconType", logger)
                    + " category=" + member(params, "category", logger));
        }
        return name;
    }

    /** getter 优先、字段兼容旧版本；缺失和实际执行失败分开记录，失败不会阻断 SystemUI。 */
    private static Object member(Object owner, String name, Logger logger) {
        if (owner == null) return null;
        if (owner instanceof JSONObject) {
            Object value = ((JSONObject) owner).opt(name);
            if (value == null || value == JSONObject.NULL) {
                logger.debug("model-read absent owner=JSONObject member=" + name);
                return null;
            }
            return value;
        }
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            Method method = owner.getClass().getMethod(getter);
            method.setAccessible(true);
            return method.invoke(owner);
        } catch (NoSuchMethodException missing) {
            // 旧模型可能只有字段；getter 不存在不是模型错误。
        } catch (Throwable error) {
            logger.debug("model-read failed owner=" + type(owner) + " member=" + getter
                    + " error=" + error);
            return null;
        }
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException missing) {
                // 继续检查继承层，避免遗漏父类的私有协议字段。
            } catch (Throwable error) {
                logger.debug("model-read failed owner=" + type(owner) + " member=" + name
                        + " error=" + error);
                return null;
            }
        }
        logger.debug("model-read absent owner=" + type(owner) + " member=" + name);
        return null;
    }

    private static String string(Object value) {
        if (!(value instanceof CharSequence)) return null;
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static String first(String... choices) {
        for (String choice : choices) if (choice != null) return choice;
        return null;
    }

    private static String combine(String left, String right) {
        if (left == null) return right;
        if (right == null || left.equals(right)) return left;
        return left + " " + right;
    }

    private static String type(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}