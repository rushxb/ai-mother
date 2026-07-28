package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.infrastructure.process.GoProjectCommandExecutor;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandResult;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** 执行 Go 构建测试并记录关键路径性能。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoBuildCommandService {

    private static final String DISPLAY_COMMAND =
            "go test -mod=readonly -count=1 -trimpath -buildvcs=false ./...";

    private final GoProjectCommandExecutor commandExecutor;
    private final GenerationPerformanceMonitorService performanceMonitorService;

    /** 执行{@code Tests}处理流程。 */
    ProjectCommandResult executeTests(Path projectRoot, String taskId) {
        GenerationPerformanceMonitorService.SpanTimer span =
                performanceMonitorService.startSpan(taskId, "go_test", GenerationSpanCategory.BUILD);
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            ProjectCommandResult result = commandExecutor.executeTests(
                    projectRoot,
                    taskId,
                    "go_test:" + projectRoot
            );
            if (result.success()) {
                span.success();
            } else {
                span.close(result.timedOut() ? "timeout" : "failed", result.command());
            }
            return result;
        } catch (GenerationExecutionPolicyException exception) {
            span.failed(exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            span.failed(LogExceptionSanitizer.sanitizeMessage(exception));
            log.warn("执行 Go 构建测试时发生异常: projectRoot={}, error={}",
                    projectRoot, LogExceptionSanitizer.sanitizeMessage(exception));
            return new ProjectCommandResult(
                    ProjectCommandResult.Status.START_FAILED,
                    DISPLAY_COMMAND,
                    null,
                    "",
                    LogExceptionSanitizer.sanitizeMessage(exception)
            );
        }
    }
}
