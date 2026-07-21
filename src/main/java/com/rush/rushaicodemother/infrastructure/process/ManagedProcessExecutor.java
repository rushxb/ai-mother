package com.rush.rushaicodemother.infrastructure.process;

import com.rush.rushaicodemother.infrastructure.sandbox.GeneratedCodeProcessSandbox;
import com.rush.rushaicodemother.infrastructure.sandbox.HostLocalGeneratedCodeProcessSandbox;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxProcessPlan;
import com.rush.rushaicodemother.monitor.GeneratedCodeSandboxMetricsCollector;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 外部进程统一执行内核，负责输出排空、资源上限、总超时、空闲超时、中断和进程树回收。
 */
@Slf4j
@Component
public class ManagedProcessExecutor {

    private static final Duration MAX_WAIT_POLL_INTERVAL = Duration.ofMillis(250);

    private final ProjectProcessTerminator processTerminator;
    private final ProcessStarter processStarter;
    private final GeneratedCodeProcessSandbox processSandbox;
    private final GeneratedCodeSandboxMetricsCollector sandboxMetrics;
    private final Tracer tracer;

    @Autowired
    public ManagedProcessExecutor(ProjectProcessTerminator processTerminator,
                                  GeneratedCodeProcessSandbox processSandbox,
                                  GeneratedCodeSandboxMetricsCollector sandboxMetrics,
                                  Tracer tracer) {
        this(processTerminator, ProcessBuilder::start, processSandbox, sandboxMetrics, tracer);
    }

    public ManagedProcessExecutor(ProjectProcessTerminator processTerminator,
                                  GeneratedCodeProcessSandbox processSandbox,
                                  GeneratedCodeSandboxMetricsCollector sandboxMetrics) {
        this(processTerminator, ProcessBuilder::start, processSandbox, sandboxMetrics, Tracer.NOOP);
    }

    public ManagedProcessExecutor(ProjectProcessTerminator processTerminator) {
        this(processTerminator, ProcessBuilder::start, new HostLocalGeneratedCodeProcessSandbox(),
                GeneratedCodeSandboxMetricsCollector.noOp(), Tracer.NOOP);
    }

    ManagedProcessExecutor(
            ProjectProcessTerminator processTerminator,
            ProcessStarter processStarter
    ) {
        this(processTerminator, processStarter, new HostLocalGeneratedCodeProcessSandbox(),
                GeneratedCodeSandboxMetricsCollector.noOp(), Tracer.NOOP);
    }

    ManagedProcessExecutor(
            ProjectProcessTerminator processTerminator,
            ProcessStarter processStarter,
            GeneratedCodeProcessSandbox processSandbox
    ) {
        this(processTerminator, processStarter, processSandbox,
                GeneratedCodeSandboxMetricsCollector.noOp(), Tracer.NOOP);
    }

    ManagedProcessExecutor(
            ProjectProcessTerminator processTerminator,
            ProcessStarter processStarter,
            GeneratedCodeProcessSandbox processSandbox,
            GeneratedCodeSandboxMetricsCollector sandboxMetrics
    ) {
        this(processTerminator, processStarter, processSandbox, sandboxMetrics, Tracer.NOOP);
    }

    ManagedProcessExecutor(
            ProjectProcessTerminator processTerminator,
            ProcessStarter processStarter,
            GeneratedCodeProcessSandbox processSandbox,
            GeneratedCodeSandboxMetricsCollector sandboxMetrics,
            Tracer tracer
    ) {
        this.processTerminator = processTerminator;
        this.processStarter = processStarter;
        this.processSandbox = processSandbox;
        this.sandboxMetrics = sandboxMetrics;
        this.tracer = tracer;
    }

    public ManagedProcessResult execute(ManagedProcessRequest request) {
        Span parent = tracer.currentSpan();
        if (parent == null) {
            return executeInternal(request);
        }
        String category = normalizeLogValue(request == null ? null : request.logCategory(), "external-process")
                .replaceAll("[^A-Za-z0-9_.-]", "_");
        Span span = tracer.spanBuilder()
                .name("generated_code.process." + category)
                .kind(Span.Kind.CLIENT)
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            ManagedProcessResult result = executeInternal(request);
            span.tag("process.status", result.status().name().toLowerCase());
            span.tag("process.success", result.exitedSuccessfully());
            if (!result.exitedSuccessfully()) {
                span.event("generated_code.process.failed");
            }
            return result;
        } catch (RuntimeException | Error failure) {
            span.error(failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    private ManagedProcessResult executeInternal(ManagedProcessRequest request) {
        long startedAtNanos = System.nanoTime();
        validateRequest(request);
        Path workingDirectory = normalizeWorkingDirectory(request.workingDirectory());
        SandboxProcessPlan processPlan = null;
        String commandText = displayCommand(request);
        Process process = null;
        ProcessOutputCollector stdoutCollector = null;
        ProcessOutputCollector stderrCollector = null;
        List<CompletableFuture<Void>> outputCompletions = List.of();

        try {
            processPlan = processSandbox.prepare(request, workingDirectory);
            log.info("执行外部进程: category={}, sandbox={}, command={}, context={}",
                    normalizeLogValue(request.logCategory(), "external-process"),
                    processPlan.backend(),
                    commandText,
                    normalizeLogValue(request.logContext(), "unknown"));
            ProcessBuilder processBuilder = new ProcessBuilder(processPlan.hostCommand());
            processBuilder.directory(processPlan.hostWorkingDirectory().toFile());
            processBuilder.redirectErrorStream(request.redirectErrorStream());
            processBuilder.environment().putAll(processPlan.hostEnvironment());
            processPlan.hostEnvironmentVariablesToRemove().forEach(processBuilder.environment()::remove);

            process = processStarter.start(processBuilder);
            request.lifecycle().onStarted(process);
            stdoutCollector = createCollector(request, "stdout");
            List<CompletableFuture<Void>> mutableCompletions = new ArrayList<>(2);
            mutableCompletions.add(stdoutCollector.start(
                    process.getInputStream(),
                    process.pid(),
                    "stdout"
            ));
            if (!request.redirectErrorStream()) {
                stderrCollector = createCollector(request, "stderr");
                mutableCompletions.add(stderrCollector.start(
                        process.getErrorStream(),
                        process.pid(),
                        "stderr"
                ));
            }
            outputCompletions = List.copyOf(mutableCompletions);

            WaitOutcome waitOutcome = waitForProcess(
                    process,
                    stdoutCollector,
                    stderrCollector,
                    request
            );
            if (!waitOutcome.completed()) {
                processTerminator.terminate(process);
                awaitOutputCompletion(outputCompletions, request.outputDrainTimeout());
                return measuredResult(result(
                        waitOutcome.status(),
                        commandText,
                        null,
                        stdoutCollector,
                        stderrCollector,
                        waitOutcome.errorDetail()
                ), processPlan, request, startedAtNanos);
            }

            awaitOutputCompletion(outputCompletions, request.outputDrainTimeout());
            return measuredResult(result(
                    ManagedProcessResult.Status.COMPLETED,
                    commandText,
                    process.exitValue(),
                    stdoutCollector,
                    stderrCollector,
                    null
            ), processPlan, request, startedAtNanos);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            terminateAndDrain(process, outputCompletions, request.outputDrainTimeout());
            return measuredResult(result(
                    ManagedProcessResult.Status.INTERRUPTED,
                    commandText,
                    null,
                    stdoutCollector,
                    stderrCollector,
                    "外部进程执行线程被中断"
            ), processPlan, request, startedAtNanos);
        } catch (IOException | RuntimeException exception) {
            terminateAndDrain(process, outputCompletions, request.outputDrainTimeout());
            log.error("启动或执行外部进程失败: command={}, exceptionType={}",
                    commandText, exception.getClass().getName());
            return measuredResult(result(
                    ManagedProcessResult.Status.START_FAILED,
                    commandText,
                    null,
                    stdoutCollector,
                    stderrCollector,
                    "外部进程启动失败，请检查运行环境和命令配置"
            ), processPlan, request, startedAtNanos);
        } finally {
            notifyFinished(request.lifecycle(), process, commandText);
            cleanupSandbox(processPlan, commandText);
        }
    }

    private void cleanupSandbox(SandboxProcessPlan processPlan, String commandText) {
        if (processPlan == null) {
            return;
        }
        try {
            processSandbox.cleanup(processPlan);
            if (!processPlan.cleanupResourceId().isBlank()) {
                sandboxMetrics.recordCleanup(processPlan.backend(), "success");
            }
        } catch (RuntimeException exception) {
            sandboxMetrics.recordCleanup(processPlan.backend(), "failure");
            log.warn("Sandbox 清理失败: backend={}, command={}, exceptionType={}",
                    processPlan.backend(), commandText, exception.getClass().getName());
        }
    }

    private ManagedProcessResult measuredResult(
            ManagedProcessResult result,
            SandboxProcessPlan processPlan,
            ManagedProcessRequest request,
            long startedAtNanos
    ) {
        sandboxMetrics.recordExecution(
                processPlan == null ? "unknown" : processPlan.backend(),
                request.logCategory(),
                result.status().name(),
                Duration.ofNanos(Math.max(0, System.nanoTime() - startedAtNanos))
        );
        return result;
    }

    private void notifyFinished(
            ManagedProcessLifecycle lifecycle,
            Process process,
            String commandText
    ) {
        if (process == null) {
            return;
        }
        try {
            lifecycle.onFinished(process);
        } catch (RuntimeException exception) {
            log.warn("外部进程结束回调失败: command={}, exceptionType={}",
                    commandText, exception.getClass().getName());
        }
    }

    private WaitOutcome waitForProcess(
            Process process,
            ProcessOutputCollector stdoutCollector,
            ProcessOutputCollector stderrCollector,
            ManagedProcessRequest request
    ) throws InterruptedException {
        long startedAt = System.nanoTime();
        long lastHeartbeatAt = startedAt;
        long timeoutNanos = request.timeout().toNanos();
        Long idleTimeoutNanos = request.idleTimeout() == null
                ? null
                : request.idleTimeout().toNanos();
        long heartbeatNanos = request.heartbeatInterval().toNanos();

        while (true) {
            if (request.cancellationRequested().getAsBoolean()) {
                return WaitOutcome.failed(
                        ManagedProcessResult.Status.INTERRUPTED,
                        "外部进程执行已取消"
                );
            }
            long now = System.nanoTime();
            long elapsedNanos = now - startedAt;
            long idleNanos = idleNanos(now, stdoutCollector, stderrCollector);
            if (elapsedNanos >= timeoutNanos) {
                logTimeout(
                        "外部进程总超时",
                        elapsedNanos,
                        idleNanos,
                        stdoutCollector,
                        stderrCollector,
                        request.outputLogPolicy()
                );
                return WaitOutcome.failed(
                        ManagedProcessResult.Status.TIMED_OUT,
                        "外部进程执行超过总超时 " + request.timeout()
                );
            }
            if (idleTimeoutNanos != null && idleNanos >= idleTimeoutNanos) {
                logTimeout(
                        "外部进程长时间无输出",
                        elapsedNanos,
                        idleNanos,
                        stdoutCollector,
                        stderrCollector,
                        request.outputLogPolicy()
                );
                return WaitOutcome.failed(
                        ManagedProcessResult.Status.IDLE_TIMED_OUT,
                        "外部进程持续无输出超过 " + request.idleTimeout()
                );
            }
            if (now - lastHeartbeatAt >= heartbeatNanos) {
                lastHeartbeatAt = now;
                logHeartbeat(elapsedNanos, idleNanos, stdoutCollector, stderrCollector, request.outputLogPolicy());
            }

            long remainingNanos = timeoutNanos - elapsedNanos;
            if (idleTimeoutNanos != null) {
                remainingNanos = Math.min(remainingNanos, idleTimeoutNanos - idleNanos);
            }
            long waitNanos = Math.min(MAX_WAIT_POLL_INTERVAL.toNanos(), remainingNanos);
            long waitMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(Math.max(1, waitNanos)));
            if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                return WaitOutcome.completedSuccessfully();
            }
        }
    }

    private ProcessOutputCollector createCollector(ManagedProcessRequest request, String streamName) {
        String context = normalizeLogValue(request.logContext(), "unknown");
        return new ProcessOutputCollector(
                normalizeLogValue(request.logCategory(), "external-process"),
                context + " " + streamName,
                request.maxOutputLength(),
                request.outputCharset(),
                request.outputLogPolicy()
        );
    }

    private ManagedProcessResult result(
            ManagedProcessResult.Status status,
            String command,
            Integer exitCode,
            ProcessOutputCollector stdoutCollector,
            ProcessOutputCollector stderrCollector,
            String errorDetail
    ) {
        return new ManagedProcessResult(
                status,
                command,
                exitCode,
                output(stdoutCollector),
                output(stderrCollector),
                errorDetail
        );
    }

    private void terminateAndDrain(
            Process process,
            List<CompletableFuture<Void>> outputCompletions,
            Duration outputDrainTimeout
    ) {
        if (process != null) {
            processTerminator.terminate(process);
        }
        awaitOutputCompletion(outputCompletions, outputDrainTimeout);
    }

    private void awaitOutputCompletion(
            List<CompletableFuture<Void>> outputCompletions,
            Duration outputDrainTimeout
    ) {
        for (CompletableFuture<Void> outputCompletion : outputCompletions) {
            ProcessOutputCollector.awaitCompletionPreservingInterrupt(
                    outputCompletion,
                    outputDrainTimeout
            );
        }
    }

    private long idleNanos(
            long now,
            ProcessOutputCollector stdoutCollector,
            ProcessOutputCollector stderrCollector
    ) {
        long stdoutIdle = stdoutCollector.idleNanos(now);
        return stderrCollector == null
                ? stdoutIdle
                : Math.min(stdoutIdle, stderrCollector.idleNanos(now));
    }

    private String outputTail(
            ProcessOutputCollector stdoutCollector,
            ProcessOutputCollector stderrCollector
    ) {
        String stdoutTail = stdoutCollector.tailForLog();
        if (stderrCollector == null || "(暂无输出)".equals(stderrCollector.tailForLog())) {
            return stdoutTail;
        }
        return stdoutTail + " | stderr: " + stderrCollector.tailForLog();
    }

    private void logTimeout(
            String message,
            long elapsedNanos,
            long idleNanos,
            ProcessOutputCollector stdoutCollector,
            ProcessOutputCollector stderrCollector,
            ManagedProcessOutputLogPolicy outputLogPolicy
    ) {
        if (!outputLogPolicy.isHeartbeatTailEnabled()) {
            log.warn("{}: elapsed={}s, idle={}s",
                    message,
                    TimeUnit.NANOSECONDS.toSeconds(elapsedNanos),
                    TimeUnit.NANOSECONDS.toSeconds(idleNanos));
            return;
        }
        log.warn("{}: elapsed={}s, idle={}s, tail={}",
                message,
                TimeUnit.NANOSECONDS.toSeconds(elapsedNanos),
                TimeUnit.NANOSECONDS.toSeconds(idleNanos),
                outputTail(stdoutCollector, stderrCollector));
    }

    private void logHeartbeat(
            long elapsedNanos,
            long idleNanos,
            ProcessOutputCollector stdoutCollector,
            ProcessOutputCollector stderrCollector,
            ManagedProcessOutputLogPolicy outputLogPolicy
    ) {
        if (!outputLogPolicy.isHeartbeatTailEnabled()) {
            log.info("外部进程执行中: elapsed={}s, idle={}s",
                    TimeUnit.NANOSECONDS.toSeconds(elapsedNanos),
                    TimeUnit.NANOSECONDS.toSeconds(idleNanos));
            return;
        }
        log.info("外部进程执行中: elapsed={}s, idle={}s, tail={}",
                TimeUnit.NANOSECONDS.toSeconds(elapsedNanos),
                TimeUnit.NANOSECONDS.toSeconds(idleNanos),
                outputTail(stdoutCollector, stderrCollector));
    }

    private void validateRequest(ManagedProcessRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("外部进程请求不能为空");
        }
        if (request.command() == null
                || request.command().isEmpty()
                || request.command().stream().anyMatch(part -> part == null || part.isBlank())) {
            throw new IllegalArgumentException("外部进程命令参数不能为空");
        }
        validatePositiveDuration(request.timeout(), "外部进程总超时");
        if (request.idleTimeout() != null) {
            validatePositiveDuration(request.idleTimeout(), "外部进程空闲超时");
        }
        validatePositiveDuration(request.heartbeatInterval(), "外部进程心跳间隔");
        validatePositiveDuration(request.outputDrainTimeout(), "外部进程输出收口超时");
        if (request.heartbeatInterval().compareTo(request.timeout()) >= 0) {
            throw new IllegalArgumentException("外部进程心跳间隔必须小于总超时");
        }
        if (request.maxOutputLength() <= 0) {
            throw new IllegalArgumentException("外部进程最大输出长度必须大于 0");
        }
    }

    private void validatePositiveDuration(Duration duration, String label) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
    }

    private Path normalizeWorkingDirectory(Path workingDirectory) {
        if (workingDirectory == null) {
            throw new IllegalArgumentException("外部进程工作目录不能为空");
        }
        Path normalized = workingDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("外部进程工作目录不存在: " + normalized);
        }
        try {
            return normalized.toRealPath();
        } catch (IOException exception) {
            return normalized;
        }
    }

    private String output(ProcessOutputCollector collector) {
        return collector == null ? "" : collector.output();
    }

    private String normalizeLogValue(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        return SensitiveLogSanitizer.sanitize(normalized);
    }

    private String displayCommand(ManagedProcessRequest request) {
        if (request.displayCommand() != null && !request.displayCommand().isBlank()) {
            return SensitiveLogSanitizer.sanitize(request.displayCommand().trim());
        }
        StringBuilder display = new StringBuilder();
        boolean redactNext = false;
        for (String argument : request.command()) {
            if (!display.isEmpty()) {
                display.append(' ');
            }
            String normalized = argument.trim();
            if (redactNext) {
                display.append("***");
                redactNext = false;
                continue;
            }
            int equalsIndex = normalized.indexOf('=');
            String optionName = equalsIndex >= 0 ? normalized.substring(0, equalsIndex) : normalized;
            if (isSensitiveOption(optionName)) {
                if (equalsIndex >= 0) {
                    display.append(optionName).append("=***");
                } else {
                    display.append(optionName);
                    redactNext = true;
                }
                continue;
            }
            display.append(SensitiveLogSanitizer.sanitize(normalized));
        }
        return display.toString();
    }

    private boolean isSensitiveOption(String optionName) {
        String normalized = optionName.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("api-key")
                || normalized.contains("api_key")
                || normalized.contains("authorization")
                || normalized.contains("credential");
    }

    private record WaitOutcome(
            boolean completed,
            ManagedProcessResult.Status status,
            String errorDetail
    ) {

        private static WaitOutcome completedSuccessfully() {
            return new WaitOutcome(true, ManagedProcessResult.Status.COMPLETED, null);
        }

        private static WaitOutcome failed(ManagedProcessResult.Status status, String errorDetail) {
            return new WaitOutcome(false, status, errorDetail);
        }
    }
}
