package com.rush.rushaicodemother.config.production;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 以代码常量提供 {@code prod} 与 {@code benchmark-worker} Profile 的固定配置。
 *
 * <p>这些取值原先声明在 {@code application-prod.yml} 和 {@code application-benchmark-worker.yml} 中。
 * 为了让部署方只维护 {@code application.yml} 与 {@code application-dev.yml}，安全硬化项和进程角色开关
 * 统一下沉为常量：它们属于发布契约，不应由运维逐项调整。</p>
 *
 * <p>属性源以 {@code addLast} 注册，优先级低于环境变量、命令行参数和 {@code application.yml}
 * 中的显式配置，因此密钥、主机名等仍然只能由外部注入；本类不提供任何凭据默认值。
 * 缺失的凭据由 {@link ProductionConfigurationEnvironmentPostProcessor} 在启动时拒绝。</p>
 */
public class ProfileDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 生产 Profile 名称。 */
    private static final String PRODUCTION_PROFILE = "prod";

    /** 评测 Worker Profile 名称。 */
    private static final String BENCHMARK_WORKER_PROFILE = "benchmark-worker";

    /** 生产固定配置的属性源名称。 */
    static final String PRODUCTION_PROPERTY_SOURCE = "productionProfileDefaults";

    /** 评测 Worker 固定配置的属性源名称。 */
    static final String BENCHMARK_WORKER_PROPERTY_SOURCE = "benchmarkWorkerProfileDefaults";

    /**
     * 生产环境的安全硬化与在线角色配置。
     *
     * <p>仅包含与环境无关的固定项；数据库、Redis、Milvus、密钥和 Origin 等一律留给外部注入。</p>
     */
    private static final Map<String, Object> PRODUCTION_DEFAULTS = productionDefaults();

    /**
     * 评测 Worker 的进程角色配置。
     *
     * <p>关闭 Web 端点与后台调度，并把任务队列、事件流切换为本地实现，避免 Worker 消费线上流量。</p>
     */
    private static final Map<String, Object> BENCHMARK_WORKER_DEFAULTS = benchmarkWorkerDefaults();

    /**
     * 按激活的 Profile 注入固定配置。
     *
     * @param environment 当前应用环境
     * @param application 当前应用
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 先注册在线角色，再注册 Worker 角色，使 Worker 取值覆盖在线取值，
        // 与原先 `prod,benchmark-worker` 的 Profile 顺序语义保持一致。
        if (environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE))) {
            register(environment, PRODUCTION_PROPERTY_SOURCE, PRODUCTION_DEFAULTS);
        }
        if (environment.acceptsProfiles(Profiles.of(BENCHMARK_WORKER_PROFILE))) {
            register(environment, BENCHMARK_WORKER_PROPERTY_SOURCE, BENCHMARK_WORKER_DEFAULTS);
        }
    }

    /**
     * 把固定配置插入到系统环境变量之后。
     *
     * <p>这样其优先级低于命令行参数、系统属性和 OS 环境变量，但高于 {@code application.yml}，
     * 与原先 Profile 专属 yaml 的层级完全一致：既能覆盖基础默认值，又不会盖掉部署方的显式注入。</p>
     *
     * <p>取值中保留 {@code ${ENV:default}} 占位符的项还有第二层可覆盖性：占位符针对整个环境解析，
     * 因此即使属性源顺序发生变化，显式提供的环境变量依然生效。</p>
     */
    private void register(ConfigurableEnvironment environment, String name, Map<String, Object> values) {
        MutablePropertySources propertySources = environment.getPropertySources();
        MapPropertySource source = new MapPropertySource(name, values);
        if (propertySources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            propertySources.addAfter(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
            return;
        }
        // 缺少系统环境属性源的场景（例如精简测试环境）退化为最高优先级，
        // 以保证固定配置仍然覆盖 application.yml 的基础默认值。
        propertySources.addFirst(source);
    }

    /**
     * 返回执行顺序。
     *
     * <p>必须早于 {@link ProductionConfigurationEnvironmentPostProcessor} 的
     * {@link Ordered#LOWEST_PRECEDENCE}，否则生产校验会读不到这里注入的取值。</p>
     *
     * @return 执行顺序
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    /** 返回生产 Profile 的固定配置。 */
    private static Map<String, Object> productionDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();

        // 数据库迁移：生产默认执行 Flyway，并允许对存量库设定基线。
        defaults.put("spring.flyway.enabled", "${FLYWAY_ENABLED:true}");
        defaults.put("spring.flyway.baseline-on-migrate", "${FLYWAY_BASELINE_ON_MIGRATE:false}");
        defaults.put("spring.flyway.baseline-version", "${FLYWAY_BASELINE_VERSION:20260716.5}");
        defaults.put("spring.flyway.baseline-description", "production-schema-before-flyway");

        // 数据源与 Redis 连接：保留既有部署变量名，取值仍必须由外部注入。
        // 这里只做变量名到 Spring 属性的映射，不提供任何兜底默认值；
        // 未注入时占位符解析为空，由生产校验器在创建 Bean 前拒绝启动。
        defaults.put("spring.datasource.url", "${MYSQL_URL:}");
        defaults.put("spring.datasource.username", "${MYSQL_USERNAME:}");
        defaults.put("spring.datasource.password", "${MYSQL_PASSWORD:}");
        defaults.put("spring.data.redis.host", "${REDIS_HOST:}");
        defaults.put("spring.data.redis.port", "${REDIS_PORT:6379}");
        defaults.put("spring.data.redis.database", "${REDIS_DATABASE:0}");
        defaults.put("spring.data.redis.username", "${REDIS_USERNAME:}");
        defaults.put("spring.data.redis.password", "${REDIS_PASSWORD:}");
        // CORS 与部署地址没有基础默认值，这里补齐变量映射；密钥类已在 application.yml 中声明。
        defaults.put("app.cors.allowed-origins", "${CORS_ALLOWED_ORIGINS:}");
        defaults.put("app.cors.allow-credentials", "true");
        defaults.put("code.deploy-host", "${CODE_DEPLOY_HOST:}");

        // 会话与 Cookie：生产强制 HTTPS Cookie，并缩短会话有效期。
        defaults.put("spring.session.timeout", "${SESSION_TIMEOUT:8h}");
        defaults.put("server.servlet.session.cookie.secure", "true");
        defaults.put("server.servlet.session.cookie.max-age", "${SESSION_COOKIE_MAX_AGE:8h}");
        defaults.put("server.forward-headers-strategy", "${SERVER_FORWARD_HEADERS_STRATEGY:none}");

        // 关闭在线 API 文档端点，避免暴露内部契约。
        defaults.put("springdoc.api-docs.enabled", "false");
        defaults.put("springdoc.swagger-ui.enabled", "false");
        defaults.put("knife4j.enable", "false");

        // 屏蔽会打印模型凭据的 Mapper 日志。
        defaults.put("logging.level.com.rush.rushaicodemother.mapper.AiModelMapper", "OFF");

        // 健康检查与链路追踪：只暴露探针结论，链路数据必须外发。
        defaults.put("management.endpoint.health.show-details", "never");
        defaults.put("management.tracing.enabled", "${OTEL_TRACING_ENABLED:true}");
        defaults.put("management.otlp.tracing.export.enabled", "${OTEL_TRACING_EXPORT_ENABLED:true}");

        // 在线节点角色：任务与事件必须走 Redis，后台调度保持开启。
        defaults.put("app.background-jobs.enabled", "true");
        defaults.put("app.generation-task-queue.transport", "${GENERATION_TASK_QUEUE_TRANSPORT:redis}");
        defaults.put("app.generation-event-stream.transport", "${GENERATION_EVENT_STREAM_TRANSPORT:redis}");
        defaults.put("app.template-pre-warm.enabled", "${TEMPLATE_PRE_WARM_ENABLED:true}");

        // 生成沙箱：生产只允许容器后端，并启用只读根、启动校验与依赖缓存。
        defaults.put("app.generated-code-sandbox.mode", "${GENERATED_CODE_SANDBOX_MODE:container}");
        defaults.put("app.generated-code-sandbox.container.read-only-root", "true");
        defaults.put("app.generated-code-sandbox.container.verify-on-startup", "true");
        defaults.put("app.generated-code-sandbox.container.dependency-cache-enabled",
                "${GENERATED_CODE_SANDBOX_DEPENDENCY_CACHE_ENABLED:true}");

        // 模型容量治理：生产必须限流，且不允许失败放行。
        defaults.put("app.ai-model-capacity.enabled", "${AI_MODEL_CAPACITY_ENABLED:true}");
        defaults.put("app.ai-model-capacity.fail-open", "${AI_MODEL_CAPACITY_FAIL_OPEN:false}");

        // Prompt 目录：生产必须启用运行时发布并要求首次加载成功。
        defaults.put("app.ai-prompt-catalog.enabled", "true");
        defaults.put("app.ai-prompt-catalog.runtime-releases.enabled",
                "${AI_PROMPT_RUNTIME_RELEASES_ENABLED:true}");
        defaults.put("app.ai-prompt-catalog.runtime-releases.initial-load-required",
                "${AI_PROMPT_RUNTIME_RELEASES_INITIAL_LOAD_REQUIRED:true}");

        // 语义记忆：生产必须启用长期记忆，并强制认证与 TLS。
        defaults.put("app.memory.long-term.enabled", "${MILVUS_MEMORY_ENABLED:true}");
        defaults.put("app.memory.long-term.authentication-required",
                "${MILVUS_AUTHENTICATION_REQUIRED:true}");
        defaults.put("app.memory.long-term.tls-required", "${MILVUS_TLS_REQUIRED:true}");
        defaults.put("app.memory.long-term.verify-on-startup", "${MILVUS_VERIFY_ON_STARTUP:true}");
        defaults.put("app.memory.outbox.enabled", "${GENERATION_MEMORY_OUTBOX_ENABLED:true}");

        return Map.copyOf(defaults);
    }

    /** 返回评测 Worker Profile 的固定配置。 */
    private static Map<String, Object> benchmarkWorkerDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();

        // Worker 不对外提供 HTTP 服务。
        defaults.put("spring.main.web-application-type", "none");

        // 隔离线上流量：不消费任务队列、不写在线事件流、不跑后台调度。
        defaults.put("app.background-jobs.enabled", "false");
        defaults.put("app.generation-task-queue.transport", "local");
        defaults.put("app.generation-event-stream.transport", "local");
        defaults.put("app.template-pre-warm.enabled", "false");

        // 评测角色：开启 Worker 与两类评分器。
        defaults.put("app.generation-benchmark.worker.enabled", "true");
        defaults.put("app.generation-benchmark.browser-grading.enabled",
                "${GENERATION_BENCHMARK_BROWSER_GRADING_ENABLED:true}");
        defaults.put("app.generation-benchmark.backend-grading.enabled",
                "${GENERATION_BENCHMARK_BACKEND_GRADING_ENABLED:true}");

        return Map.copyOf(defaults);
    }
}
