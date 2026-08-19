package com.rush.rushaicodemother.orchestration.heavy;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTerminalOutcome;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.PatchResult;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommand;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTaskFinalizer;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality;
import com.rush.rushaicodemother.memory.GenerationOutcomeMemoryRequest;
import com.rush.rushaicodemother.memory.GenerationOutcomeMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class HeavyGenerationSessionCompletionService {

    private final GenerationTaskFinalizer generationTaskFinalizer;
    private final GenerationOutcomeMemoryService outcomeMemoryService;
    private final com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalIntentService
            terminalIntentService;

    @Autowired
    public HeavyGenerationSessionCompletionService(
            GenerationTaskFinalizer generationTaskFinalizer,
            GenerationOutcomeMemoryService outcomeMemoryService,
            com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalIntentService
                    terminalIntentService) {
        this.generationTaskFinalizer = generationTaskFinalizer;
        this.outcomeMemoryService = outcomeMemoryService;
        this.terminalIntentService = terminalIntentService;
    }

    /** 遗留单测兼容构造入口。 */
    public HeavyGenerationSessionCompletionService(
            GenerationTaskFinalizer generationTaskFinalizer,
            GenerationOutcomeMemoryService outcomeMemoryService) {
        this(generationTaskFinalizer, outcomeMemoryService, null);
    }

    /**
     * 在调用者自动声明会话完成后保留终端生命周期数据。
     * 流完成和基础设施清理仍然是协调器的责任。
     */
    public void completeClaimed(Long appId,
                                GenerationSession session,
                                GenerationPreparation preparation,
                                GenerationTerminalOutcome outcome) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (session == null || preparation == null) {
            return;
        }
        if (outcome == null) {
            throw new IllegalArgumentException("generation terminal outcome must not be null");
        }
        String status = outcome.status();
        String memorySummary = buildMemorySummary(appId, preparation, status);
        GenerationOutcomeQuality outcomeQuality = resolveOutcomeQuality(appId, preparation, session, outcome);
        GenerationFinalizationCommand command = GenerationFinalizationCommand.of(
                preparation.taskId(),
                appId,
                session.executionContext() == null ? null : session.executionContext().executionFence(),
                outcome.taskStatus(),
                outcome == GenerationTerminalOutcome.SUCCESS ? null : outcome.status(),
                memorySummary,
                outcomeQuality
        );
        generationTaskFinalizer.finalizeManaged(
                terminalIntentService == null ? command : terminalIntentService.preparedOr(command));
        rememberOutcomeSafely(appId, session, preparation, outcome, memorySummary);
    }

    /** Heavy 发布前冻结成功终态；恢复与正常收口复用同一命令。 */
    public GenerationFinalizationCommand publishedSuccessCommand(Long appId,
                                                                 GenerationSession session,
                                                                 GenerationPreparation preparation) {
        GenerationFinalizationCommand command = GenerationFinalizationCommand.of(
                preparation.taskId(), appId,
                session.executionContext() == null ? null : session.executionContext().executionFence(),
                com.rush.rushaicodemother.model.enums.GenerationTaskStatus.SUCCESS,
                null,
                buildMemorySummary(appId, preparation, GenerationTerminalOutcome.SUCCESS.status()),
                resolveOutcomeQuality(appId, preparation, session, GenerationTerminalOutcome.SUCCESS));
        return command;
    }

    /**
 * 返回编排模式。
 *
 * @param preparation {@code preparation} 对应的调用参数
 * @return 处理后的重型生成会话完成文本
 */
    public String orchestrationMode(GenerationPreparation preparation) {
        if (preparation == null || preparation.events() == null) {
            return "unknown";
        }
        return preparation.events().stream()
                .map(GenerationStreamEvent::getData)
                .filter(map -> map != null && map.get("orchestrationMode") != null)
                .map(map -> String.valueOf(map.get("orchestrationMode")))
                .findFirst()
                .orElse("unknown");
    }

    /**
     * 装配 Heavy 路径的 L3 结果质量证据。
     *
     * <p>指标全部来自已沉淀的制品与执行上下文，采集不到即保持 {@code null}（未采集），
     * 不影响交付。</p>
     */
    private GenerationOutcomeQuality resolveOutcomeQuality(Long appId,
                                                           GenerationPreparation preparation,
                                                           GenerationSession session,
                                                           GenerationTerminalOutcome outcome) {
        Integer changedFileCount = resolveChangedFileCount(appId, preparation);
        Integer repairRounds = resolveRepairRounds(preparation);
        Long firstPreviewMillis = resolveFirstPreviewMillis(preparation, session);
        if (outcome != GenerationTerminalOutcome.SUCCESS) {
            // 不用 outcome.status() 充当失败分类：它取值就是 failed/cancelled 等终态，
            // 与 status 列完全重复，写进去只是冗余而非归因信息。Heavy 最终化处拿不到
            // 原始异常，因此失败分类保持未采集，由具备异常上下文的调用点负责。
            return GenerationOutcomeQuality.ofFailure(
                    null, changedFileCount, repairRounds, firstPreviewMillis);
        }
        // 修复轮次未采集时无法判断是否「免修复」通过，保持未采集而不是臆测为 true。
        Boolean firstBuildPassed = repairRounds == null
                ? null
                : repairRounds == 0 && preparation.requiresBuildValidation();
        return GenerationOutcomeQuality.ofSuccess(
                changedFileCount, repairRounds, firstBuildPassed, firstPreviewMillis);
    }

    /** 从 diff 摘要制品统计有效变更文件数。 */
    private Integer resolveChangedFileCount(Long appId, GenerationPreparation preparation) {
        GenerationArtifact artifact = preparation.artifact(DiffSummary.KEY);
        if (artifact == null) {
            return null;
        }
        try {
            DiffSummary summary = DiffSummary.fromArtifact(
                    artifact,
                    appId,
                    preparation.taskId()
            );
            return summary.created() ? summary.changedFileCount() : null;
        } catch (IllegalArgumentException | NullPointerException exception) {
            // 不把损坏或串任务的制品写入质量指标，避免错误数据掩盖真实生成结果。
            return null;
        }
    }

    /**
     * 读取实际修复轮次。
     *
     * <p>Heavy 路径目前没有沉淀「实际发生了几轮修复」的制品：{@code buildfix_plan} 里的
     * {@code repairRounds} 是计划允许的轮次上限，不是实际值，用它归因会把「计划允许 1 轮」
     * 误报成「确实修了 1 轮」。因此这里返回 {@code null}（未采集），等修复循环显式记录轮次
     * 后再接入 —— 宁可缺数据，不要错数据。</p>
     */
    private Integer resolveRepairRounds(GenerationPreparation preparation) {
        return null;
    }

    /**
     * 计算提交到首个可预览版本的耗时（TTP）；未就绪时返回 {@code null}。
     *
     * <p>优先取「暂定可预览」时刻，理由同 {@code GenerationPipelineExecutor}：TTP 度量用户多久看到东西，
     * 只取已验证发布时刻会让该指标恒等于任务总时长。</p>
     */
    private Long resolveFirstPreviewMillis(GenerationPreparation preparation, GenerationSession session) {
        if (session == null || session.executionContext() == null) {
            return null;
        }
        java.time.Instant firstPreviewReadyAt =
                session.executionContext().firstProvisionalPreviewAt() == null
                        ? session.executionContext().firstPreviewReadyAt()
                        : session.executionContext().firstProvisionalPreviewAt();
        java.time.Instant startedAt = session.executionContext().startedAt();
        if (firstPreviewReadyAt == null || startedAt == null) {
            return null;
        }
        long elapsed = java.time.Duration.between(startedAt, firstPreviewReadyAt).toMillis();
        return elapsed < 0 ? null : elapsed;
    }

    /** 构建并返回记忆汇总。 */
    private String buildMemorySummary(Long appId,
                                      GenerationPreparation preparation,
                                      String status) {
        if (preparation == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add("任务状态：" + StrUtil.blankToDefault(status, "unknown"));
        lines.add("生成类型：" + (preparation.targetType() == null ? "unknown" : preparation.targetType().getValue())
                + "，阶段：" + StrUtil.blankToDefault(preparation.generatingStage(), "unknown")
                + "，构建校验：" + preparation.requiresBuildValidation());
        GenerationArtifact changePlan = preparation.artifact("change_plan");
        if (changePlan != null) {
            lines.add("变更计划：" + compactMemoryText(String.valueOf(changePlan.payload()), 900));
        }
        appendDiffSummaryMemory(lines, appId, preparation);
        appendPatchResultMemory(lines, appId, preparation);
        if (preparation.qualityGateResult() != null) {
            lines.add("质量门禁：passed=" + preparation.qualityGateResult().passed()
                    + ", blockers=" + compactMemoryText(String.valueOf(preparation.qualityGateResult().blockers()), 500));
        }
        return compactMemoryText(String.join("\n", lines), 5000);
    }

    /** 仅把校验通过且属于当前任务的差异事实写入长期记忆。 */
    private void appendDiffSummaryMemory(List<String> lines,
                                         Long appId,
                                         GenerationPreparation preparation) {
        GenerationArtifact artifact = preparation.artifact(DiffSummary.KEY);
        if (artifact == null) {
            return;
        }
        try {
            DiffSummary summary = DiffSummary.fromArtifact(
                    artifact,
                    appId,
                    preparation.taskId()
            );
            if (!summary.created()) {
                lines.add("差异摘要：已跳过，原因=" + compactMemoryText(summary.reason(), 300));
                return;
            }
            String changeFacts = "新增=" + summary.addedCount()
                    + summary.addedFiles()
                    + "，修改=" + summary.modifiedCount()
                    + summary.modifiedFiles()
                    + "，删除=" + summary.deletedCount()
                    + summary.deletedFiles();
            lines.add("实际变更：" + compactMemoryText(changeFacts, 900));
        } catch (IllegalArgumentException | NullPointerException exception) {
            lines.add("差异摘要：制品无效，未纳入结果记忆");
        }
    }

    /** 仅把校验通过且属于当前任务的补丁落盘事实写入长期记忆。 */
    private void appendPatchResultMemory(List<String> lines,
                                         Long appId,
                                         GenerationPreparation preparation) {
        GenerationArtifact artifact = preparation.artifact(PatchResult.KEY);
        if (artifact == null) {
            return;
        }
        try {
            PatchResult result = PatchResult.fromArtifact(
                    artifact,
                    appId,
                    preparation.taskId()
            );
            if (result.applied()) {
                lines.add("Patch 结果：已对齐，匹配文件=" + result.matchedFileCount());
                return;
            }
            if (result.drifted()) {
                String driftFacts = "计划外=" + result.unplannedFileCount()
                        + result.unplannedFiles()
                        + "，计划内未落盘=" + result.missingPlannedFileCount()
                        + result.missingPlannedFiles();
                lines.add("Patch 结果：存在偏差，" + compactMemoryText(driftFacts, 700));
                return;
            }
            lines.add("Patch 结果：已跳过，原因=" + compactMemoryText(result.reason(), 300));
        } catch (IllegalArgumentException | NullPointerException exception) {
            lines.add("Patch 结果：制品无效，未纳入结果记忆");
        }
    }

    private String compactMemoryText(String value, int maxLength) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    /** 处理记录结果。 */
    private void rememberOutcome(Long appId,
                                 GenerationSession session,
                                 GenerationPreparation preparation,
                                 GenerationTerminalOutcome outcome,
                                 String memorySummary) {
        if (session.taskRequest() == null || session.taskRequest().loginUser() == null) {
            return;
        }
        if (session.taskRequest().app() == null
                || session.taskRequest().app().getTenantId() == null) {
            return;
        }
        outcomeMemoryService.remember(new GenerationOutcomeMemoryRequest(
                preparation.taskId(),
                session.taskRequest().app().getTenantId(),
                appId,
                session.taskRequest().loginUser().getId(),
                outcome.taskStatus(),
                session.taskRequest().message(),
                memorySummary,
                orchestrationMode(preparation),
                preparation.targetType() == null ? "unknown" : preparation.targetType().getValue()
        ));
    }

    private void rememberOutcomeSafely(Long appId,
                                       GenerationSession session,
                                       GenerationPreparation preparation,
                                       GenerationTerminalOutcome outcome,
                                       String memorySummary) {
        try {
            rememberOutcome(appId, session, preparation, outcome, memorySummary);
        } catch (RuntimeException memoryFailure) {
            log.warn("生成结果记忆写入失败，终态不受影响，taskId: {}",
                    preparation.taskId(), LogExceptionSanitizer.sanitize(memoryFailure));
        }
    }

}
