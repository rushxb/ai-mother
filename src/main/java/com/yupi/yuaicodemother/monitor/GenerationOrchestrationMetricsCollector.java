package com.yupi.yuaicodemother.monitor;

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
    private final ConcurrentMap<String, Counter> patchResultCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> patchApplyCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> autoRepairCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> userWaitTimers = new ConcurrentHashMap<>();

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
        recordSummary("generation_orchestration_context_chars",
                "代码生成单次上下文字符数",
                mode,
                normalizedContextMode,
                Math.max(0, contextChars));
    }

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
