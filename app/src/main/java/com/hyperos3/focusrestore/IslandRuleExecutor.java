/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import com.hyperos3.focusrestore.IslandRuleSelection.Code;
import com.hyperos3.focusrestore.IslandRuleSelection.Phase;

/** C3-3：只执行已按优先级和声明顺序排列的候选，不调用内置解析器。 */
public final class IslandRuleExecutor {
    private final IslandRuleExtractor extractor = new IslandRuleExtractor();
    private final IslandRulePostProcessor postProcessor = new IslandRulePostProcessor();
    private final IslandRuleBudget.Clock clock;

    public IslandRuleExecutor() { this(System::nanoTime); }

    IslandRuleExecutor(IslandRuleBudget.Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public enum Status { SUCCESS, FALLBACK, CANCELLED }

    public static final class Result {
        public final Status status;
        public final String text;
        public final IslandRuleSet.Entry winningEntry;
        public final List<IslandRuleSelection.Event> events;
        public final long droppedEvents;

        private Result(Status status, String text, IslandRuleSelection diagnostics) {
            this.status = status;
            this.text = text;
            this.winningEntry = diagnostics.selectedEntry;
            this.events = diagnostics.events;
            this.droppedEvents = diagnostics.droppedEvents;
        }
    }

    /** 传入加载快照的 orderedActiveEntries；不复制、不重排，也不读取胜出之后的条目。 */
    public Result execute(List<IslandRuleSet.Entry> orderedActiveEntries, IslandRuleContext context,
            String rawPayload) {
        return execute(orderedActiveEntries, context, rawPayload, IslandRuleBudget.NEVER_CANCELLED);
    }

    /**
     * 取消信号应快速、非阻塞且不抛异常。一旦为 true 应保持为 true，跨线程时须保证可见性。
     * 取消延迟取决于检查点，不保证墙钟上限。调用方仍须在内置回退和发布前复核票据。
     */
    public Result execute(List<IslandRuleSet.Entry> orderedActiveEntries, IslandRuleContext context,
            String rawPayload, BooleanSupplier isCancelled) {
        Objects.requireNonNull(isCancelled, "isCancelled");
        IslandRuleSelection.Collector diagnostics = new IslandRuleSelection.Collector();
        if (isCancelled.getAsBoolean()) return cancelled(diagnostics);
        if (orderedActiveEntries != null && context != null) {
            int count = Math.min(orderedActiveEntries.size(), IslandRuleSet.MAX_ACTIVE_RULES);
            for (int index = 0; index < count; index++) {
                if (isCancelled.getAsBoolean()) return cancelled(diagnostics);
                IslandRuleSet.Entry entry = null;
                Phase phase = Phase.SELECTION;
                String path = "$";
                try {
                    entry = orderedActiveEntries.get(index);
                    IslandRuleBudget budget = entry != null && entry.enabled
                            ? new IslandRuleBudget(clock, isCancelled) : null;
                    phase = Phase.MATCH;
                    path = "$.match";
                    if (!IslandRuleSelector.matchesCandidate(entry, context, index, diagnostics,
                            budget == null ? null : meter(budget))) continue;

                    phase = Phase.PREPARATION;
                    path = "$";
                    IslandRuleExtractor.Preparation prepared = extractor.prepare(rawPayload, budget);
                    if (!prepared.isSuccess()) {
                        addProblem(diagnostics, index, entry.id, phase, prepared.failure);
                        continue;
                    }
                    phase = Phase.EXTRACTION;
                    path = "$.extract";
                    IslandRuleExtractor.Result extracted = extractor.extract(entry.rule, prepared.payload, budget);
                    for (IslandRuleExtractor.Problem omission : extracted.omissions)
                        addProblem(diagnostics, index, entry.id, phase, omission);
                    if (!extracted.isSuccess()) {
                        addProblem(diagnostics, index, entry.id, phase, extracted.failure);
                        continue;
                    }

                    phase = Phase.POST_PROCESS;
                    path = "$.display";
                    IslandRulePostProcessor.Result output = postProcessor.process(extracted, entry.rule.display, budget);
                    if (!output.isSuccess()) {
                        diagnostics.add(index, entry.id, phase, Code.valueOf(output.code.name()), path);
                        continue;
                    }
                    if (isCancelled.getAsBoolean()) return cancelled(diagnostics);
                    // 终态独立于诊断；即使事件缓冲已满，也立即返回正文且不访问后续候选。
                    diagnostics.add(index, entry.id, Phase.EXECUTION, Code.EXECUTED, "$");
                    return new Result(Status.SUCCESS, output.text, diagnostics.finish(entry));
                } catch (IslandRuleBudget.Cancelled cancelled) {
                    return cancelled(diagnostics);
                } catch (IslandRuleBudget.Limit limit) {
                    diagnostics.add(index, entry == null ? null : entry.id, phase,
                            limit.timeout ? Code.R_TIMEOUT : Code.R_BUDGET, path);
                } catch (RuntimeException rejected) {
                    diagnostics.add(index, entry == null ? null : entry.id, phase, Code.R_EXCEPTION, path);
                }
            }
        }
        if (isCancelled.getAsBoolean()) return cancelled(diagnostics);
        diagnostics.add(-1, null, Phase.EXECUTION, Code.FALLBACK, "$");
        return new Result(Status.FALLBACK, null, diagnostics.finish(null));
    }

    private static Result cancelled(IslandRuleSelection.Collector diagnostics) {
        return new Result(Status.CANCELLED, null, diagnostics.finish(null));
    }

    private static IslandRuleSelector.MatchMeter meter(IslandRuleBudget budget) {
        return new IslandRuleSelector.MatchMeter() {
            @Override public void readField() { budget.readField(); }
            @Override public void checkTime() { budget.checkTime(); }
        };
    }

    private static void addProblem(IslandRuleSelection.Collector diagnostics, int index, String ruleId,
            Phase phase, IslandRuleExtractor.Problem problem) {
        diagnostics.add(index, ruleId, phase, Code.valueOf(problem.code.name()), problem.path);
    }
}
