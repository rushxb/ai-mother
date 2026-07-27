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

    /** Dev Server 从创建进程到确认就绪的最大时长。 */
    private Duration startupTimeout = Duration.ofSeconds(30);

    /** 启动就绪检查间隔。 */
    private Duration readinessPollInterval = Duration.ofMillis(200);

    /** 启动就绪后，用于收集首次编译延迟错误的验证窗口。 */
    private Duration validationErrorCollectionWindow = Duration.ofSeconds(5);

    /** 检测到阻断级错误后，继续收集同批诊断的短暂收口窗口。 */
    private Duration validationCriticalErrorDrainWindow = Duration.ofMillis(300);

    /** 验证期间取消和截止日期检查的轮询间隔。 */
    private Duration validationPollInterval = Duration.ofMillis(100);

    /** 进程结束后等待输出消费线程收口的时长。 */
    private Duration outputDrainTimeout = Duration.ofSeconds(2);

    /** 停止启动中会话时，等待启动线程完成补偿清理的最大时长。 */
    private Duration stopTimeout = Duration.ofSeconds(10);

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
    private int maxServersPerUser = 3;

    /** 单行进程输出允许保留的最大字符数。 */
    @Min(256)
    @Max(100_000)
    private int maxOutputLineLength = 2000;

    /** 单应用在内存中保留的最近输出行数。 */
    @Min(1)
    @Max(10_000)
    private int maxRecentOutputLines = 200;

    /** 稳定的部署节点身份。生产环境必须明确配置它。 */
    private String nodeId;

    /** 持久开发服务器会话的所有权租赁。 */
    private Duration leaseDuration = Duration.ofSeconds(30);

    /** 拥有进程更新其会话租约所使用的时间间隔。 */
    private Duration heartbeatInterval = Duration.ofSeconds(10);

    /** 用于扫描过期开发服务器会话的时间间隔。 */
    private Duration recoveryScanInterval = Duration.ofSeconds(15);

    /** 一次扫描中恢复的最大过期会话数。 */
    @Min(1)
    @Max(500)
    private int recoveryBatchSize = 50;

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
                recoveryScanInterval
        );
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
