package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 记录项目构建去重、复用和并发等待的低基数运行指标。 */
@Component
public class ProjectBuildCoordinationMetricsCollector {

    private static final Set<String> PROJECT_TYPES = Set.of("vue", "go");
    private static final Set<String> EVENTS = Set.of(
            "execution_started",
            "execution_success",
            "execution_failure",
            "execution_error",
            "task_reused",
            "inflight_joined",
            "reuse_rejected"
    );
    private static final Set<String> WAIT_STATUSES = Set.of("success", "failure", "interrupted", "error");

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> eventCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> waitTimers = new ConcurrentHashMap<>();

    @Autowired
    public ProjectBuildCoordinationMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private ProjectBuildCoordinationMetricsCollector() {
        this.meterRegistry = null;
    }

    public static ProjectBuildCoordinationMetricsCollector noOp() {
        return new ProjectBuildCoordinationMetricsCollector();
    }

    /**
 * 记录事件相关指标或状态。
 *
 * @param projectType 项目类型
 * @param event 待处理的领域事件
 */
    public void recordEvent(String projectType, String event) {
        if (meterRegistry == null) {
            return;
        }
        String normalizedProjectType = projectType(projectType);
        String normalizedEvent = event(event);
        String key = normalizedProjectType + ':' + normalizedEvent;
        eventCounters.computeIfAbsent(key, unused -> Counter.builder(
                        "generation_project_build_coordination_total")
                .description("项目构建协调事件数量")
                .tag("project_type", normalizedProjectType)
                .tag("event", normalizedEvent)
                .register(meterRegistry)).increment();
    }

    /**
 * 记录{@code Join}{@code Wait}相关指标或状态。
 *
 * @param projectType 项目类型
 * @param status 目标状态
 * @param duration 目标时长
 */
    public void recordJoinWait(String projectType, String status, Duration duration) {
        if (meterRegistry == null) {
            return;
        }
        String normalizedProjectType = projectType(projectType);
        String normalizedStatus = waitStatus(status);
        String key = normalizedProjectType + ':' + normalizedStatus;
        waitTimers.computeIfAbsent(key, unused -> Timer.builder(
                        "generation_project_build_join_wait_duration_seconds")
                .description("等待同任务在途构建完成的时长")
                .tag("project_type", normalizedProjectType)
                .tag("status", normalizedStatus)
                .register(meterRegistry)).record(nonNegative(duration));
    }

    private String projectType(String value) {
        return value != null && PROJECT_TYPES.contains(value) ? value : "unknown";
    }

    private String event(String value) {
        return value != null && EVENTS.contains(value) ? value : "other";
    }

    private String waitStatus(String value) {
        return value != null && WAIT_STATUSES.contains(value) ? value : "error";
    }

    private Duration nonNegative(Duration duration) {
        return duration == null || duration.isNegative() ? Duration.ZERO : duration;
    }
}
