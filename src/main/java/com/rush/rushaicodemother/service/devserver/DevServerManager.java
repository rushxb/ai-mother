package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxProcessPlan;
import com.rush.rushaicodemother.service.dependency.DependencyInstallResult;
import com.rush.rushaicodemother.service.dependency.ProjectDependencyInstaller;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionClaimResult;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.LinkOption;
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
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 应用 Dev Server 生命周期编排器。
 *
 * <p>进程启动、端口保留、输出消费和项目定位分别由深模块承担；本类只维护
 * 应用会话状态，并保证启动、停止和失败补偿使用同一条生命周期路径。</p>
 */
@Slf4j
@Service
public class DevServerManager {

    private final DevServerRuntimeProperties properties;
    private final ProjectDependencyInstaller projectDependencyInstaller;
    private final DevServerProjectLocator projectLocator;
    private final DevServerPortAllocator portAllocator;
    private final VisualEditorBootstrapInjector bootstrapInjector;
    private final DevServerProcessRunner processRunner;
    private final DevServerOutputHub outputHub;
    private final DevServerSessionLeaseCoordinator leaseCoordinator;

    private final Map<Long, ManagedDevServerSession> sessions = new ConcurrentHashMap<>();
    private final ReentrantLock registryLock = new ReentrantLock();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    /**
     * 空闲判定使用的单调时钟。
     *
     * <p>刻意不用 {@link java.time.Clock}：空闲时长是「经过了多久」而不是「几点」，
     * 系统墙钟回拨会让基于 {@code Instant} 的判定瞬间误杀活跃会话。测试可注入固定推进的时间源。</p>
     */
    private final LongSupplier nanoTimeSource;

    /**
 * 创建开发服务器管理器实例并完成必要的依赖和初始状态设置。
 *
 * @param properties 配置属性
 * @param projectDependencyInstaller {@code projectDependencyInstaller} 对应的调用参数
 * @param projectLocator {@code projectLocator} 对应的调用参数
 * @param portAllocator {@code portAllocator} 对应的调用参数
 * @param bootstrapInjector {@code bootstrapInjector} 对应的调用参数
 * @param processRunner {@code processRunner} 对应的调用参数
 * @param outputHub {@code outputHub} 对应的调用参数
 * @param leaseCoordinator 租约协调器
 */
    @Autowired
    public DevServerManager(
            DevServerRuntimeProperties properties,
            ProjectDependencyInstaller projectDependencyInstaller,
            DevServerProjectLocator projectLocator,
            DevServerPortAllocator portAllocator,
            VisualEditorBootstrapInjector bootstrapInjector,
            DevServerProcessRunner processRunner,
            DevServerOutputHub outputHub,
            DevServerSessionLeaseCoordinator leaseCoordinator
    ) {
        this(properties, projectDependencyInstaller, projectLocator, portAllocator,
                bootstrapInjector, processRunner, outputHub, leaseCoordinator, System::nanoTime);
    }

    /** 创建开发服务器管理器实例，并允许注入空闲判定使用的单调时间源。 */
    DevServerManager(
            DevServerRuntimeProperties properties,
            ProjectDependencyInstaller projectDependencyInstaller,
            DevServerProjectLocator projectLocator,
            DevServerPortAllocator portAllocator,
            VisualEditorBootstrapInjector bootstrapInjector,
            DevServerProcessRunner processRunner,
            DevServerOutputHub outputHub,
            DevServerSessionLeaseCoordinator leaseCoordinator,
            LongSupplier nanoTimeSource
    ) {
        this.properties = properties;
        this.projectDependencyInstaller = projectDependencyInstaller;
        this.projectLocator = projectLocator;
        this.portAllocator = portAllocator;
        this.bootstrapInjector = bootstrapInjector;
        this.processRunner = processRunner;
        this.outputHub = outputHub;
        this.leaseCoordinator = leaseCoordinator;
        this.nanoTimeSource = nanoTimeSource;
    }

    /** 创建开发服务器管理器实例并完成必要的依赖和初始状态设置。 */
    DevServerManager(
            DevServerRuntimeProperties properties,
            ProjectDependencyInstaller projectDependencyInstaller,
            DevServerProjectLocator projectLocator,
            DevServerPortAllocator portAllocator,
            VisualEditorBootstrapInjector bootstrapInjector,
            DevServerProcessRunner processRunner,
            DevServerOutputHub outputHub
    ) {
        this(
                properties,
                projectDependencyInstaller,
                projectLocator,
                portAllocator,
                bootstrapInjector,
                processRunner,
                outputHub,
                DevServerSessionLeaseCoordinator.noOp()
        );
    }

    /** 创建开发服务器管理器实例，供空闲回收用例注入可控时间源。 */
    DevServerManager(
            DevServerRuntimeProperties properties,
            ProjectDependencyInstaller projectDependencyInstaller,
            DevServerProjectLocator projectLocator,
            DevServerPortAllocator portAllocator,
            VisualEditorBootstrapInjector bootstrapInjector,
            DevServerProcessRunner processRunner,
            DevServerOutputHub outputHub,
            LongSupplier nanoTimeSource
    ) {
        this(
                properties,
                projectDependencyInstaller,
                projectLocator,
                portAllocator,
                bootstrapInjector,
                processRunner,
                outputHub,
                DevServerSessionLeaseCoordinator.noOp(),
                nanoTimeSource
        );
    }

    /**
     * 启动应用的 Dev Server；成功返回时，进程已经在回环地址对应端口稳定就绪。
     *
     * <p>{@link DevServerStartResult#startedByCaller()} 为 {@code true} 时，当前调用者
     * 拥有该会话的失败补偿和停止责任；复用既有会话时不得由当前调用者停止。</p>
     */
    public DevServerStartResult startDevServer(App app, Long userId) {
        return startDevServerInternal(app, userId, null);
    }

    /**
     * 在任务范围的超时和取消控制下启动开发服务器。
     */
    public DevServerStartResult startDevServer(
            App app,
            Long userId,
            DevServerStartOptions startOptions
    ) {
        if (startOptions == null) {
            throw new IllegalArgumentException("Dev Server start options cannot be null");
        }
        return startDevServerInternal(app, userId, startOptions);
    }

    /** 启动开发服务器内部。 */
    private DevServerStartResult startDevServerInternal(
            App app,
            Long userId,
            DevServerStartOptions startOptions
    ) {
        validateStartRequest(app, userId);
        Long appId = app.getId();
        if (shuttingDown.get()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "服务正在关闭，不能启动 Dev Server");
        }

        throwIfExternallyCancelled(startOptions);

        // 保留原始定位器合约上的交互/遗留路径。除了保存
        // 对于没有代围栏的调用者的兼容性，这也可以防止
        // 空选项对象会默默地更改工作区解析语义。
        Path projectDirectory = startOptions == null
                ? projectLocator.locate(app)
                : projectLocator.locate(app, startOptions);
        Map<String, String> requestedEnvironment = startOptions == null
                ? Map.of()
                : startOptions.environmentOverrides();
        ManagedDevServerSession reusableSession = findReusableSession(
                appId,
                projectDirectory,
                requestedEnvironment
        );
        if (reusableSession != null) {
            DevServerSessionLeaseCoordinator.LeaseStatus leaseStatus = leaseCoordinator.renew(appId);
            if (leaseStatus != DevServerSessionLeaseCoordinator.LeaseStatus.RENEWED
                    && leaseStatus != DevServerSessionLeaseCoordinator.LeaseStatus.RETRYABLE_FAILURE) {
                stopSession(reusableSession, false);
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "Dev Server ownership is no longer available; please retry"
                );
            }
            throwIfExternallyCancelled(startOptions);
            bootstrapInjector.inject(reusableSession.projectDirectory());
            throwIfExternallyCancelled(startOptions);
            return DevServerStartResult.reused(reusableSession.port());
        }
        SessionRegistration registration = registerStartingSession(
                appId,
                userId,
                projectDirectory,
                app.getDevServerPort(),
                requestedEnvironment
        );
        ManagedDevServerSession session = registration.session();
        if (!registration.created()) {
            throwIfExternallyCancelled(startOptions);
            bootstrapInjector.inject(session.projectDirectory());
            throwIfExternallyCancelled(startOptions);
            return DevServerStartResult.reused(session.port());
        }
        boolean started = false;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            throwIfExternallyCancelled(startOptions);
            ensureDependenciesInstalled(session, startOptions);
            session.throwIfStopping();
            throwIfExternallyCancelled(startOptions);
            bootstrapInjector.inject(projectDirectory);

            DevServerProcessSession processSession = startProcess(
                    session, appId, startOptions);
            if (isExternallyCancelled(startOptions)) {
                processRunner.stop(processSession);
                throw new DevServerStartException(
                        DevServerStartException.Reason.CANCELLED,
                        "Dev Server startup was cancelled"
                );
            }
            if (!session.attach(processSession)) {
                processRunner.stop(processSession);
                throw new DevServerStartException(
                        DevServerStartException.Reason.CANCELLED,
                        "Dev Server 启动已取消"
                );
            }
            SandboxProcessPlan sandboxPlan = processSession.sandboxPlan();
            String sandboxBackend = sandboxPlan == null ? "host-local" : sandboxPlan.backend();
            List<String> cleanupResourceIds = sandboxPlan == null
                    ? List.of()
                    : sandboxPlan.cleanupResourceIds();
            if (!leaseCoordinator.markRunning(appId, sandboxBackend, cleanupResourceIds)) {
                processRunner.stop(processSession);
                throw new DevServerStartException(
                        DevServerStartException.Reason.CANCELLED,
                        "Dev Server durable ownership was lost during startup"
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
                        LogExceptionSanitizer.sanitize(exception)
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
            leaseCoordinator.requestStop(appId);
            portAllocator.release(appId);
            return;
        }
        stopSession(session, true);
    }

    /**
 * 判断运行中是否满足约束。
 *
 * @param appId 应用编号
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
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

    /**
 * 注册错误采集器。
 *
 * @param appId 应用编号
 * @param collector 采集器
 */
    public void registerErrorCollector(Long appId, DevServerErrorCollector collector) {
        outputHub.registerCollector(appId, collector);
        log.debug("已注册 Dev Server 错误收集器，appId={}", appId);
    }

    /**
 * 注销错误采集器。
 *
 * @param appId 应用编号
 * @param collector 采集器
 */
    public void unregisterErrorCollector(Long appId, DevServerErrorCollector collector) {
        outputHub.unregisterCollector(appId, collector);
        log.debug("已注销 Dev Server 错误收集器，appId={}", appId);
    }

    public List<String> getRecentOutputLines(Long appId, int limit) {
        return outputHub.recentLines(appId, limit);
    }

    /**
     * 记录一次预览访问，推迟该会话的空闲回收。
     *
     * <p>由预览路由收口调用：只有本节点持有的会话才可能被本节点回收，因此这里对未知
     * {@code appId} 静默返回，跨节点的活跃度由所有者节点自己记账。</p>
     *
     * @param appId 应用编号
     */
    public void touchSession(Long appId) {
        if (appId == null) {
            return;
        }
        ManagedDevServerSession session = sessions.get(appId);
        if (session != null) {
            session.touch(nanoTimeSource.getAsLong());
        }
    }

    /** 处理{@code maintain}会话{@code Leases}。 */
    @Scheduled(fixedDelayString = "${app.dev-server.runtime.heartbeat-interval:10s}")
    public void maintainSessionLeases() {
        if (shuttingDown.get()) {
            return;
        }
        for (ManagedDevServerSession session : new ArrayList<>(sessions.values())) {
            try {
                // 先判可回收再续租：顺序颠倒会让本轮就该回收的会话反被续上一个租期。
                if (reclaimIfUnusable(session)) {
                    continue;
                }
                DevServerSessionLeaseCoordinator.LeaseStatus status =
                        leaseCoordinator.renew(session.appId());
                if (status == DevServerSessionLeaseCoordinator.LeaseStatus.STOP_REQUESTED
                        || status == DevServerSessionLeaseCoordinator.LeaseStatus.LOST) {
                    log.warn("Stopping Dev Server after durable lease status {}, appId={}",
                            status, session.appId());
                    stopSession(session, false);
                }
            } catch (RuntimeException maintenanceFailure) {
                log.error("Dev Server lease maintenance failed, appId={}",
                        session.appId(), LogExceptionSanitizer.sanitize(maintenanceFailure));
            }
        }
    }

    /**
     * 回收已不再可用的运行中会话：长时间无人访问，或工作区目录已不在原处。
     *
     * <p>两种情形都必须主动收口。空闲会话若不回收，任何漏掉的停止（任务异常退出、用户直接
     * 关掉预览页）都会让 Vite 进程、端口和单用户会话配额一直占用到 JVM 退出。工作区目录被
     * 移走的情形更危险：发布会把执行工作区整体移动到版本目录，而 Linux 上 Vite 进程仍持有
     * 旧 inode，此时预览既不报错也不更新，用户看到的是一份无声的过期内容。</p>
     *
     * <p>仅处理已进入运行态的会话：启动中的会话由启动线程自行收口，停止中的无需重复处理。</p>
     *
     * @return 是否已回收该会话
     */
    private boolean reclaimIfUnusable(ManagedDevServerSession session) {
        if (!session.isRunning()) {
            return false;
        }
        String reclaimReason = unusableReason(session);
        if (reclaimReason == null) {
            return false;
        }
        log.info("回收不可用的 Dev Server 会话，appId={}, reason={}", session.appId(), reclaimReason);
        stopSession(session, false);
        return true;
    }

    /** 返回会话不可用的原因，可用时返回 {@code null}。 */
    private String unusableReason(ManagedDevServerSession session) {
        if (!Files.isDirectory(session.projectDirectory(), LinkOption.NOFOLLOW_LINKS)) {
            return "workspace_directory_missing";
        }
        Duration idleTimeout = properties.getIdleSessionTimeout();
        if (idleTimeout != null && session.isIdleSince(nanoTimeSource.getAsLong(), idleTimeout)) {
            return "idle_timeout";
        }
        return null;
    }

    /** 处理{@code destroy}。 */
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
                log.error("关闭应用 {} 的 Dev Server 失败", session.appId(), LogExceptionSanitizer.sanitize(exception));
                processRunner.terminateProjectProcesses(session.projectDirectory());
                cleanupSession(session, "服务关闭强制清理");
            }
        }
        sessions.clear();
        portAllocator.clear();
        outputHub.clear();
    }

    /** 查找匹配的{@code Reusable}会话。 */
    private ManagedDevServerSession findReusableSession(
            Long appId,
            Path requestedProjectDirectory,
            Map<String, String> requestedEnvironment
    ) {
        registryLock.lock();
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            ManagedDevServerSession existing = sessions.get(appId);
            if (existing == null) {
                return null;
            }
            if (existing.isRunning()) {
                if (!sameProjectDirectory(existing.projectDirectory(), requestedProjectDirectory)) {
                    throw new BusinessException(
                            ErrorCode.OPERATION_ERROR,
                            "A Dev Server for a different workspace is already running"
                    );
                }
                if (!existing.environmentOverrides().equals(requestedEnvironment)) {
                    throw new BusinessException(
                            ErrorCode.OPERATION_ERROR,
                            "已有 Dev Server 使用了不同的运行环境"
                    );
                }
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

    /** 注册{@code Starting}会话。 */
    private SessionRegistration registerStartingSession(
            Long appId,
            Long userId,
            Path projectDirectory,
            Integer preferredPort,
            Map<String, String> environmentOverrides
    ) {
        registryLock.lock();
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            if (shuttingDown.get()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "服务正在关闭，不能启动 Dev Server");
            }
            ManagedDevServerSession existing = sessions.get(appId);
            if (existing != null) {
                if (existing.isRunning()) {
                    if (!sameProjectDirectory(existing.projectDirectory(), projectDirectory)) {
                        throw new BusinessException(
                                ErrorCode.OPERATION_ERROR,
                                "A Dev Server for a different workspace is already running"
                        );
                    }
                    if (!existing.environmentOverrides().equals(environmentOverrides)) {
                        throw new BusinessException(
                                ErrorCode.OPERATION_ERROR,
                                "已有 Dev Server 使用了不同的运行环境"
                        );
                    }
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
            boolean durableClaimAcquired = false;
            ManagedDevServerSession session = null;
            try {
                DevServerSessionClaimResult claimResult = leaseCoordinator.claimStarting(
                        appId, userId, projectDirectory, port);
                if (claimResult == DevServerSessionClaimResult.USER_QUOTA_EXCEEDED) {
                    throw new BusinessException(
                            ErrorCode.OPERATION_ERROR,
                            "The durable Dev Server user quota has been reached"
                    );
                }
                if (claimResult != DevServerSessionClaimResult.ACQUIRED) {
                    throw new BusinessException(
                            ErrorCode.OPERATION_ERROR,
                            "A Dev Server session for this application is active on another node"
                    );
                }
                durableClaimAcquired = true;
                session = new ManagedDevServerSession(
                        appId,
                        userId,
                        projectDirectory,
                        port,
                        environmentOverrides,
                        nanoTimeSource.getAsLong()
                );
                sessions.put(appId, session);
                outputHub.prepare(appId);
                return new SessionRegistration(session, true);
            } catch (RuntimeException registrationFailure) {
                if (session != null) {
                    sessions.remove(appId, session);
                    session.markStopped();
                }
                portAllocator.release(appId);
                if (durableClaimAcquired) {
                    try {
                        leaseCoordinator.release(appId, "local_registration_failed");
                    } catch (RuntimeException releaseFailure) {
                        registrationFailure.addSuppressed(releaseFailure);
                    }
                }
                throw registrationFailure;
            }
        } finally {
            registryLock.unlock();
        }
    }

    private boolean sameProjectDirectory(Path left, Path right) {
        return left != null && right != null
                && left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
    }

    /** 确保{@code Dependencies}{@code Installed}已达到可用状态。 */
    private void ensureDependenciesInstalled(ManagedDevServerSession session,
                                             DevServerStartOptions startOptions) {
        DependencyInstallResult result = startOptions == null
                ? projectDependencyInstaller.ensureInstalled(session.projectDirectory())
                : projectDependencyInstaller.ensureInstalled(
                        session.projectDirectory(),
                        startOptions.taskId()
                );
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

    /** 启动进程。 */
    private DevServerProcessSession startProcess(
            ManagedDevServerSession session,
            Long appId,
            DevServerStartOptions startOptions
    ) {
        BooleanSupplier cancellationRequested = () -> session.isStopRequested()
                || startOptions != null && startOptions.isCancellationRequested();
        if (startOptions == null) {
            return processRunner.start(
                    session.projectDirectory(),
                    session.port(),
                    appId,
                    outputHub.sink(appId),
                    cancellationRequested
            );
        }
        if (!startOptions.environmentOverrides().isEmpty()) {
            return processRunner.start(
                    session.projectDirectory(),
                    session.port(),
                    appId,
                    outputHub.sink(appId),
                    startOptions.startupTimeout(),
                    cancellationRequested,
                    startOptions.environmentOverrides()
            );
        }
        return processRunner.start(
                session.projectDirectory(),
                session.port(),
                appId,
                outputHub.sink(appId),
                startOptions.startupTimeout(),
                cancellationRequested
        );
    }

    private void throwIfExternallyCancelled(DevServerStartOptions startOptions) {
        if (isExternallyCancelled(startOptions)) {
            throw mapStartFailure(new DevServerStartException(
                    DevServerStartException.Reason.CANCELLED,
                    "Dev Server startup was cancelled"
            ));
        }
    }

    private boolean isExternallyCancelled(DevServerStartOptions startOptions) {
        return startOptions != null && startOptions.isCancellationRequested();
    }

    /** 停止会话。 */
    private void stopSession(ManagedDevServerSession session, boolean failOnTimeout) {
        session.requestStop();
        RuntimeException cleanupFailure = null;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            leaseCoordinator.markStopping(session.appId());
        } catch (RuntimeException exception) {
            cleanupFailure = exception;
            log.warn("Failed to persist Dev Server stopping state, appId={}",
                    session.appId(), LogExceptionSanitizer.sanitize(exception));
        }
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            projectDependencyInstaller.cancel(session.projectDirectory());
        } catch (RuntimeException exception) {
            cleanupFailure = exception;
            log.warn("取消应用 {} 的依赖安装失败", session.appId(), LogExceptionSanitizer.sanitize(exception));
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

    /** 清理会话及其关联资源。 */
    private void cleanupSession(ManagedDevServerSession session, String reason) {
        registryLock.lock();
        try {
            if (sessions.remove(session.appId(), session)) {
                portAllocator.release(session.appId());
                try {
                    leaseCoordinator.release(session.appId(), reason);
                } catch (RuntimeException durableCleanupFailure) {
                    log.error("Failed to release durable Dev Server ownership, appId={}",
                            session.appId(), LogExceptionSanitizer.sanitize(durableCleanupFailure));
                }
                log.debug("已清理 Dev Server 会话，appId={}, reason={}", session.appId(), reason);
            }
            session.markStopped();
        } finally {
            registryLock.unlock();
        }
    }

    /** 将输入映射为开始失败。 */
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

    /** 校验{@code ate}开始请求是否有效。 */
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

    /** 返回安全{@code Exit}代码。 */
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
        private final Map<String, String> environmentOverrides;
        private final CompletableFuture<Void> startupCompletion = new CompletableFuture<>();
        /** 最近一次被访问的时刻，用于空闲回收；写多读少且跨线程，用 volatile 足够。 */
        private volatile long lastAccessNanos;
        private SessionState state = SessionState.STARTING;
        private DevServerProcessSession processSession;

        private ManagedDevServerSession(
                Long appId,
                Long userId,
                Path projectDirectory,
                int port,
                Map<String, String> environmentOverrides,
                long createdAtNanos
        ) {
            this.appId = appId;
            this.userId = userId;
            this.projectDirectory = projectDirectory;
            this.port = port;
            this.environmentOverrides = environmentOverrides == null
                    ? Map.of()
                    : Map.copyOf(environmentOverrides);
            // 以创建时刻作为初值：会话在首次被访问前也应享有完整的空闲宽限期。
            this.lastAccessNanos = createdAtNanos;
        }

        private void touch(long nowNanos) {
            lastAccessNanos = nowNanos;
        }

        /** 判断自 {@code lastAccessNanos} 起的静默时长是否已超过空闲上限。 */
        private boolean isIdleSince(long nowNanos, Duration idleTimeout) {
            return nowNanos - lastAccessNanos > idleTimeout.toNanos();
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

        private Map<String, String> environmentOverrides() {
            return environmentOverrides;
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

        /** 等待{@code Startup}完成。 */
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
