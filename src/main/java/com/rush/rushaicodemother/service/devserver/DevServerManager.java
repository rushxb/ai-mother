package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.dependency.DependencyInstallResult;
import com.rush.rushaicodemother.service.dependency.ProjectDependencyInstaller;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 应用 Dev Server 生命周期编排器。
 *
 * <p>进程启动、端口保留、输出消费和项目定位分别由深模块承担；本类只维护
 * 应用会话状态，并保证启动、停止和失败补偿使用同一条生命周期路径。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevServerManager {

    private final DevServerRuntimeProperties properties;
    private final ProjectDependencyInstaller projectDependencyInstaller;
    private final DevServerProjectLocator projectLocator;
    private final DevServerPortAllocator portAllocator;
    private final VisualEditorBootstrapInjector bootstrapInjector;
    private final DevServerProcessRunner processRunner;
    private final DevServerOutputHub outputHub;

    private final Map<Long, ManagedDevServerSession> sessions = new ConcurrentHashMap<>();
    private final ReentrantLock registryLock = new ReentrantLock();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /**
     * 启动应用的 Dev Server；成功返回时，进程已经在回环地址对应端口稳定就绪。
     *
     * <p>{@link DevServerStartResult#startedByCaller()} 为 {@code true} 时，当前调用者
     * 拥有该会话的失败补偿和停止责任；复用既有会话时不得由当前调用者停止。</p>
     */
    public DevServerStartResult startDevServer(App app, Long userId) {
        validateStartRequest(app, userId);
        Long appId = app.getId();
        if (shuttingDown.get()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "服务正在关闭，不能启动 Dev Server");
        }

        ManagedDevServerSession reusableSession = findReusableSession(appId);
        if (reusableSession != null) {
            bootstrapInjector.inject(reusableSession.projectDirectory());
            return DevServerStartResult.reused(reusableSession.port());
        }
        Path projectDirectory = projectLocator.locate(app);
        SessionRegistration registration = registerStartingSession(
                appId,
                userId,
                projectDirectory,
                app.getDevServerPort()
        );
        ManagedDevServerSession session = registration.session();
        if (!registration.created()) {
            bootstrapInjector.inject(session.projectDirectory());
            return DevServerStartResult.reused(session.port());
        }
        boolean started = false;
        try {
            ensureDependenciesInstalled(session);
            session.throwIfStopping();
            bootstrapInjector.inject(projectDirectory);

            DevServerProcessSession processSession = processRunner.start(
                    projectDirectory,
                    session.port(),
                    appId,
                    outputHub.sink(appId),
                    session::isStopRequested
            );
            if (!session.attach(processSession)) {
                processRunner.stop(processSession);
                throw new DevServerStartException(
                        DevServerStartException.Reason.CANCELLED,
                        "Dev Server 启动已取消"
                );
            }
            watchProcessExit(session, processSession);
            if (!session.isRunning()) {
                throw new DevServerStartException(
                        DevServerStartException.Reason.PROCESS_EXITED,
                        "Dev Server 就绪后进程立即退出"
                );
            }
            started = true;
            log.info("应用 {} 的 Dev Server 已启动，port={}", appId, session.port());
            return DevServerStartResult.started(session.port());
        } catch (DevServerStartException exception) {
            if (exception.reason() == DevServerStartException.Reason.CANCELLED) {
                log.debug("Dev Server 启动已取消，appId={}, projectDirectory={}", appId, projectDirectory);
            } else {
                log.warn(
                        "Dev Server 启动失败，appId={}, projectDirectory={}, reason={}",
                        appId,
                        projectDirectory,
                        exception.reason(),
                        exception
                );
            }
            throw mapStartFailure(exception);
        } finally {
            session.completeStartup();
            if (!started) {
                cleanupSession(session, "启动失败");
            }
        }
    }

    /**
     * 停止应用的 Dev Server。该方法在返回前等待进程树和输出消费任务完成收口。
     */
    public void stopDevServer(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        }
        ManagedDevServerSession session = sessions.get(appId);
        if (session == null) {
            portAllocator.release(appId);
            return;
        }
        stopSession(session, true);
    }

    public boolean isRunning(Long appId) {
        if (appId == null) {
            return false;
        }
        ManagedDevServerSession session = sessions.get(appId);
        if (session == null) {
            return false;
        }
        if (session.isRunning()) {
            return true;
        }
        if (session.hasExitedProcess()) {
            cleanupSession(session, "进程已退出");
        }
        return false;
    }

    /**
     * 返回当前确实处于运行状态的端口；启动中、停止中或进程已退出时返回 {@code null}。
     */
    public Integer getPort(Long appId) {
        if (appId == null) {
            return null;
        }
        ManagedDevServerSession session = sessions.get(appId);
        if (session == null) {
            return null;
        }
        if (session.isRunning()) {
            return session.port();
        }
        if (session.hasExitedProcess()) {
            cleanupSession(session, "读取端口时发现进程已退出");
        }
        return null;
    }

    public void registerErrorCollector(Long appId, DevServerErrorCollector collector) {
        outputHub.registerCollector(appId, collector);
        log.debug("已注册 Dev Server 错误收集器，appId={}", appId);
    }

    public void unregisterErrorCollector(Long appId, DevServerErrorCollector collector) {
        outputHub.unregisterCollector(appId, collector);
        log.debug("已注销 Dev Server 错误收集器，appId={}", appId);
    }

    public List<String> getRecentOutputLines(Long appId, int limit) {
        return outputHub.recentLines(appId, limit);
    }

    @PreDestroy
    public void destroy() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        log.info("正在关闭全部 Dev Server 会话，count={}", sessions.size());
        List<ManagedDevServerSession> snapshot = new ArrayList<>(sessions.values());
        for (ManagedDevServerSession session : snapshot) {
            try {
                stopSession(session, false);
            } catch (RuntimeException exception) {
                log.error("关闭应用 {} 的 Dev Server 失败", session.appId(), exception);
                processRunner.terminateProjectProcesses(session.projectDirectory());
                cleanupSession(session, "服务关闭强制清理");
            }
        }
        sessions.clear();
        portAllocator.clear();
        outputHub.clear();
    }

    private ManagedDevServerSession findReusableSession(Long appId) {
        registryLock.lock();
        try {
            ManagedDevServerSession existing = sessions.get(appId);
            if (existing == null) {
                return null;
            }
            if (existing.isRunning()) {
                return existing;
            }
            if (existing.hasExitedProcess()) {
                sessions.remove(appId, existing);
                existing.markStopped();
                portAllocator.release(appId);
                return null;
            }
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    existing.isStopRequested() ? "Dev Server 正在停止" : "Dev Server 正在启动"
            );
        } finally {
            registryLock.unlock();
        }
    }

    private SessionRegistration registerStartingSession(
            Long appId,
            Long userId,
            Path projectDirectory,
            Integer preferredPort
    ) {
        registryLock.lock();
        try {
            if (shuttingDown.get()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "服务正在关闭，不能启动 Dev Server");
            }
            ManagedDevServerSession existing = sessions.get(appId);
            if (existing != null) {
                if (existing.isRunning()) {
                    return new SessionRegistration(existing, false);
                }
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        existing.isStopRequested() ? "Dev Server 正在停止" : "Dev Server 正在启动"
                );
            }
            long userSessionCount = sessions.values().stream()
                    .filter(session -> session.userId().equals(userId))
                    .filter(session -> !session.isStopped())
                    .count();
            if (userSessionCount >= properties.getMaxServersPerUser()) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "单用户最多同时运行 " + properties.getMaxServersPerUser() + " 个 Dev Server"
                );
            }

            int port = portAllocator.reserve(appId, preferredPort);
            ManagedDevServerSession session = new ManagedDevServerSession(
                    appId,
                    userId,
                    projectDirectory,
                    port
            );
            sessions.put(appId, session);
            outputHub.prepare(appId);
            return new SessionRegistration(session, true);
        } finally {
            registryLock.unlock();
        }
    }

    private void ensureDependenciesInstalled(ManagedDevServerSession session) {
        DependencyInstallResult result = projectDependencyInstaller.ensureInstalled(session.projectDirectory());
        if (result == null) {
            throw new DevServerStartException(
                    DevServerStartException.Reason.DEPENDENCY_INSTALL_FAILED,
                    "Dependency installer returned no result"
            );
        }
        if (result.success()) {
            return;
        }
        if (session.isStopRequested() || result.status() == DependencyInstallResult.Status.CANCELLED) {
            throw new DevServerStartException(
                    DevServerStartException.Reason.CANCELLED,
                    "Dev Server 启动已取消"
            );
        }
        if (result.status() == DependencyInstallResult.Status.INTERRUPTED) {
            throw new DevServerStartException(
                    DevServerStartException.Reason.INTERRUPTED,
                    "Dependency installation was interrupted"
            );
        }
        throw new DevServerStartException(
                DevServerStartException.Reason.DEPENDENCY_INSTALL_FAILED,
                "Dependency installation failed: status=" + result.status()
                        + ", detail=" + result.errorDetail()
        );
    }

    private void stopSession(ManagedDevServerSession session, boolean failOnTimeout) {
        session.requestStop();
        RuntimeException cleanupFailure = null;
        try {
            projectDependencyInstaller.cancel(session.projectDirectory());
        } catch (RuntimeException exception) {
            cleanupFailure = exception;
            log.warn("取消应用 {} 的依赖安装失败", session.appId(), exception);
        }

        StartupAwaitResult startupAwaitResult = session.awaitStartup(properties.getStopTimeout());
        if (startupAwaitResult != StartupAwaitResult.COMPLETED) {
            processRunner.terminateProjectProcesses(session.projectDirectory());
            if (failOnTimeout) {
                String message = startupAwaitResult == StartupAwaitResult.INTERRUPTED
                        ? "等待 Dev Server 启动任务停止时被中断"
                        : "等待 Dev Server 启动任务停止超时";
                BusinessException stopFailure = new BusinessException(ErrorCode.SYSTEM_ERROR, message);
                if (cleanupFailure != null) {
                    stopFailure.addSuppressed(cleanupFailure);
                }
                throw stopFailure;
            }
        }

        DevServerProcessSession processSession = session.processSession();
        if (processSession != null) {
            try {
                processRunner.stop(processSession);
            } catch (RuntimeException exception) {
                if (cleanupFailure == null) {
                    cleanupFailure = exception;
                } else {
                    cleanupFailure.addSuppressed(exception);
                }
            }
        }
        cleanupSession(session, "主动停止");
        log.info("应用 {} 的 Dev Server 已停止", session.appId());

        if (cleanupFailure != null) {
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "Dev Server 已停止，但部分清理操作失败",
                    cleanupFailure
            );
        }
    }

    private void watchProcessExit(
            ManagedDevServerSession session,
            DevServerProcessSession processSession
    ) {
        processSession.process().onExit().thenRun(() -> {
            processRunner.awaitOutput(processSession);
            cleanupSession(session, "进程自然退出");
            log.info("应用 {} 的 Dev Server 进程已退出，exitCode={}",
                    session.appId(), safeExitCode(processSession.process()));
        });
    }

    private void cleanupSession(ManagedDevServerSession session, String reason) {
        registryLock.lock();
        try {
            if (sessions.remove(session.appId(), session)) {
                portAllocator.release(session.appId());
                log.debug("已清理 Dev Server 会话，appId={}, reason={}", session.appId(), reason);
            }
            session.markStopped();
        } finally {
            registryLock.unlock();
        }
    }

    private BusinessException mapStartFailure(DevServerStartException exception) {
        return switch (exception.reason()) {
            case INVALID_LAUNCHER -> new BusinessException(
                    ErrorCode.NOT_FOUND_ERROR,
                    "项目缺少可用的 Dev Server 启动器",
                    exception
            );
            case DEPENDENCY_INSTALL_FAILED -> new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "项目依赖安装失败，请稍后重试",
                    exception
            );
            case PROCESS_START_FAILED -> new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "Dev Server 启动失败，请稍后重试",
                    exception
            );
            case PROCESS_EXITED -> new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "Dev Server 启动后异常退出",
                    exception
            );
            case STARTUP_TIMEOUT -> new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "Dev Server 启动超时，请稍后重试",
                    exception
            );
            case CANCELLED -> new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Dev Server 启动已取消",
                    exception
            );
            case INTERRUPTED -> new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Dev Server 启动被中断",
                    exception
            );
        };
    }

    private void validateStartRequest(App app, Long userId) {
        if (app == null || app.getId() == null || app.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        }
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户 ID 不能为空");
        }
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT
                && codeGenType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "仅 Vue 项目支持 Dev Server 预览");
        }
    }

    private int safeExitCode(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException exception) {
            return -1;
        }
    }

    private record SessionRegistration(ManagedDevServerSession session, boolean created) {
    }

    private enum StartupAwaitResult {
        COMPLETED,
        TIMED_OUT,
        INTERRUPTED
    }

    private enum SessionState {
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED
    }

    private static final class ManagedDevServerSession {

        private final Long appId;
        private final Long userId;
        private final Path projectDirectory;
        private final int port;
        private final CompletableFuture<Void> startupCompletion = new CompletableFuture<>();
        private SessionState state = SessionState.STARTING;
        private DevServerProcessSession processSession;

        private ManagedDevServerSession(
                Long appId,
                Long userId,
                Path projectDirectory,
                int port
        ) {
            this.appId = appId;
            this.userId = userId;
            this.projectDirectory = projectDirectory;
            this.port = port;
        }

        private Long appId() {
            return appId;
        }

        private Long userId() {
            return userId;
        }

        private Path projectDirectory() {
            return projectDirectory;
        }

        private int port() {
            return port;
        }

        private synchronized boolean attach(DevServerProcessSession newProcessSession) {
            if (state != SessionState.STARTING || !newProcessSession.process().isAlive()) {
                return false;
            }
            processSession = newProcessSession;
            state = SessionState.RUNNING;
            return true;
        }

        private synchronized void requestStop() {
            if (state != SessionState.STOPPED) {
                state = SessionState.STOPPING;
            }
        }

        private synchronized boolean isStopRequested() {
            return state == SessionState.STOPPING || state == SessionState.STOPPED;
        }

        private synchronized void throwIfStopping() {
            if (isStopRequested()) {
                throw new DevServerStartException(
                        DevServerStartException.Reason.CANCELLED,
                        "Dev Server 启动已取消"
                );
            }
        }

        private synchronized boolean isRunning() {
            return state == SessionState.RUNNING
                    && processSession != null
                    && processSession.process().isAlive();
        }

        private synchronized boolean hasExitedProcess() {
            return processSession != null && !processSession.process().isAlive();
        }

        private synchronized boolean isStopped() {
            return state == SessionState.STOPPED;
        }

        private synchronized DevServerProcessSession processSession() {
            return processSession;
        }

        private synchronized void markStopped() {
            state = SessionState.STOPPED;
        }

        private void completeStartup() {
            startupCompletion.complete(null);
        }

        private StartupAwaitResult awaitStartup(Duration timeout) {
            try {
                startupCompletion.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
                return StartupAwaitResult.COMPLETED;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return StartupAwaitResult.INTERRUPTED;
            } catch (ExecutionException exception) {
                return StartupAwaitResult.COMPLETED;
            } catch (TimeoutException exception) {
                return StartupAwaitResult.TIMED_OUT;
            }
        }
    }
}
