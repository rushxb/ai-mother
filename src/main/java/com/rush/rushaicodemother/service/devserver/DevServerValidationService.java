package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.DevServerManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Dev Server 运行时验证服务
 * <p>
 * 在代码生成完成后，启动 dev server 并监控其错误输出，
 * 检测运行时错误（缺失依赖、导出不存在、组件解析失败等）。
 * 验证完成后立即停止 dev server，释放资源。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevServerValidationService {

    private final DevServerManager devServerManager;

    /**
     * dev server 启动超时（毫秒）
     */
    private static final long STARTUP_TIMEOUT_MS = 20_000;

    /**
     * 首次编译完成后的错误收集窗口（毫秒）
     * Vite 首次编译后可能会有延迟的错误输出，等待一段时间收集完整
     */
    private static final long ERROR_COLLECTION_WINDOW_MS = 5_000;

    /**
     * 启动轮询间隔（毫秒）
     */
    private static final long POLL_INTERVAL_MS = 500;

    /**
     * 执行 Dev Server 运行时验证
     *
     * @param taskId      任务 ID
     * @param appId       应用 ID
     * @param userId      用户 ID
     * @param projectPath 项目路径
     * @return 验证结果
     */
    public DevServerValidationResult validate(String taskId, Long appId, Long userId, String projectPath) {
        long startTime = System.currentTimeMillis();

        log.info("开始 Dev Server 运行时验证，taskId: {}, appId: {}, projectPath: {}", taskId, appId, projectPath);

        // 1. 创建错误收集器并注册到 DevServerManager
        DevServerErrorCollector collector = new DevServerErrorCollector();
        devServerManager.registerErrorCollector(appId, collector);

        boolean devServerStarted = false;
        try {
            // 2. 启动 dev server
            App app = buildAppForValidation(appId, projectPath);
            int port;
            try {
                port = devServerManager.startDevServer(app, userId);
                devServerStarted = true;
            } catch (BusinessException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                if (e.getCode() == ErrorCode.NOT_FOUND_ERROR.getCode()) {
                    return DevServerValidationResult.skipped(taskId, appId, e.getMessage());
                }
                return DevServerValidationResult.timeout(taskId, appId, elapsed);
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.warn("Dev Server 启动异常，taskId: {}, error: {}", taskId, e.getMessage());
                return DevServerValidationResult.timeout(taskId, appId, elapsed);
            }

            log.info("Dev Server 已启动，taskId: {}, port: {}，等待首次编译完成...", taskId, port);

            // 3. 等待首次编译完成（端口被占用说明服务已启动）
            boolean startupOk = waitForStartup(port, STARTUP_TIMEOUT_MS);
            if (!startupOk) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.warn("Dev Server 启动超时，taskId: {}", taskId);
                return DevServerValidationResult.timeout(taskId, appId, elapsed);
            }

            // 4. 错误收集窗口：继续等待一段时间收集延迟的错误
            log.info("Dev Server 首次编译完成，taskId: {}，进入错误收集窗口（{}ms）...", taskId, ERROR_COLLECTION_WINDOW_MS);
            try {
                Thread.sleep(ERROR_COLLECTION_WINDOW_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            // 5. 分析错误
            List<DevServerError> errors = collector.getErrors();
            long elapsed = System.currentTimeMillis() - startTime;

            log.info("Dev Server 验证完成，taskId: {}, criticalErrors: {}, warnings: {}, duration: {}ms",
                    taskId, collector.getCriticalErrorCount(), collector.getWarningCount(), elapsed);

            if (collector.hasCriticalError()) {
                return DevServerValidationResult.failed(taskId, appId, errors, elapsed);
            }
            if (collector.hasWarning()) {
                return DevServerValidationResult.warning(taskId, appId, errors, elapsed);
            }
            return DevServerValidationResult.passed(taskId, appId, elapsed);

        } finally {
            // 6. 停止 dev server（验证完毕，释放资源）
            if (devServerStarted) {
                try {
                    devServerManager.stopDevServer(appId, userId);
                    log.info("验证用 Dev Server 已停止，appId: {}", appId);
                } catch (Exception e) {
                    log.warn("停止验证用 Dev Server 失败，appId: {}, error: {}", appId, e.getMessage());
                }
            }
            // 7. 注销错误收集器
            devServerManager.unregisterErrorCollector(appId);
        }
    }

    /**
     * 等待 dev server 启动（通过检查端口是否被占用）
     */
    private boolean waitForStartup(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isPortAvailable(port)) {
                // 端口被占用 = dev server 已启动
                return true;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 检查端口是否可用（未被占用）
     */
    private boolean isPortAvailable(int port) {
        try (java.net.ServerSocket serverSocket = new java.net.ServerSocket(port)) {
            serverSocket.setReuseAddress(true);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * 构建验证用的 App 对象
     */
    private App buildAppForValidation(Long appId, String projectPath) {
        App app = new App();
        app.setId(appId);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        return app;
    }
}
