package com.rush.rushaicodemother.infrastructure.process;

import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxNetworkPolicy;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** 在统一进程与沙箱边界内执行 Go 项目测试。 */
@Slf4j
@Component
public class GoProjectCommandExecutor {

    private static final String DISPLAY_COMMAND =
            "go test -mod=readonly -count=1 -trimpath -buildvcs=false ./...";

    private final ProjectCommandProperties properties;
    private final ManagedProcessExecutor processExecutor;
    private final GenerationExecutionContextService executionContextService;
    private final GoToolchain goToolchain;

    @Autowired
    public GoProjectCommandExecutor(
            ProjectCommandProperties properties,
            ManagedProcessExecutor processExecutor,
            GenerationExecutionContextService executionContextService,
            GoToolchain goToolchain
    ) {
        this.properties = Objects.requireNonNull(properties, "项目命令配置不能为空");
        this.processExecutor = Objects.requireNonNull(processExecutor, "受管进程执行器不能为空");
        this.executionContextService = Objects.requireNonNull(executionContextService, "生成执行上下文服务不能为空");
        this.goToolchain = Objects.requireNonNull(goToolchain, "Go 工具链不能为空");
    }

    /**
 * 执行{@code Tests}处理流程。
 *
 * @param projectDirectory 项目目录
 * @param taskId 任务编号
 * @param logContext 日志上下文
 * @return {@code Tests}
 */
    public ProjectCommandResult executeTests(Path projectDirectory, String taskId, String logContext) {
        Duration timeout = executionContextService.clampTimeout(taskId, properties.getGoTestTimeout());
        ManagedProcessResult managedResult = processExecutor.execute(
                ManagedProcessRequest.builder()
                        .workingDirectory(projectDirectory)
                        .command(List.of(
                                goToolchain.goExecutable(),
                                "test",
                                "-mod=readonly",
                                "-count=1",
                                "-trimpath",
                                "-buildvcs=false",
                                "./..."
                        ))
                        .displayCommand(DISPLAY_COMMAND)
                        .environment(GoProcessEnvironment.overrides())
                        .environmentVariablesToRemove(GoProcessEnvironment.variablesToRemove())
                        .timeout(timeout)
                        .idleTimeout(properties.getGoTestIdleTimeout())
                        .heartbeatInterval(properties.getHeartbeatInterval())
                        .outputDrainTimeout(properties.getOutputDrainTimeout())
                        .maxOutputLength(properties.getMaxOutputLength())
                        .redirectErrorStream(true)
                        .outputLogPolicy(ManagedProcessOutputLogPolicy.SUMMARY)
                        .logCategory("go-build")
                        .logContext(logContext)
                        .cancellationRequested(() -> executionContextService.shouldStop(taskId))
                        .networkPolicy(SandboxNetworkPolicy.NONE)
                        .build()
        );
        ProjectCommandResult result = toProjectCommandResult(managedResult);
        executionContextService.assertCanContinue(taskId);
        if (result.success()) {
            log.info("Go 项目构建测试通过: command={}", result.command());
        } else {
            log.warn("Go 项目构建测试未通过: command={}, status={}, exitCode={}",
                    result.command(), result.status(), result.exitCode());
        }
        return result;
    }

    /** 将当前对象转换为项目命令结果。 */
    private ProjectCommandResult toProjectCommandResult(ManagedProcessResult result) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (result.status() == ManagedProcessResult.Status.COMPLETED) {
            if (Integer.valueOf(0).equals(result.exitCode())) {
                return new ProjectCommandResult(
                        ProjectCommandResult.Status.SUCCESS,
                        result.command(),
                        result.exitCode(),
                        result.combinedOutput(),
                        null
                );
            }
            return new ProjectCommandResult(
                    ProjectCommandResult.Status.FAILED,
                    result.command(),
                    result.exitCode(),
                    result.combinedOutput(),
                    "Go 测试命令退出码: " + result.exitCode()
            );
        }
        return new ProjectCommandResult(
                switch (result.status()) {
                    case TIMED_OUT -> ProjectCommandResult.Status.TIMED_OUT;
                    case IDLE_TIMED_OUT -> ProjectCommandResult.Status.IDLE_TIMED_OUT;
                    case INTERRUPTED -> ProjectCommandResult.Status.INTERRUPTED;
                    case START_FAILED -> ProjectCommandResult.Status.START_FAILED;
                    case COMPLETED -> throw new IllegalStateException("已完成的 Go 进程必须包含退出码");
                },
                result.command(),
                result.exitCode(),
                result.combinedOutput(),
                result.errorDetail()
        );
    }
}
