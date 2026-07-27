package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.process.GoProcessEnvironment;
import com.rush.rushaicodemother.infrastructure.process.GoToolchain;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessOutputLogPolicy;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessResult;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxNetworkPolicy;
import com.rush.rushaicodemother.orchestration.template.ProjectTemplateCatalog;
import com.rush.rushaicodemother.orchestration.template.ProjectTemplateMaterializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** 在接收评测任务前验证后端模板、Go 工具链、离线依赖和临时工作区。 */
@Slf4j
@Component
public class GenerationBenchmarkBackendReadinessVerifier implements SmartInitializingSingleton {

    private static final String DISPLAY_COMMAND =
            "go test -mod=readonly -count=1 -trimpath -buildvcs=false ./...";

    private final GenerationBenchmarkBackendProperties properties;
    private final ProjectTemplateMaterializer templateMaterializer;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final ManagedProcessExecutor processExecutor;
    private final GoToolchain goToolchain;

    public GenerationBenchmarkBackendReadinessVerifier(
            GenerationBenchmarkBackendProperties properties,
            ProjectTemplateMaterializer templateMaterializer,
            WorkspaceFileSystemService workspaceFileSystemService,
            ManagedProcessExecutor processExecutor,
            GoToolchain goToolchain
    ) {
        this.properties = properties;
        this.templateMaterializer = templateMaterializer;
        this.workspaceFileSystemService = workspaceFileSystemService;
        this.processExecutor = processExecutor;
        this.goToolchain = goToolchain;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verify();
    }

    void verify() {
        if (!properties.isEnabled()) {
            return;
        }
        Path workspace = readinessWorkspace();
        Throwable primaryFailure = null;
        try {
            workspaceFileSystemService.ensureDirectory(workspace.getParent());
            templateMaterializer.materializeAtomically(
                    ProjectTemplateCatalog.GO_SQLITE_BACKEND,
                    workspace
            );
            ManagedProcessResult result = processExecutor.execute(buildRequest(workspace));
            requireSuccessfulVerification(result);
            log.info("生成基准测试后端评分就绪检查通过");
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } catch (Exception failure) {
            IllegalStateException wrapped = new IllegalStateException(
                    "生成基准测试后端评分就绪检查失败",
                    failure
            );
            primaryFailure = wrapped;
            throw wrapped;
        } finally {
            deleteWorkspace(workspace, primaryFailure);
        }
    }

    private Path readinessWorkspace() {
        Path root = properties.getWorkspaceRoot().toAbsolutePath().normalize();
        return root.resolve("readiness-" + UUID.randomUUID()).normalize();
    }

    private ManagedProcessRequest buildRequest(Path workspace) {
        return ManagedProcessRequest.builder()
                .workingDirectory(workspace)
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
                .timeout(properties.getProcessTimeout())
                .heartbeatInterval(properties.getHeartbeatInterval())
                .outputDrainTimeout(properties.getOutputDrainTimeout())
                .maxOutputLength(properties.getMaxOutputLength())
                .redirectErrorStream(true)
                .outputLogPolicy(ManagedProcessOutputLogPolicy.SUMMARY)
                .logCategory("benchmark-backend-readiness")
                .logContext("template=" + ProjectTemplateCatalog.GO_SQLITE_BACKEND)
                .cancellationRequested(() -> Thread.currentThread().isInterrupted())
                .networkPolicy(SandboxNetworkPolicy.NONE)
                .build();
    }

    private void requireSuccessfulVerification(ManagedProcessResult result) {
        if (result != null && result.exitedSuccessfully()) {
            return;
        }
        ManagedProcessResult.Status status = result == null ? null : result.status();
        Integer exitCode = result == null ? null : result.exitCode();
        String output = result == null
                ? ""
                : LogExceptionSanitizer.sanitizeValue(result.combinedOutput(), 2_000);
        log.error("生成基准测试后端评分就绪检查未通过: status={}, exitCode={}, output={}",
                status, exitCode, output);
        if (status == ManagedProcessResult.Status.INTERRUPTED
                || Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("生成基准测试后端评分就绪检查被中断");
        }
        throw new IllegalStateException(
                "生成基准测试后端评分不可用，请检查完整 Go SDK、离线模块缓存和沙箱配置"
        );
    }

    private void deleteWorkspace(Path workspace, Throwable primaryFailure) {
        if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            workspaceFileSystemService.deleteDirectory(workspace);
            if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("生成基准测试后端评分就绪目录未被完整清理");
            }
        } catch (Exception cleanupFailure) {
            IllegalStateException wrapped = cleanupFailure instanceof IllegalStateException stateFailure
                    ? stateFailure
                    : new IllegalStateException(
                            "清理生成基准测试后端评分就绪目录失败",
                            cleanupFailure
                    );
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(wrapped);
                return;
            }
            throw wrapped;
        }
    }
}
