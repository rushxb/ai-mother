package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.rush.rushaicodemother.bootstrap.StandaloneProcessExitCodeGenerator;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 在其他启动校验完成后执行一次 Benchmark，并向主进程暴露退出码。 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(
        prefix = "app.generation-benchmark.worker",
        name = "enabled",
        havingValue = "true")
public class GenerationBenchmarkWorkerApplicationRunner
        implements ApplicationRunner, StandaloneProcessExitCodeGenerator {

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_EXECUTION_FAILED = 1;
    static final int EXIT_GATE_REJECTED = 2;

    private final GenerationBenchmarkWorkerExecutionService executionService;
    private volatile int exitCode = EXIT_EXECUTION_FAILED;

    /**
 * 运行生成基准测试工作器应用处理流程。
 *
 * @param args 命令行参数
 */
    @Override
    public void run(ApplicationArguments args) {
        try {
            GenerationBenchmarkWorkerResult result = executionService.execute();
            exitCode = EXIT_SUCCESS;
            log.info("Benchmark Worker 执行完成，evidenceId={}", result.evidenceId());
        } catch (GenerationBenchmarkWorkerRejectedException rejected) {
            exitCode = EXIT_GATE_REJECTED;
            log.error("Benchmark Worker 候选未通过发布门禁，原因={}",
                    LogExceptionSanitizer.sanitizeMessage(rejected));
        } catch (RuntimeException failure) {
            exitCode = EXIT_EXECUTION_FAILED;
            log.error("Benchmark Worker 执行失败，原因={}",
                    LogExceptionSanitizer.sanitizeMessage(failure));
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
