package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.orchestration.GenerationSessionProperties;
import com.rush.rushaicodemother.orchestration.context.AiContextPackBudgetProperties;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingTelemetryProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecutorProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressProperties;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验已下沉为常量的生成策略无法被外部配置覆盖。
 */
class HardcodedGenerationPolicyWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    GenerationProjectContextProperties.class,
                    EditLocatorProperties.class,
                    PatchExecutionProperties.class,
                    AiToolWorkspaceProperties.class,
                    AiToolLoopGuardProperties.class,
                    AiAgentProductivityProperties.class,
                    AiContextPackBudgetProperties.class,
                    AiModelCircuitBreakerProperties.class,
                    AiToolApprovalProperties.class,
                    ArtifactLifecycleProperties.class,
                    DependencyInstallProperties.class,
                    ExternalProcessProperties.class,
                    GenerationCommitProperties.class,
                    GenerationCreditReservationProperties.class,
                    GenerationRoutingTelemetryProperties.class,
                    GenerationRuntimeProperties.class,
                    GenerationSessionProperties.class,
                    GenerationSlaProperties.class,
                    GenerationSseProperties.class,
                    GenerationStageAdmissionProperties.class,
                    GenerationTaskAdmissionProperties.class,
                    GenerationTaskExecutorProperties.class,
                    GenerationTaskProgressProperties.class,
                    ProjectCommandProperties.class,
                    TemplateMaterializationProperties.class,
                    UserCreditProperties.class,
                    WorkspaceFileSystemProperties.class
            )
            .withPropertyValues(
                    "app.generation-project-context.max-project-index-files=1",
                    "app.edit-locator.max-candidate-files=1",
                    "app.patch-execution.max-operations=1",
                    "app.ai-tool-workspace.max-batch-write-files=1",
                    "app.ai-tool-loop-guard.max-no-progress-calls=2",
                    "app.ai-agent-productivity.max-model-turns-without-mutation=1",
                    "app.ai-context-pack.generation-max-tokens=999",
                    "app.ai-model-circuit-breaker.failure-threshold=9",
                    "app.ai-tool-approval.expiration-batch-size=7",
                    "app.artifact-lifecycle.max-files=3",
                    "app.dependency-install.max-attempts=9",
                    "app.external-process.termination-grace-period=9s",
                    "app.generation-commit.lock-stripes=8",
                    "app.user-credit.reservation.create-estimated-tokens=1",
                    "app.generation-routing.telemetry.task-sample-limit=1",
                    "app.generation-runtime.max-repair-rounds=9",
                    "app.generation-session.lock-stripes=8",
                    "app.generation-sla.profiles.CREATE.max-model-turns=99",
                    "app.generation-sse.heartbeat-interval=99s",
                    "app.generation-stage-admission.build-minimum=99s",
                    "app.generation-task-admission.max-non-terminal-tasks-per-user=99",
                    "app.generation-task-executor.max-concurrency=99",
                    "app.generation-progress.task-sample-limit=1",
                    "app.project-command.full-build-timeout=9m",
                    "app.template-materialization.max-files=1",
                    "app.user-credit.tokens-per-credit=1",
                    "app.workspace-file-system.max-files=1"
            );

    @Test
    void externalPropertiesMustNotOverrideHardcodedPolicies() {
        contextRunner.run(context -> {
            assertThat(context.getBean(GenerationProjectContextProperties.class)
                    .getMaxProjectIndexFiles())
                    .isEqualTo(GenerationProjectContextProperties.MAX_PROJECT_INDEX_FILES);
            assertThat(context.getBean(EditLocatorProperties.class).getMaxCandidateFiles())
                    .isEqualTo(EditLocatorProperties.MAX_CANDIDATE_FILES);
            assertThat(context.getBean(PatchExecutionProperties.class).getMaxOperations())
                    .isEqualTo(PatchExecutionProperties.MAX_OPERATIONS);
            assertThat(context.getBean(AiToolWorkspaceProperties.class).getMaxBatchWriteFiles())
                    .isEqualTo(AiToolWorkspaceProperties.MAX_BATCH_WRITE_FILES);
            assertThat(context.getBean(AiToolLoopGuardProperties.class).getMaxNoProgressCalls())
                    .isEqualTo(AiToolLoopGuardProperties.MAX_NO_PROGRESS_CALLS);
            assertThat(context.getBean(AiAgentProductivityProperties.class)
                    .getMaxModelTurnsWithoutMutation())
                    .isEqualTo(AiAgentProductivityProperties.MAX_MODEL_TURNS_WITHOUT_MUTATION);
        });
    }

    @Test
    void externalPropertiesMustNotOverrideRuntimeAndSlaBudgets() {
        contextRunner.run(context -> {
            assertThat(context.getBean(GenerationRuntimeProperties.class).getMaxRepairRounds())
                    .isEqualTo(GenerationRuntimeProperties.MAX_REPAIR_ROUNDS);
            assertThat(context.getBean(GenerationSlaProperties.class)
                    .profile(GenerationMode.CREATE).getMaxModelTurns())
                    .isEqualTo(GenerationSlaProperties.CREATE_MAX_MODEL_TURNS);
            assertThat(context.getBean(GenerationStageAdmissionProperties.class).getBuildMinimum())
                    .isEqualTo(GenerationStageAdmissionProperties.BUILD_MINIMUM);
            assertThat(context.getBean(GenerationTaskExecutorProperties.class).getMaxConcurrency())
                    .isEqualTo(GenerationTaskExecutorProperties.MAX_CONCURRENCY);
            assertThat(context.getBean(GenerationTaskAdmissionProperties.class)
                    .getMaxNonTerminalTasksPerUser())
                    .isEqualTo(GenerationTaskAdmissionProperties.MAX_NON_TERMINAL_TASKS_PER_USER);
            assertThat(context.getBean(GenerationSseProperties.class).getHeartbeatInterval())
                    .isEqualTo(GenerationSseProperties.HEARTBEAT_INTERVAL);
        });
    }

    @Test
    void externalPropertiesMustNotOverrideResourceAndTimeoutLimits() {
        contextRunner.run(context -> {
            assertThat(context.getBean(WorkspaceFileSystemProperties.class).getMaxFiles())
                    .isEqualTo(WorkspaceFileSystemProperties.MAX_FILES);
            assertThat(context.getBean(ProjectCommandProperties.class).getFullBuildTimeout())
                    .isEqualTo(ProjectCommandProperties.FULL_BUILD_TIMEOUT);
            assertThat(context.getBean(ArtifactLifecycleProperties.class).getMaxFiles())
                    .isEqualTo(ArtifactLifecycleProperties.MAX_FILES);
            assertThat(context.getBean(TemplateMaterializationProperties.class).getMaxFiles())
                    .isEqualTo(TemplateMaterializationProperties.MAX_FILES);
            assertThat(context.getBean(DependencyInstallProperties.class).getMaxAttempts())
                    .isEqualTo(DependencyInstallProperties.MAX_ATTEMPTS);
            assertThat(context.getBean(ExternalProcessProperties.class).getTerminationGracePeriod())
                    .isEqualTo(ExternalProcessProperties.TERMINATION_GRACE_PERIOD);
            assertThat(context.getBean(GenerationCommitProperties.class).getLockStripes())
                    .isEqualTo(GenerationCommitProperties.LOCK_STRIPES);
            assertThat(context.getBean(GenerationSessionProperties.class).getLockStripes())
                    .isEqualTo(GenerationSessionProperties.LOCK_STRIPES);
        });
    }

    @Test
    void externalPropertiesMustNotOverrideBudgetAndTelemetryPolicies() {
        contextRunner.run(context -> {
            assertThat(context.getBean(AiContextPackBudgetProperties.class).getGenerationMaxTokens())
                    .isEqualTo(AiContextPackBudgetProperties.GENERATION_MAX_TOKENS);
            assertThat(context.getBean(AiModelCircuitBreakerProperties.class).getFailureThreshold())
                    .isEqualTo(AiModelCircuitBreakerProperties.FAILURE_THRESHOLD);
            assertThat(context.getBean(AiToolApprovalProperties.class).getExpirationBatchSize())
                    .isEqualTo(AiToolApprovalProperties.EXPIRATION_BATCH_SIZE);
            assertThat(context.getBean(GenerationRoutingTelemetryProperties.class)
                    .getTaskSampleLimit())
                    .isEqualTo(GenerationRoutingTelemetryProperties.TASK_SAMPLE_LIMIT);
            assertThat(context.getBean(GenerationTaskProgressProperties.class).getTaskSampleLimit())
                    .isEqualTo(GenerationTaskProgressProperties.TASK_SAMPLE_LIMIT);
            assertThat(context.getBean(UserCreditProperties.class).getTokensPerCredit())
                    .isEqualTo(UserCreditProperties.TOKENS_PER_CREDIT);
            assertThat(context.getBean(GenerationCreditReservationProperties.class)
                    .getCreateEstimatedTokens())
                    .isEqualTo(GenerationCreditReservationProperties.CREATE_ESTIMATED_TOKENS);
        });
    }
}
