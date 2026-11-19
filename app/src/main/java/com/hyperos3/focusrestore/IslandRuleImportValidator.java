/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** App 导入校验。适配证据仅来自本机包信息，不使用通知样本或运行规则。 */
final class IslandRuleImportValidator {
    enum Stage { L1, L3, L2 }
    static final class Environment {
        final String systemUiName;
        final Long systemUiCode;

        Environment(String systemUiName, Long systemUiCode) {
            this.systemUiName = systemUiName;
            this.systemUiCode = systemUiCode;
        }

        boolean sameAs(Environment other) {
            return other != null && Objects.equals(systemUiName, other.systemUiName)
                    && Objects.equals(systemUiCode, other.systemUiCode);
        }
    }

    static final class Finding {
        final Stage stage;
        final String code;
        final String path;
        final String reason;
        final boolean blocking;

        Finding(Stage stage, String code, String path, String reason, boolean blocking) {
            this.stage = stage;
            this.code = code;
            this.path = path;
            this.reason = reason;
            this.blocking = blocking;
        }
    }

    static final class Result {
        final IslandRule rule;
        final List<Finding> findings;

        Result(IslandRule rule, List<Finding> findings) {
            this.rule = rule;
            this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
        }

        boolean canApprove(boolean force) {
            if (rule == null) return false;
            return findings.stream().noneMatch(f -> f.blocking && (!force || f.stage != Stage.L2));
        }
    }

    Result validate(byte[] original, Environment environment) {
        List<Finding> findings = new ArrayList<>();
        IslandRuleCodec.DecodeResult decoded = new IslandRuleCodec().decode(original);
        if (!decoded.isSuccess()) {
            // 解码路径可包含外部键名，仅显示固定字段/阶段，不回显输入。
            findings.add(new Finding(Stage.L1, decoded.error.code.name(), "$", "RULE_STRUCTURE", true));
            return new Result(null, findings);
        }
        IslandRule rule = decoded.rule;
        for (IslandRuleMatchValidator.Problem problem : new IslandRuleMatchValidator().validate(rule)) {
            findings.add(new Finding(Stage.L3, problem.code.name(), problem.path, "OPERATOR_TYPE", true));
        }
        IslandRuleExtractor.Problem problem = IslandRuleExtractor.validateImportModel(rule);
        if (problem != null) {
            findings.add(new Finding(Stage.L3, problem.code.name(), problem.path, problem.reason, true));
        }
        if (!findings.isEmpty()) return new Result(rule, findings);
        IslandRule.Metadata meta = rule.meta;
        if (meta.systemUiVersion != null) {
            if (environment == null || environment.systemUiName == null || environment.systemUiCode == null) {
                unverifiable(findings, "systemUiVersion", "SYSTEM_UI_VERSION_UNKNOWN");
            } else if (!meta.systemUiVersion.name.equals(environment.systemUiName)
                    || meta.systemUiVersion.code != environment.systemUiCode) {
                findings.add(new Finding(Stage.L2, "L2_ENVIRONMENT", "$.meta.systemUiVersion",
                        "SYSTEM_UI_VERSION_MISMATCH", true));
            }
        }
        check(findings, "notificationTag", meta.notificationTag);
        check(findings, "hyperOsVersion", meta.hyperOsVersion);
        check(findings, "deviceModel", meta.deviceModel);
        check(findings, "deviceCodename", meta.deviceCodename);
        check(findings, "protocolVersion", meta.protocolVersion);
        check(findings, "business", meta.business);
        check(findings, "notificationChannelId", meta.notificationChannelId);
        check(findings, "notificationCategory", meta.notificationCategory);
        return new Result(rule, findings);
    }

    private static void check(List<Finding> findings, String field, Object value) {
        if (value != null) unverifiable(findings, field, "NO_COMPATIBILITY_EVIDENCE");
    }

    private static void unverifiable(List<Finding> findings, String field, String reason) {
        findings.add(new Finding(Stage.L2, "L2_UNVERIFIABLE", "$.meta." + field, reason, false));
    }
}
