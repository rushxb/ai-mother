package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.config.production.ProfileDefaultsEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 校验评测 Worker 角色在 Profile yaml 删除后仍然隔离线上流量。
 *
 * <p>取值现由 {@link ProfileDefaultsEnvironmentPostProcessor} 以代码常量提供。</p>
 */
class GenerationBenchmarkWorkerProfileTest {

    @Test
    void profileMustDisableOnlineRolesAndEnableCompleteGraders() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod", "benchmark-worker");

        new ProfileDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertEquals("none", environment.getProperty("spring.main.web-application-type"));
        assertEquals("false", environment.getProperty("app.background-jobs.enabled"));
        assertEquals("local", environment.getProperty("app.generation-task-queue.transport"));
        assertEquals("local", environment.getProperty("app.generation-event-stream.transport"));
        assertEquals("false", environment.getProperty("app.template-pre-warm.enabled"));
        assertEquals("true", environment.getProperty("app.generation-benchmark.worker.enabled"));
        assertEquals("true",
                environment.getProperty("app.generation-benchmark.browser-grading.enabled"));
        assertEquals("true",
                environment.getProperty("app.generation-benchmark.backend-grading.enabled"));
    }
}
