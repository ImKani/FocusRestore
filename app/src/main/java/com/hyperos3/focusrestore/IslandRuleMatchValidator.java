/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** D1 仅检查协议条件的操作类型，不代表完整 L3 或规则运行批准。 */
public final class IslandRuleMatchValidator {
    public static final class Problem {
        public final IslandRuleSelection.Code code;
        public final String path;

        private Problem(int clauseIndex) {
            this.code = IslandRuleSelection.Code.L3_OPERATOR_TYPE;
            this.path = "$.match.all[" + clauseIndex + "].op";
        }
    }

    /** 无上下文依赖；match 的版本、数量边界由选择层防御性拒绝。 */
    public List<Problem> validate(IslandRule rule) {
        if (rule == null || rule.match.isEmpty() || rule.match.size() > 16)
            return Collections.emptyList();
        List<Problem> problems = new ArrayList<>();
        for (int index = 0; index < rule.match.size(); index++) {
            MatchClause clause = rule.match.get(index);
            if ("protocolVersion".equals(clause.field) && !"equals".equals(clause.op)) {
                problems.add(new Problem(index));
            }
        }
        return Collections.unmodifiableList(problems);
    }
}
