package com.rush.rushaicodemother.core.builder;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandResult;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** Go 项目构建测试编排入口。 */
@Slf4j
@Component
public class GoProjectBuilder {

    private final GoBuildCommandService commandService;
    private final GoProjectSnapshotService snapshotService;
    private final GoBuildResultRegistry resultRegistry;
    private final GenerationExecutionContextService executionContextService;

    public GoProjectBuilder(
            GoBuildCommandService commandService,
            GoProjectSnapshotService snapshotService,
            GoBuildResultRegistry resultRegistry,
            GenerationExecutionContextService executionContextService
    ) {
        this.commandService = Objects.requireNonNull(commandService, "Go 构建命令服务不能为空");
        this.snapshotService = Objects.requireNonNull(snapshotService, "Go 项目快照服务不能为空");
        this.resultRegistry = Objects.requireNonNull(resultRegistry, "Go 构建结果注册表不能为空");
        this.executionContextService = Objects.requireNonNull(
                executionContextService,
                "生成执行上下文服务不能为空"
        );
    }

    public GoBuildResult buildProjectWithResult(String projectPath, String taskId) {
        return buildProjectWithResult(
                projectPath,
                taskId,
                BuildExecutionBudgetReservation.forTask(executionContextService, taskId)
        );
    }

    public GoBuildResult buildProjectWithResult(
            String projectPath,
            String taskId,
            BuildExecutionBudgetReservation budgetReservation
    ) {
        Objects.requireNonNull(budgetReservation, "构建预算预留不能为空");
        Path projectRoot = resolveProjectRoot(projectPath);
        if (projectRoot == null) {
            return GoBuildResult.invalid(projectPath, "Go 项目目录不存在或不安全");
        }
        if (!isSafeRegularFile(projectRoot.resolve("go.mod"))) {
            return GoBuildResult.invalid(projectRoot.toString(), "Go 项目缺少安全的 go.mod 文件");
        }
        if (!isSafeRegularFile(projectRoot.resolve("go.sum"))) {
            return GoBuildResult.invalid(projectRoot.toString(), "Go 项目缺少安全的 go.sum 文件");
        }

        if (StrUtil.isBlank(taskId)) {
            return executeTests(projectRoot, taskId, budgetReservation);
        }
        GoProjectSnapshot snapshot = captureSnapshot(projectRoot);
        if (snapshot == null) {
            return executeTests(projectRoot, taskId, budgetReservation);
        }
        GoBuildResult result = resultRegistry.execute(
                taskId,
                projectRoot,
                snapshot,
                () -> executeStableTests(projectRoot, taskId, budgetReservation, snapshot)
        );
        if ("reused".equals(result.stage())) {
            log.info("Go 项目源码未变化，跳过重复构建测试: taskId={}, projectRoot={}", taskId, projectRoot);
        }
        return result;
    }

    private GoBuildResult executeStableTests(
            Path projectRoot,
            String taskId,
            BuildExecutionBudgetReservation budgetReservation,
            GoProjectSnapshot expectedSnapshot
    ) {
        GoBuildResult result = executeTests(projectRoot, taskId, budgetReservation);
        if (!result.success()) {
            return result;
        }
        GoProjectSnapshot completedSnapshot = captureSnapshot(projectRoot);
        if (completedSnapshot == null || !expectedSnapshot.equals(completedSnapshot)) {
            log.warn("Go 项目在构建测试期间发生变化，不记录成功结果: taskId={}, projectRoot={}",
                    taskId, projectRoot);
            return GoBuildResult.sourceChangedDuringBuild(projectRoot.toString());
        }
        return result;
    }

    private GoBuildResult executeTests(
            Path projectRoot,
            String taskId,
            BuildExecutionBudgetReservation budgetReservation
    ) {
        budgetReservation.reserve();
        log.info("开始执行 Go 项目构建测试: {}", projectRoot);
        ProjectCommandResult commandResult = commandService.executeTests(projectRoot, taskId);
        return GoBuildResult.fromCommand(projectRoot.toString(), commandResult);
    }

    private GoProjectSnapshot captureSnapshot(Path projectRoot) {
        try {
            return snapshotService.capture(projectRoot);
        } catch (Exception exception) {
            log.debug("Go 项目快照不可用，将执行真实构建测试: projectRoot={}, error={}",
                    projectRoot, LogExceptionSanitizer.sanitizeMessage(exception));
            return null;
        }
    }

    private Path resolveProjectRoot(String projectPath) {
        if (StrUtil.isBlank(projectPath)) {
            return null;
        }
        try {
            Path projectRoot = Path.of(projectPath).toAbsolutePath().normalize();
            return isSafeDirectory(projectRoot) ? projectRoot : null;
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private boolean isSafeDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    }

    private boolean isSafeRegularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    }
}
