package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.stream.Stream;

/**
 * Dev Server 进程启动、停止、端口和输出资源限制。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.dev-server.runtime")
public class DevServerRuntimeProperties {

    private static final Duration MAX_RUNTIME_DURATION = Duration.ofHours(1);
    private static final String SAFE_NODE_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";

    public static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration READINESS_POLL_INTERVAL = Duration.ofMillis(200);
    public static final Duration VALIDATION_ERROR_COLLECTION_WINDOW = Duration.ofSeconds(5);
    public static final Duration VALIDATION_CRITICAL_ERROR_DRAIN_WINDOW = Duration.ofMillis(300);
    public static final Duration VALIDATION_POLL_INTERVAL = Duration.ofMillis(100);
    public static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);
    public static final int MAX_SERVERS_PER_USER = 3;
    public static final int MAX_OUTPUT_LINE_LENGTH = 2000;
    public static final int MAX_RECENT_OUTPUT_LINES = 200;
    public static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    public static final Duration RECOVERY_SCAN_INTERVAL = Duration.ofSeconds(15);
    public static final int RECOVERY_BATCH_SIZE = 50;
    public static final Duration IDLE_SESSION_TIMEOUT = Duration.ofMinutes(20);

    /** Dev Server 从创建进程到确认就绪的最大时长。 */
    private Duration startupTimeout = STARTUP_TIMEOUT;

    /** 启动就绪检查间隔。 */
    private Duration readinessPollInterval = READINESS_POLL_INTERVAL;

    /** 启动就绪后，用于收集首次编译延迟错误的验证窗口。 */
    private Duration validationErrorCollectionWindow = VALIDATION_ERROR_COLLECTION_WINDOW;

    /** 检测到阻断级错误后，继续收集同批诊断的短暂收口窗口。 */
    private Duration validationCriticalErrorDrainWindow = VALIDATION_CRITICAL_ERROR_DRAIN_WINDOW;

    /** 验证期间取消和截止日期检查的轮询间隔。 */
    private Duration validationPollInterval = VALIDATION_POLL_INTERVAL;

    /** 进程结束后等待输出消费线程收口的时长。 */
    private Duration outputDrainTimeout = OUTPUT_DRAIN_TIMEOUT;

    /** 停止启动中会话时，等待启动线程完成补偿清理的最大时长。 */
    private Duration stopTimeout = STOP_TIMEOUT;

    /** 自动分配端口范围起点。 */
    @Min(1024)
    @Max(65535)
    private int portRangeStart = 10000;

    /** 自动分配端口范围终点。 */
    @Min(1024)
    @Max(65535)
    private int portRangeEnd = 60000;

    /** 单用户允许同时占用的 Dev Server 会话数。 */
    @Min(1)
    @Max(100)
    private int maxServersPerUser = MAX_SERVERS_PER_USER;

    /** 单行进程输出允许保留的最大字符数。 */
    @Min(256)
    @Max(100_000)
    private int maxOutputLineLength = MAX_OUTPUT_LINE_LENGTH;

    /** 单应用在内存中保留的最近输出行数。 */
    @Min(1)
    @Max(10_000)
    private int maxRecentOutputLines = MAX_RECENT_OUTPUT_LINES;

    /** 稳定的部署节点身份。生产环境必须明确配置它。 */
    private String nodeId;

    /** 持久开发服务器会话的所有权租赁。 */
    private Duration leaseDuration = LEASE_DURATION;

    /** 拥有进程更新其会话租约所使用的时间间隔。 */
    private Duration heartbeatInterval = HEARTBEAT_INTERVAL;

    /** 用于扫描过期开发服务器会话的时间间隔。 */
    private Duration recoveryScanInterval = RECOVERY_SCAN_INTERVAL;

    /** 一次扫描中恢复的最大过期会话数。 */
    @Min(1)
    @Max(500)
    private int recoveryBatchSize = RECOVERY_BATCH_SIZE;

    /**
     * 无人访问的 Dev Server 会话被回收前允许的最长空闲时长。
     *
     * <p>取值需要同时容纳两类正常间隙：用户读代码、切窗口造成的浏览器静默，以及生成链路
     * 两轮修复之间的空档。取值过小会打断正在使用的预览，过大则让废弃会话继续占用端口、
     * 进程和单用户会话配额。</p>
     */
    private Duration idleSessionTimeout = IDLE_SESSION_TIMEOUT;

    @AssertTrue(message = "Dev Server 运行时超时必须全部大于 0")
    public boolean isDurationConfigurationValid() {
        return configuredDurations()
                .allMatch(duration -> duration != null && !duration.isZero() && !duration.isNegative());
    }

    @AssertTrue(message = "Dev Server 运行时超时不能超过 1 小时")
    public boolean isDurationConfigurationBounded() {
        return configuredDurations()
                .allMatch(duration -> duration != null && duration.compareTo(MAX_RUNTIME_DURATION) <= 0);
    }

    /** 汇总参与约束校验的时长配置。 */
    private Stream<Duration> configuredDurations() {
        return Stream.of(
                startupTimeout,
                readinessPollInterval,
                validationErrorCollectionWindow,
                validationCriticalErrorDrainWindow,
                validationPollInterval,
                outputDrainTimeout,
                stopTimeout,
                leaseDuration,
                heartbeatInterval,
                recoveryScanInterval,
                idleSessionTimeout
        );
    }

    @AssertTrue(message = "Dev Server 空闲回收时长必须大于租约续期间隔")
    public boolean isIdleSessionTimeoutSafe() {
        // 空闲判定由心跳巡检推进，若小于心跳间隔，一次巡检就可能误杀刚建立的会话。
        return idleSessionTimeout != null
                && heartbeatInterval != null
                && idleSessionTimeout.compareTo(heartbeatInterval) > 0;
    }

    @AssertTrue(message = "Dev Server heartbeat interval must be shorter than its ownership lease")
    public boolean isLeaseConfigurationValid() {
        return leaseDuration != null
                && heartbeatInterval != null
                && heartbeatInterval.compareTo(leaseDuration) < 0;
    }

    @AssertTrue(message = "Dev Server 就绪检查间隔必须小于启动超时")
    public boolean isReadinessPollIntervalSafe() {
        return startupTimeout != null
                && readinessPollInterval != null
                && readinessPollInterval.compareTo(startupTimeout) < 0;
    }

    @AssertTrue(message = "Dev Server 阻断错误收口窗口不能超过完整错误收集窗口")
    public boolean isValidationCriticalErrorDrainWindowSafe() {
        return validationErrorCollectionWindow != null
                && validationCriticalErrorDrainWindow != null
                && validationCriticalErrorDrainWindow.compareTo(validationErrorCollectionWindow) <= 0;
    }

    @AssertTrue(message = "Dev Server 端口范围配置无效")
    public boolean isPortRangeValid() {
        return portRangeStart <= portRangeEnd;
    }

    @AssertTrue(message = "Dev Server node id must be safe for internal routing")
    public boolean isNodeIdValid() {
        return nodeId == null || nodeId.isBlank() || nodeId.trim().matches(SAFE_NODE_ID_PATTERN);
    }

}
