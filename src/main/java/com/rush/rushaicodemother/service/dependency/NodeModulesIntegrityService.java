package com.rush.rushaicodemother.service.dependency;

import com.rush.rushaicodemother.config.DependencyInstallProperties;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessResult;
import com.rush.rushaicodemother.infrastructure.process.NodeProcessEnvironment;
import com.rush.rushaicodemother.infrastructure.process.NodeToolchain;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** 负责 node_modules 结构、Vite 运行时及关键 Windows 原生包的完整性校验与安全修复。 */
@Slf4j
@Component
public class NodeModulesIntegrityService {

    private static final List<NativePackageDescriptor> WINDOWS_NATIVE_PACKAGES = List.of(
            new NativePackageDescriptor("@rollup+rollup-win32", "@rollup/rollup-win32-x64-msvc"),
            new NativePackageDescriptor("@esbuild+win32", "@esbuild/win32-x64")
    );

    private final DependencyInstallProperties properties;
    private final ManagedProcessExecutor processExecutor;
    private final NodeToolchain nodeToolchain;
    private final GenerationExecutionContextService executionContextService;
    private final boolean windows;

    @Autowired
    public NodeModulesIntegrityService(
            DependencyInstallProperties properties,
            ManagedProcessExecutor processExecutor,
            NodeToolchain nodeToolchain,
            GenerationExecutionContextService executionContextService
    ) {
        this(properties, processExecutor, nodeToolchain, executionContextService, isWindowsOperatingSystem());
    }

    NodeModulesIntegrityService(
            DependencyInstallProperties properties,
            ManagedProcessExecutor processExecutor,
            NodeToolchain nodeToolchain,
            GenerationExecutionContextService executionContextService,
            boolean windows
    ) {
        this.properties = properties;
        this.processExecutor = processExecutor;
        this.nodeToolchain = nodeToolchain;
        this.executionContextService = executionContextService;
        this.windows = windows;
    }

    /** 判断{@code Complete}是否满足约束。 */
    boolean isComplete(Path projectDirectory, String taskId) {
        Path projectPath = normalize(projectDirectory);
        Path nodeModules = projectPath.resolve("node_modules");
        Path pnpmDirectory = nodeModules.resolve(".pnpm");
        if (!isSafeDirectory(nodeModules) || !isSafeDirectory(pnpmDirectory)) {
            return false;
        }
        if (!containsEntries(pnpmDirectory) || !hasSafeViteExecutable(nodeModules)) {
            return false;
        }
        if (!isViteRuntimeResolvable(projectPath, taskId)) {
            return false;
        }
        return areNativePackagesComplete(pnpmDirectory);
    }

    /** 清理损坏的{@code Native}依赖包。 */
    void cleanCorruptedNativePackages(Path projectDirectory) throws IOException {
        if (!windows) {
            return;
        }
        Path nodeModules = normalize(projectDirectory).resolve("node_modules");
        Path pnpmDirectory = nodeModules.resolve(".pnpm");
        if (!isSafeDirectory(nodeModules) || !isSafeDirectory(pnpmDirectory)) {
            return;
        }

        for (NativePackageDescriptor descriptor : WINDOWS_NATIVE_PACKAGES) {
            for (Path versionDirectory : findMatchingVersionDirectories(pnpmDirectory, descriptor.directoryPrefix())) {
                Path packageDirectory = versionDirectory.resolve("node_modules").resolve(descriptor.modulePath());
                if (Files.exists(packageDirectory, LinkOption.NOFOLLOW_LINKS)
                        && !isNativePackageDirectoryValid(packageDirectory)) {
                    log.warn("清理损坏的原生依赖目录: {}", packageDirectory);
                    deleteNativePackageDirectory(pnpmDirectory, packageDirectory);
                }
            }
        }
    }

    /** 删除{@code Native}依赖包目录。 */
    void deleteNativePackageDirectory(Path pnpmDirectory, Path packageDirectory) throws IOException {
        Path safeRoot = normalize(pnpmDirectory);
        Path target = normalize(packageDirectory);
        if (target.equals(safeRoot) || !target.startsWith(safeRoot)) {
            throw new IOException("拒绝删除 .pnpm 根目录之外的路径: " + target);
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        validateSafeAncestors(safeRoot, target);

        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            /**
 * 在目录访问完成后处理异常并收口遍历状态。
 *
 * @param directory 目录
 * @param exception 待转换或处理的异常
 * @return 方法执行结果
 */
            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 校验{@code ate}安全{@code Ancestors}是否有效。 */
    private void validateSafeAncestors(Path safeRoot, Path target) throws IOException {
        if (!isSafeDirectory(safeRoot)) {
            throw new IOException(".pnpm 根目录不是安全的普通目录: " + safeRoot);
        }
        Path current = safeRoot;
        Path relativeTarget = safeRoot.relativize(target);
        for (int index = 0; index < relativeTarget.getNameCount() - 1; index++) {
            current = current.resolve(relativeTarget.getName(index));
            if (Files.isSymbolicLink(current)
                    || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("拒绝通过不安全的目录层级删除依赖: " + current);
            }
        }
    }

    /** 判断{@code Vite}运行时{@code Resolvable}是否满足约束。 */
    private boolean isViteRuntimeResolvable(Path projectDirectory, String taskId) {
        List<String> command = List.of(
                nodeToolchain.nodeExecutable(),
                "--input-type=module",
                "--eval",
                "import('vite').then(() => process.exit(0)).catch((error) => { "
                        + "console.error(error?.message || error); process.exit(1); })"
        );
        Duration timeout = executionContextService.clampTimeout(
                taskId,
                properties.getRuntimeValidationTimeout()
        );
        BooleanSupplier cancellationRequested = () -> executionContextService.shouldStop(taskId);
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            ManagedProcessResult result = processExecutor.execute(
                    ManagedProcessRequest.builder()
                            .workingDirectory(projectDirectory)
                            .command(command)
                            .environment(NodeProcessEnvironment.overrides(false))
                            .environmentVariablesToRemove(NodeProcessEnvironment.variablesToRemove())
                            .timeout(timeout)
                            .idleTimeout(null)
                            .heartbeatInterval(properties.getHeartbeatInterval())
                            .outputDrainTimeout(properties.getOutputDrainTimeout())
                            .maxOutputLength(properties.getMaxOutputLength())
                            .redirectErrorStream(true)
                            .logCategory("dependency-validation")
                            .logContext("vite-runtime-check " + projectDirectory)
                            .cancellationRequested(cancellationRequested)
                            .build()
            );
            executionContextService.assertCanContinue(taskId);
            if (!result.exitedSuccessfully()) {
                log.warn("Vite 运行时校验失败: project={}, status={}, exitCode={}",
                        projectDirectory, result.status(), result.exitCode());
                return false;
            }
            return true;
        } catch (GenerationExecutionPolicyException policyFailure) {
            throw policyFailure;
        } catch (RuntimeException exception) {
            log.warn("Vite 运行时校验异常: project={}, exceptionType={}",
                    projectDirectory, exception.getClass().getName());
            return false;
        }
    }

    /** 返回{@code are}{@code Native}依赖包{@code Complete}。 */
    private boolean areNativePackagesComplete(Path pnpmDirectory) {
        if (!windows) {
            return true;
        }
        try {
            for (NativePackageDescriptor descriptor : WINDOWS_NATIVE_PACKAGES) {
                for (Path versionDirectory : findMatchingVersionDirectories(
                        pnpmDirectory,
                        descriptor.directoryPrefix()
                )) {
                    Path packageDirectory = versionDirectory.resolve("node_modules").resolve(descriptor.modulePath());
                    if (Files.exists(packageDirectory, LinkOption.NOFOLLOW_LINKS)
                            && !isNativePackageDirectoryValid(packageDirectory)) {
                        log.warn("关键原生依赖不完整: {}", packageDirectory);
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException exception) {
            log.warn("检查关键原生依赖失败: path={}, exceptionType={}",
                    pnpmDirectory, exception.getClass().getName());
            return false;
        }
    }

    private List<Path> findMatchingVersionDirectories(Path pnpmDirectory, String directoryPrefix) throws IOException {
        List<Path> matches = new ArrayList<>();
        try (var entries = Files.list(pnpmDirectory)) {
            entries.filter(path -> path.getFileName().toString().startsWith(directoryPrefix))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(matches::add);
        }
        return matches;
    }

    private boolean isNativePackageDirectoryValid(Path packageDirectory) {
        return !Files.isSymbolicLink(packageDirectory)
                && Files.isDirectory(packageDirectory, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(packageDirectory.resolve("package.json"), LinkOption.NOFOLLOW_LINKS);
    }

    private boolean hasSafeViteExecutable(Path nodeModules) {
        Path binDirectory = nodeModules.resolve(".bin");
        if (!isSafeDirectory(binDirectory)) {
            return false;
        }
        Path windowsLauncher = binDirectory.resolve("vite.cmd");
        Path unixLauncher = binDirectory.resolve("vite");
        return isSafeExecutable(nodeModules, windowsLauncher)
                || isSafeExecutable(nodeModules, unixLauncher);
    }

    /** 判断安全{@code Executable}是否满足约束。 */
    private boolean isSafeExecutable(Path nodeModules, Path executable) {
        if (!Files.exists(executable, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isSymbolicLink(executable)) {
            return Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS);
        }
        try {
            Path nodeModulesRealPath = nodeModules.toRealPath();
            Path executableTarget = executable.toRealPath();
            return executableTarget.startsWith(nodeModulesRealPath)
                    && Files.isRegularFile(executableTarget, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean isSafeDirectory(Path directory) {
        return !Files.isSymbolicLink(directory)
                && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean containsEntries(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isPresent();
        } catch (IOException exception) {
            return false;
        }
    }

    private Path normalize(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("路径不能为空");
        }
        return path.toAbsolutePath().normalize();
    }

    private static boolean isWindowsOperatingSystem() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("windows");
    }

    private record NativePackageDescriptor(String directoryPrefix, Path modulePath) {

        private NativePackageDescriptor(String directoryPrefix, String modulePath) {
            this(directoryPrefix, Path.of(modulePath));
        }
    }
}
