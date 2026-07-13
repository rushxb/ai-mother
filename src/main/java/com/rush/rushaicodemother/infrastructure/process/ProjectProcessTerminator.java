package com.rush.rushaicodemother.infrastructure.process;

import com.rush.rushaicodemother.config.ExternalProcessProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 项目相关外部进程终止器。
 *
 * <p>只通过 {@link ProcessHandle} 操作已确认属于目标项目的 Node 工具进程，
 * 不调用 shell、WMIC 或按进程名全局终止，避免影响同机其他项目。</p>
 */
@Slf4j
@Component
public class ProjectProcessTerminator {

    private static final Set<String> ALLOWED_EXECUTABLE_NAMES = Set.of(
            "node", "node.exe",
            "pnpm", "pnpm.cmd", "pnpm.exe",
            "vite", "vite.cmd", "vite.exe",
            "esbuild", "esbuild.exe"
    );
    private static final Set<String> ALLOWED_WRAPPER_EXECUTABLE_NAMES = Set.of("cmd", "cmd.exe");
    private static final Duration MAX_TERMINATION_GRACE_PERIOD = Duration.ofMinutes(5);

    private final Duration gracePeriod;

    @Autowired
    public ProjectProcessTerminator(ExternalProcessProperties properties) {
        this(properties.getTerminationGracePeriod());
    }

    ProjectProcessTerminator(Duration gracePeriod) {
        if (gracePeriod == null
                || gracePeriod.isZero()
                || gracePeriod.isNegative()
                || gracePeriod.compareTo(MAX_TERMINATION_GRACE_PERIOD) > 0) {
            throw new IllegalArgumentException("进程终止宽限期必须大于 0 且不超过 5 分钟");
        }
        this.gracePeriod = gracePeriod;
    }

    /** 终止指定进程的全部后代，再终止父进程。 */
    public boolean terminate(Process process) {
        if (process == null) {
            return false;
        }
        return terminateTree(process.toHandle());
    }

    /**
     * 终止命令行中明确包含项目规范路径，且可执行工具属于白名单的进程。
     *
     * @return 被识别并执行终止的进程数量
     */
    public int terminateProjectProcesses(Path projectDirectory) {
        Path normalizedProject = normalizeProjectPath(projectDirectory);
        long currentProcessId = ProcessHandle.current().pid();
        List<ProcessHandle> matchingProcesses = ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(handle -> handle.pid() != currentProcessId)
                .filter(handle -> isProjectProcess(handle.info(), normalizedProject))
                .toList();

        int terminatedCount = 0;
        for (ProcessHandle processHandle : matchingProcesses) {
            if (terminateTree(processHandle)) {
                terminatedCount++;
            }
        }
        if (terminatedCount > 0) {
            log.info("已终止项目相关进程: project={}, count={}", normalizedProject, terminatedCount);
        }
        return terminatedCount;
    }

    boolean isProjectProcess(ProcessHandle.Info processInfo, Path projectDirectory) {
        if (processInfo == null || projectDirectory == null) {
            return false;
        }
        String command = processInfo.command().orElse("");
        String[] arguments = processInfo.arguments().orElseGet(() -> new String[0]);
        String commandLine = processInfo.commandLine().orElseGet(
                () -> String.join(" ", combine(command, arguments))
        );
        return containsAllowedTool(command, arguments, commandLine)
                && containsProjectPath(commandLine, projectDirectory);
    }

    private boolean terminateTree(ProcessHandle root) {
        if (root == null) {
            return false;
        }
        List<ProcessHandle> descendants = root.descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .toList();
        List<ProcessHandle> tree = new ArrayList<>(descendants.size() + 1);
        tree.addAll(descendants);
        tree.add(root);

        boolean hadLiveProcess = tree.stream().anyMatch(ProcessHandle::isAlive);
        tree.stream().filter(ProcessHandle::isAlive).forEach(this::destroyQuietly);
        awaitExit(tree, gracePeriod);
        tree.stream().filter(ProcessHandle::isAlive).forEach(this::destroyForciblyQuietly);
        awaitExit(tree, gracePeriod);

        boolean terminated = tree.stream().noneMatch(ProcessHandle::isAlive);
        if (!terminated) {
            List<Long> remainingProcessIds = tree.stream()
                    .filter(ProcessHandle::isAlive)
                    .map(ProcessHandle::pid)
                    .toList();
            log.warn("进程树未能完全终止: rootPid={}, remaining={}", root.pid(), remainingProcessIds);
        }
        return hadLiveProcess && terminated;
    }

    private void awaitExit(List<ProcessHandle> processes, Duration timeout) {
        long startedAtNanos = System.nanoTime();
        long timeoutNanos = timeout.toNanos();
        boolean interrupted = Thread.interrupted();
        try {
            for (ProcessHandle process : processes) {
                if (!process.isAlive()) {
                    continue;
                }
                long elapsedNanos = System.nanoTime() - startedAtNanos;
                long remainingNanos = timeoutNanos - elapsedNanos;
                if (remainingNanos <= 0) {
                    return;
                }
                try {
                    process.onExit().get(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                    return;
                } catch (Exception exception) {
                    // 进程退出竞态、权限限制或等待超时会在后续强制终止阶段处理。
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void destroyQuietly(ProcessHandle process) {
        try {
            process.destroy();
        } catch (RuntimeException exception) {
            log.debug("请求结束进程失败: pid={}, error={}", process.pid(), exception.getMessage());
        }
    }

    private void destroyForciblyQuietly(ProcessHandle process) {
        try {
            process.destroyForcibly();
        } catch (RuntimeException exception) {
            log.debug("强制结束进程失败: pid={}, error={}", process.pid(), exception.getMessage());
        }
    }

    private Path normalizeProjectPath(Path projectDirectory) {
        if (projectDirectory == null) {
            throw new IllegalArgumentException("项目目录不能为空");
        }
        Path normalized = projectDirectory.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            return normalized;
        }
        try {
            return normalized.toRealPath();
        } catch (IOException exception) {
            return normalized;
        }
    }

    private boolean containsAllowedTool(String command, String[] arguments, String commandLine) {
        String executableName = fileName(command);
        if (ALLOWED_EXECUTABLE_NAMES.contains(executableName)) {
            return true;
        }
        if (!ALLOWED_WRAPPER_EXECUTABLE_NAMES.contains(executableName)) {
            return false;
        }
        for (String argument : arguments) {
            if (ALLOWED_EXECUTABLE_NAMES.contains(fileName(argument))) {
                return true;
            }
        }
        for (String commandLineToken : commandLine.split("[\\s\\\"]+")) {
            if (ALLOWED_EXECUTABLE_NAMES.contains(fileName(commandLineToken))) {
                return true;
            }
        }
        return false;
    }

    private String fileName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.strip().replace("\"", "").replace('\\', '/');
        int separatorIndex = normalized.lastIndexOf('/');
        String fileName = separatorIndex >= 0 ? normalized.substring(separatorIndex + 1) : normalized;
        return fileName.toLowerCase(Locale.ROOT);
    }

    private boolean containsProjectPath(String commandLine, Path projectDirectory) {
        String normalizedCommandLine = normalizeForComparison(commandLine);
        String normalizedProjectPath = normalizeForComparison(projectDirectory.toString());
        if (normalizedProjectPath.isBlank()) {
            return false;
        }
        int matchIndex = normalizedCommandLine.indexOf(normalizedProjectPath);
        while (matchIndex >= 0) {
            int beforeIndex = matchIndex - 1;
            int afterIndex = matchIndex + normalizedProjectPath.length();
            boolean safeBefore = beforeIndex < 0 || isPathBoundary(normalizedCommandLine.charAt(beforeIndex), true);
            boolean safeAfter = afterIndex >= normalizedCommandLine.length()
                    || isPathBoundary(normalizedCommandLine.charAt(afterIndex), false);
            if (safeBefore && safeAfter) {
                return true;
            }
            matchIndex = normalizedCommandLine.indexOf(normalizedProjectPath, matchIndex + 1);
        }
        return false;
    }

    private boolean isPathBoundary(char character, boolean beforePath) {
        if (Character.isWhitespace(character) || character == '"' || character == '\'') {
            return true;
        }
        return beforePath
                ? character == '=' || character == '('
                : character == '/' || character == ';' || character == ',' || character == ')';
    }

    private String normalizeForComparison(String value) {
        return value == null
                ? ""
                : value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private List<String> combine(String command, String[] arguments) {
        List<String> parts = new ArrayList<>(arguments.length + 1);
        if (command != null && !command.isBlank()) {
            parts.add(command);
        }
        for (String argument : arguments) {
            if (argument != null) {
                parts.add(argument);
            }
        }
        return parts;
    }
}
