/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 一条规则的不可变声明；不保留原始 JSON 或 x-* 注解。 */
public final class IslandRule {
    public final int ruleVersion;
    public final Metadata meta;
    public final List<MatchClause> match;
    public final List<ExtractSpec> extract;
    public final DisplaySpec display;

    IslandRule(int ruleVersion, Metadata meta, List<MatchClause> match,
            List<ExtractSpec> extract, DisplaySpec display) {
        this.ruleVersion = ruleVersion;
        this.meta = Objects.requireNonNull(meta, "meta");
        this.match = immutableList(match, "match");
        this.extract = immutableList(extract, "extract");
        this.display = Objects.requireNonNull(display, "display");
    }

    static <T> List<T> immutableList(List<T> source, String name) {
        Objects.requireNonNull(source, name);
        ArrayList<T> copy = new ArrayList<>(source.size());
        for (T item : source) copy.add(Objects.requireNonNull(item, name + " item"));
        return Collections.unmodifiableList(copy);
    }

    /** 适配声明和说明信息；未确认的数据保持 null，不成为运行期匹配字段。 */
    public static final class Metadata {
        public final String packageName;
        public final String focusNotificationName;
        public final String notificationTag;
        public final SystemUiVersion systemUiVersion;
        public final String hyperOsVersion;
        public final String deviceModel;
        public final String deviceCodename;
        public final Integer protocolVersion;
        public final String business;
        public final String templateType;
        public final String notificationChannelId;
        public final String notificationCategory;
        public final String ruleAuthor;
        public final String ruleDescription;
        public final String createdAt;

        Metadata(String packageName, String focusNotificationName, String notificationTag,
                SystemUiVersion systemUiVersion, String hyperOsVersion, String deviceModel,
                String deviceCodename, Integer protocolVersion, String business,
                String templateType, String notificationChannelId, String notificationCategory,
                String ruleAuthor, String ruleDescription, String createdAt) {
            this.packageName = Objects.requireNonNull(packageName, "packageName");
            this.focusNotificationName = focusNotificationName;
            this.notificationTag = notificationTag;
            this.systemUiVersion = systemUiVersion;
            this.hyperOsVersion = hyperOsVersion;
            this.deviceModel = deviceModel;
            this.deviceCodename = deviceCodename;
            this.protocolVersion = protocolVersion;
            this.business = business;
            this.templateType = Objects.requireNonNull(templateType, "templateType");
            this.notificationChannelId = notificationChannelId;
            this.notificationCategory = notificationCategory;
            this.ruleAuthor = Objects.requireNonNull(ruleAuthor, "ruleAuthor");
            this.ruleDescription = Objects.requireNonNull(ruleDescription, "ruleDescription");
            this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public static final class SystemUiVersion {
        public final String name;
        public final long code;

        SystemUiVersion(String name, long code) {
            this.name = Objects.requireNonNull(name, "name");
            this.code = code;
        }
    }
}
