package com.rush.rushaicodemother.monitor;

import cn.hutool.core.util.StrUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 生成编排指标收集器。
 */
@Component
@RequiredArgsConstructor
public class GenerationOrchestrationMetricsCollector {

    private final MeterRegistry meterRegistry;

    private final ConcurrentMap<String, Counter> runCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> totalTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> nodeTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DistributionSummary> contextSummaries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> buildFixCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> patchFirstCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> qualityGateCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> rollbackPlanCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> rollbackRestoreCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> commitCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> patchResultCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> patchApplyCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> autoRepairCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> runtimeValidationCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> stageAdmissionCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> userWaitTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> firstPreviewTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> slaOutcomeCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> streamSnapshotWriteCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> streamSnapshotWriteTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> toolLoopGuardCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> agentProductivityCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> checkpointCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> checkpointTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> completionCheckpointCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> completionCheckpointTimers = new ConcurrentHashMap<>();

    /**
 * 记录{@code Run}相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param status 目标状态
 */
    public void recordRun(String orchestrationMode, String status) {
        String mode = normalize(orchestrationMode);
        String normalizedStatus = normalize(status);
        String key = String.join(":", mode, normalizedStatus);
        Counter counter = runCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_orchestration_runs_total")
                        .description("代码生成编排执行次数")
                        .tag("orchestration_mode", mode)
                        .tag("status", normalizedStatus)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    /**
 * 记录总量时长相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param targetType 目标类型
 * @param status 目标状态
 * @param duration 目标时长
 */
    public void recordTotalDuration(String orchestrationMode, String targetType, String status, Duration duration) {
        String mode = normalize(orchestrationMode);
        String type = normalize(targetType);
        String normalizedStatus = normalize(status);
        String key = String.join(":", mode, type, normalizedStatus);
        Timer timer = totalTimers.computeIfAbsent(key, unused ->
                Timer.builder("generation_orchestration_duration_seconds")
                        .description("代码生成编排总耗时")
                        .tag("orchestration_mode", mode)
                        .tag("target_type", type)
                        .tag("status", normalizedStatus)
                        .register(meterRegistry)
        );
        timer.record(nonNegative(duration));
    }

    /**
 * 记录节点时长相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param dagNode {@code dagNode} 对应的调用参数
 * @param stage 阶段
 * @param status 目标状态
 * @param duration 目标时长
 */
    public void recordNodeDuration(String orchestrationMode,
                                   String dagNode,
                                   String stage,
                                   String status,
                                   Duration duration) {
        String mode = normalize(orchestrationMode);
        String node = normalize(dagNode);
        String nodeStage = normalize(stage);
        String normalizedStatus = normalize(status);
        String key = String.join(":", mode, node, nodeStage, normalizedStatus);
        Timer timer = nodeTimers.computeIfAbsent(key, unused ->
                Timer.builder("generation_orchestration_node_duration_seconds")
                        .description("代码生成 DAG 节点耗时")
                        .tag("orchestration_mode", mode)
                        .tag("dag_node", node)
                        .tag("stage", nodeStage)
                        .tag("status", normalizedStatus)
                        .register(meterRegistry)
        );
        timer.record(nonNegative(duration));
    }

    public void recordContextSnapshot(String orchestrationMode,
                                      String contextMode,
                                      int selectedFileCount,
                                      int indexedFileCount,
                                      int contextChars) {
        recordContextSnapshot(orchestrationMode, contextMode, selectedFileCount, indexedFileCount, 0, 0, contextChars);
    }

    /**
 * 记录上下文快照相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param contextMode 上下文模式
 * @param selectedFileCount {@code selectedFileCount} 对应的调用参数
 * @param indexedFileCount {@code indexedFileCount} 对应的调用参数
 * @param indexedSymbolCount {@code indexedSymbolCount} 对应的调用参数
 * @param indexHitCount 索引命中数量
 * @param contextChars 待处理的 {@code contextChars} 集合
 */
    public void recordContextSnapshot(String orchestrationMode,
                                      String contextMode,
                                      int selectedFileCount,
                                      int indexedFileCount,
                                      int indexedSymbolCount,
                                      int indexHitCount,
                                      int contextChars) {
        String mode = normalize(orchestrationMode);
        String normalizedContextMode = normalize(contextMode);
        recordSummary("generation_orchestration_selected_files",
                "代码生成单次精选文件数",
                mode,
                normalizedContextMode,
                Math.max(0, selectedFileCount));
        recordSummary("generation_orchestration_indexed_files",
                "代码生成单次索引文件数",
                mode,
                normalizedContextMode,
                Math.max(0, indexedFileCount));
        recordSummary("generation_orchestration_indexed_symbols",
                "代码生成单次索引符号数",
                mode,
                normalizedContextMode,
                Math.max(0, indexedSymbolCount));
        recordSummary("generation_orchestration_index_hits",
                "代码生成单次索引命中数",
                mode,
                normalizedContextMode,
                Math.max(0, indexHitCount));
        recordSummary("generation_orchestration_context_chars",
                "代码生成单次上下文字符数",
                mode,
                normalizedContextMode,
                Math.max(0, contextChars));
    }

    /**
 * 记录构建{@code Fix}计划相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param enabled 是否启用
 */
    public void recordBuildFixPlan(String orchestrationMode, boolean enabled) {
        String mode = normalize(orchestrationMode);
        String enabledTag = String.valueOf(enabled);
        String key = String.join(":", mode, enabledTag);
        Counter counter = buildFixCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_orchestration_buildfix_total")
                        .description("代码生成 BuildFix 门禁启用次数")
                        .tag("orchestration_mode", mode)
                        .tag("enabled", enabledTag)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    /**
 * 记录补丁{@code First}计划相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param enabled 是否启用
 */
    public void recordPatchFirstPlan(String orchestrationMode, boolean enabled) {
        String mode = normalize(orchestrationMode);
        String enabledTag = String.valueOf(enabled);
        String key = String.join(":", mode, enabledTag);
        Counter counter = patchFirstCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_orchestration_patch_first_total")
                        .description("代码生成 patch-first 模式次数")
                        .tag("orchestration_mode", mode)
                        .tag("enabled", enabledTag)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    /**
 * 记录质量门禁相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param passed {@code passed} 对应的调用参数
 * @param level {@code level} 对应的调用参数
 */
    public void recordQualityGate(String orchestrationMode, boolean passed, String level) {
        String mode = normalize(orchestrationMode);
        String passedTag = String.valueOf(passed);
        String normalizedLevel = normalize(level);
        String key = String.join(":", mode, passedTag, normalizedLevel);
        Counter counter = qualityGateCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_orchestration_quality_gate_total")
                        .description("代码生成质量门禁结果次数")
                        .tag("orchestration_mode", mode)
                        .tag("passed", passedTag)
                        .tag("level", normalizedLevel)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    /**
 * 记录回滚计划相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param rollbackStrategy {@code rollbackStrategy} 对应的调用参数
 */
    public void recordRollbackPlan(String orchestrationMode, String rollbackStrategy) {
        String mode = normalize(orchestrationMode);
        String strategy = normalize(rollbackStrategy);
        String key = String.join(":", mode, strategy);
        Counter counter = rollbackPlanCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_orchestration_rollback_plan_total")
                        .description("代码生成回滚策略计划次数")
                        .tag("orchestration_mode", mode)
                        .tag("rollback_strategy", strategy)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    /**
 * 记录回滚恢复相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param status 目标状态
 * @param reason 原因
 */
    public void recordRollbackRestore(String orchestrationMode, String status, String reason) {
        String mode = normalize(orchestrationMode);
        String normalizedStatus = normalize(status);
        String normalizedReason = normalize(reason);
        String key = String.join(":", mode, normalizedStatus, normalizedReason);
        Counter counter = rollbackRestoreCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_orchestration_rollback_restore_total")
                        .description("代码生成失败后本地快照恢复次数")
                        .tag("orchestration_mode", mode)
                        .tag("status", normalizedStatus)
                        .tag("reason", normalizedReason)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    /**
 * 记录生成提交相关指标或状态。
 *
 * @param provider 提供方
 * @param status 目标状态
 * @param reason 原因
 */
    public void recordGenerationCommit(String provider, String status, String reason) {
        String normalizedProvider = normalize(provider);
        String normalizedStatus = normalize(status);
        String normalizedReason = normalize(reason);
        String key = String.join(":", normalizedProvider, normalizedStatus, normalizedReason);
        Counter counter = commitCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_orchestration_commit_total")
                        .description("代码生成结果本地 Git 提交次数")
                        .tag("provider", normalizedProvider)
                        .tag("status", normalizedStatus)
                        .tag("reason", normalizedReason)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    /**
 * 记录补丁结果相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param status 目标状态
 * @param reason 原因
 */
    public void recordPatchResult(String orchestrationMode, String status, String reason) {
        String mode = normalize(orchestrationMode);
        String normalizedStatus = normalize(status);
        String normalizedReason = normalize(reason);
        String key = String.join(":", mode, normalizedStatus, normalizedReason);
        Counter counter = patchResultCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_orchestration_patch_result_total")
                        .description("代码生成 patch-first 实际落盘结果次数")
                        .tag("orchestration_mode", mode)
                        .tag("status", normalizedStatus)
                        .tag("reason", normalizedReason)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    /**
 * 记录补丁{@code Apply}相关指标或状态。
 *
 * @param provider 提供方
 * @param status 目标状态
 * @param reason 原因
 */
    public void recordPatchApply(String provider, String status, String reason) {
        String normalizedProvider = normalize(provider);
        String normalizedStatus = normalize(status);
        String normalizedReason = normalize(reason);
        String key = String.join(":", normalizedProvider, normalizedStatus, normalizedReason);
        Counter counter = patchApplyCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_orchestration_patch_apply_total")
                        .description("代码生成补丁执行器落盘结果次数")
                        .tag("provider", normalizedProvider)
                        .tag("status", normalizedStatus)
                        .tag("reason", normalizedReason)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    /**
 * 记录{@code Auto}{@code Repair}相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param stage 阶段
 * @param status 目标状态
 */
    public void recordAutoRepair(String orchestrationMode, String stage, String status) {
        String mode = normalize(orchestrationMode);
        String normalizedStage = normalize(stage);
        String normalizedStatus = normalize(status);
        String key = String.join(":", mode, normalizedStage, normalizedStatus);
        Counter counter = autoRepairCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_orchestration_auto_repair_total")
                        .description("代码生成自动修复尝试和结果次数")
                        .tag("orchestration_mode", mode)
                        .tag("stage", normalizedStage)
                        .tag("status", normalizedStatus)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    /**
 * 记录运行时校验相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param targetType 目标类型
 * @param status 目标状态
 */
    public void recordRuntimeValidation(String orchestrationMode, String targetType, String status) {
        String mode = normalize(orchestrationMode);
        String type = normalize(targetType);
        String normalizedStatus = normalize(status);
        String key = String.join(":", mode, type, normalizedStatus);
        Counter counter = runtimeValidationCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_orchestration_runtime_validation_total")
                        .description("代码生成结果运行时验收次数")
                        .tag("orchestration_mode", mode)
                        .tag("target_type", type)
                        .tag("status", normalizedStatus)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    /**
 * 记录阶段准入相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param stage 阶段
 * @param outcome 结果
 */
    public void recordStageAdmission(String orchestrationMode, String stage, String outcome) {
        String mode = normalize(orchestrationMode);
        String normalizedStage = normalize(stage);
        String normalizedOutcome = normalize(outcome);
        String key = String.join(":", mode, normalizedStage, normalizedOutcome);
        Counter counter = stageAdmissionCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_stage_admission_total")
                        .description("Generation stages admitted, rejected or skipped by remaining-time policy")
                        .tag("orchestration_mode", mode)
                        .tag("stage", normalizedStage)
                        .tag("outcome", normalizedOutcome)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    /**
 * 记录用户{@code Wait}时长相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param targetType 目标类型
 * @param status 目标状态
 * @param duration 目标时长
 */
    public void recordUserWaitDuration(String orchestrationMode, String targetType, String status, Duration duration) {
        String mode = normalize(orchestrationMode);
        String type = normalize(targetType);
        String normalizedStatus = normalize(status);
        String key = String.join(":", mode, type, normalizedStatus);
        Timer timer = userWaitTimers.computeIfAbsent(key, unused ->
                Timer.builder("generation_orchestration_user_wait_duration_seconds")
                        .description("用户从提交生成到任务结束的等待耗时")
                        .tag("orchestration_mode", mode)
                        .tag("target_type", type)
                        .tag("status", normalizedStatus)
                        .register(meterRegistry)
        );
        timer.record(nonNegative(duration));
    }

    /**
 * 记录{@code First}预览时长相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param targetType 目标类型
 * @param status 目标状态
 * @param duration 目标时长
 */
    public void recordFirstPreviewDuration(String orchestrationMode,
                                           String targetType,
                                           String status,
                                           Duration duration) {
        String mode = normalize(orchestrationMode);
        String type = normalize(targetType);
        String normalizedStatus = normalize(status);
        String key = String.join(":", mode, type, normalizedStatus);
        Timer timer = firstPreviewTimers.computeIfAbsent(key, unused ->
                Timer.builder("generation_time_to_first_preview_seconds")
                        .description("Time from durable submission until the first usable preview")
                        .tag("orchestration_mode", mode)
                        .tag("target_type", type)
                        .tag("sla_status", normalizedStatus)
                        .register(meterRegistry)
        );
        timer.record(nonNegative(duration));
    }

    /**
 * 记录{@code Sla}结果相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param milestone {@code milestone} 对应的调用参数
 * @param status 目标状态
 * @param reason 原因
 */
    public void recordSlaOutcome(String orchestrationMode,
                                 String milestone,
                                 String status,
                                 String reason) {
        String mode = normalize(orchestrationMode);
        String normalizedMilestone = normalize(milestone);
        String normalizedStatus = normalize(status);
        String normalizedReason = normalize(reason);
        String key = String.join(":", mode, normalizedMilestone, normalizedStatus, normalizedReason);
        Counter counter = slaOutcomeCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_sla_outcomes_total")
                        .description("Generation SLA milestone outcomes")
                        .tag("orchestration_mode", mode)
                        .tag("milestone", normalizedMilestone)
                        .tag("status", normalizedStatus)
                        .tag("reason", normalizedReason)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    /**
 * 记录流快照{@code Write}相关指标或状态。
 *
 * @param outcome 结果
 * @param duration 目标时长
 */
    public void recordStreamSnapshotWrite(String outcome, Duration duration) {
        String normalizedOutcome = normalize(outcome);
        streamSnapshotWriteCounters.computeIfAbsent(normalizedOutcome, unused ->
                Counter.builder("generation_stream_snapshot_writes_total")
                        .description("生成流式断线快照写入次数")
                        .tag("outcome", normalizedOutcome)
                        .register(meterRegistry)
        ).increment();
        streamSnapshotWriteTimers.computeIfAbsent(normalizedOutcome, unused ->
                Timer.builder("generation_stream_snapshot_write_duration_seconds")
                        .description("生成流式断线快照写入耗时")
                        .tag("outcome", normalizedOutcome)
                        .register(meterRegistry)
        ).record(nonNegative(duration));
    }

    /**
 * 记录工具循环防护相关指标或状态。
 *
 * @param reason 原因
 * @param toolName 工具名称
 */
    public void recordToolLoopGuard(String reason, String toolName) {
        String normalizedReason = normalize(reason);
        String normalizedToolName = normalize(toolName);
        String key = String.join(":", normalizedReason, normalizedToolName);
        Counter counter = toolLoopGuardCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_ai_tool_loop_guard_total")
                        .description("AI 工具重复调用或无进展循环拦截次数")
                        .tag("reason", normalizedReason)
                        .tag("tool", normalizedToolName)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    /**
 * 记录智能体{@code Productivity}{@code Intervention}相关指标或状态。
 *
 * @param action 动作
 * @param reason 原因
 */
    public void recordAgentProductivityIntervention(String action, String reason) {
        String normalizedAction = normalize(action);
        String normalizedReason = normalize(reason);
        String key = String.join(":", normalizedAction, normalizedReason);
        agentProductivityCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_ai_agent_productivity_interventions_total")
                        .description("AI Agent 低生产率模型回合的收敛干预次数")
                        .tag("action", normalizedAction)
                        .tag("reason", normalizedReason)
                        .register(meterRegistry)
        ).increment();
    }

    /**
 * 记录节点开始检查点相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param dagNode {@code dagNode} 对应的调用参数
 * @param outcome 结果
 * @param duration 目标时长
 */
    public void recordNodeStartCheckpoint(String orchestrationMode,
                                          String dagNode,
                                          String outcome,
                                          Duration duration) {
        String mode = normalize(orchestrationMode);
        String node = normalize(dagNode);
        String normalizedOutcome = normalize(outcome);
        String key = String.join(":", mode, node, normalizedOutcome);
        checkpointCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_orchestration_node_start_checkpoints_total")
                        .description("生成编排节点开始检查点的持久化、省略或失败次数")
                        .tag("orchestration_mode", mode)
                        .tag("dag_node", node)
                        .tag("outcome", normalizedOutcome)
                        .register(meterRegistry)
        ).increment();
        checkpointTimers.computeIfAbsent(key, unused ->
                Timer.builder("generation_orchestration_node_start_checkpoint_duration_seconds")
                        .description("生成编排节点开始检查点处理耗时")
                        .tag("orchestration_mode", mode)
                        .tag("dag_node", node)
                        .tag("outcome", normalizedOutcome)
                        .register(meterRegistry)
        ).record(nonNegative(duration));
    }

    /**
 * 记录节点完成检查点相关指标或状态。
 *
 * @param orchestrationMode 编排模式
 * @param dagNode {@code dagNode} 对应的调用参数
 * @param outcome 结果
 * @param duration 目标时长
 */
    public void recordNodeCompletionCheckpoint(String orchestrationMode,
                                               String dagNode,
                                               String outcome,
                                               Duration duration) {
        String mode = normalize(orchestrationMode);
        String node = normalize(dagNode);
        String normalizedOutcome = normalize(outcome);
        String key = String.join(":", mode, node, normalizedOutcome);
        completionCheckpointCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_orchestration_node_completion_checkpoints_total")
                        .description("生成编排节点完成检查点的持久化、合并或失败次数")
                        .tag("orchestration_mode", mode)
                        .tag("dag_node", node)
                        .tag("outcome", normalizedOutcome)
                        .register(meterRegistry)
        ).increment();
        completionCheckpointTimers.computeIfAbsent(key, unused ->
                Timer.builder("generation_orchestration_node_completion_checkpoint_duration_seconds")
                        .description("生成编排节点完成检查点处理耗时")
                        .tag("orchestration_mode", mode)
                        .tag("dag_node", node)
                        .tag("outcome", normalizedOutcome)
                        .register(meterRegistry)
        ).record(nonNegative(duration));
    }

    private void recordSummary(String name,
                               String description,
                               String orchestrationMode,
                               String contextMode,
                               double amount) {
        String key = String.join(":", name, orchestrationMode, contextMode);
        DistributionSummary summary = contextSummaries.computeIfAbsent(key, unused ->
                DistributionSummary.builder(name)
                        .description(description)
                        .tag("orchestration_mode", orchestrationMode)
                        .tag("context_mode", contextMode)
                        .register(meterRegistry)
        );
        summary.record(amount);
    }

    private Duration nonNegative(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return Duration.ZERO;
        }
        return duration;
    }

    private String normalize(String value) {
        String normalized = StrUtil.blankToDefault(value, "unknown")
                .trim()
                .toLowerCase()
                .replaceAll("\\s+", "_");
        return StrUtil.blankToDefault(normalized, "unknown");
    }
}
