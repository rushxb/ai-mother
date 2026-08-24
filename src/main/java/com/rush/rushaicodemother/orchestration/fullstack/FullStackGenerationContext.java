package com.rush.rushaicodemother.orchestration.fullstack;

import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 全栈工程生成期间共享的端口、路径与前后端连接契约。
 *
 * <p>该对象同时拥有持久化制品的序列化和恢复校验规则，避免恢复检查点后由各个
 * Agent 自行读取 Map、补默认值，进而把其他应用或损坏的端口配置送入代码生成。</p>
 */
public record FullStackGenerationContext(
        Long appId,
        String workspaceRoot,
        String frontendPath,
        String backendPath,
        int frontendPort,
        int backendPort,
        String frontendBaseUrl,
        String backendBaseUrl,
        String apiPrefix,
        String frontendApiEnvName,
        String frontendApiEnvValue,
        String backendServerAddr,
        String containerizationStatus
) {

    public static final String KEY = "full_stack_context";

    private static final String ROLE = "Template";
    private static final String TITLE = "全栈上下文";
    private static final String API_PREFIX = "/api";
    private static final String FRONTEND_API_ENV_NAME = "VITE_API_BASE_URL";
    private static final String CONTAINERIZATION_STATUS = "reserved";

    public FullStackGenerationContext {
        if (appId == null || appId <= 0) {
            throw invalidField("appId", "应用标识必须为正整数");
        }
        workspaceRoot = requireText(workspaceRoot, "workspaceRoot");
        frontendPath = requireText(frontendPath, "frontendPath");
        backendPath = requireText(backendPath, "backendPath");
        requirePort(frontendPort, "frontendPort");
        requirePort(backendPort, "backendPort");
        if (frontendPort == backendPort) {
            throw invalidField("backendPort", "前后端端口不能相同");
        }
        frontendBaseUrl = requireExactText(
                frontendBaseUrl,
                "http://127.0.0.1:" + frontendPort,
                "frontendBaseUrl"
        );
        backendBaseUrl = requireExactText(
                backendBaseUrl,
                "http://127.0.0.1:" + backendPort,
                "backendBaseUrl"
        );
        apiPrefix = requireExactText(apiPrefix, API_PREFIX, "apiPrefix");
        frontendApiEnvName = requireExactText(
                frontendApiEnvName,
                FRONTEND_API_ENV_NAME,
                "frontendApiEnvName"
        );
        frontendApiEnvValue = requireExactText(
                frontendApiEnvValue,
                backendBaseUrl + apiPrefix,
                "frontendApiEnvValue"
        );
        backendServerAddr = requireExactText(
                backendServerAddr,
                ":" + backendPort,
                "backendServerAddr"
        );
        containerizationStatus = requireExactText(
                containerizationStatus,
                CONTAINERIZATION_STATUS,
                "containerizationStatus"
        );
    }

    /**
 * 创建全栈生成上下文。
 *
 * @param frontendPort {@code frontendPort} 对应的调用参数
 * @param backendPort 后端端口
 * @param workspace 工作区
 * @return 全栈生成上下文
 */
    public static FullStackGenerationContext create(int frontendPort,
                                                    int backendPort,
                                                    GenerationWorkspace workspace) {
        String workspaceRoot = portablePath(workspace.canonicalRootPath());
        String frontendPath = portablePath(workspace.frontendRootPath());
        String backendPath = portablePath(workspace.backendRootPath());
        String backendBaseUrl = "http://127.0.0.1:" + backendPort;
        String apiPrefix = API_PREFIX;
        return new FullStackGenerationContext(
                workspace.appId(),
                workspaceRoot,
                frontendPath,
                backendPath,
                frontendPort,
                backendPort,
                "http://127.0.0.1:" + frontendPort,
                backendBaseUrl,
                apiPrefix,
                FRONTEND_API_ENV_NAME,
                backendBaseUrl + apiPrefix,
                ":" + backendPort,
                CONTAINERIZATION_STATUS
        );
    }

    /** 从模板执行结果恢复强类型上下文，并校验它属于当前应用。 */
    public static FullStackGenerationContext fromPayload(
            Map<String, Object> payload,
            Long expectedAppId
    ) {
        if (payload == null) {
            throw invalidField("payload", "不能为空");
        }
        FullStackGenerationContext context = new FullStackGenerationContext(
                requireLong(payload.get("appId"), "appId"),
                requireTextValue(payload.get("workspaceRoot"), "workspaceRoot"),
                requireTextValue(payload.get("frontendPath"), "frontendPath"),
                requireTextValue(payload.get("backendPath"), "backendPath"),
                requireInteger(payload.get("frontendPort"), "frontendPort"),
                requireInteger(payload.get("backendPort"), "backendPort"),
                requireTextValue(payload.get("frontendBaseUrl"), "frontendBaseUrl"),
                requireTextValue(payload.get("backendBaseUrl"), "backendBaseUrl"),
                requireTextValue(payload.get("apiPrefix"), "apiPrefix"),
                requireTextValue(payload.get("frontendApiEnvName"), "frontendApiEnvName"),
                requireTextValue(payload.get("frontendApiEnvValue"), "frontendApiEnvValue"),
                requireTextValue(payload.get("backendServerAddr"), "backendServerAddr"),
                requireTextValue(payload.get("containerizationStatus"), "containerizationStatus")
        );
        if (!Objects.equals(context.appId(), expectedAppId)) {
            throw invalidField(
                    "appId",
                    "应用标识不匹配，期望 " + expectedAppId + "，实际 " + context.appId()
            );
        }
        return context;
    }

    /** 从持久化制品恢复强类型上下文，并拒绝类型或应用身份不一致的检查点。 */
    public static FullStackGenerationContext fromArtifact(
            GenerationArtifact artifact,
            Long expectedAppId
    ) {
        if (artifact == null) {
            throw invalidField("artifact", "不能为空");
        }
        if (!KEY.equals(artifact.key())) {
            throw invalidField("key", "制品类型不匹配: " + artifact.key());
        }
        return fromPayload(artifact.payload(), expectedAppId);
    }

    private static String portablePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    /**
 * 将当前对象转换为载荷。
 *
 * @return 载荷集合
 */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appId", appId);
        payload.put("workspaceRoot", workspaceRoot);
        payload.put("frontendPath", frontendPath);
        payload.put("backendPath", backendPath);
        payload.put("frontendPort", frontendPort);
        payload.put("backendPort", backendPort);
        payload.put("frontendBaseUrl", frontendBaseUrl);
        payload.put("backendBaseUrl", backendBaseUrl);
        payload.put("apiPrefix", apiPrefix);
        payload.put("frontendApiEnvName", frontendApiEnvName);
        payload.put("frontendApiEnvValue", frontendApiEnvValue);
        payload.put("backendServerAddr", backendServerAddr);
        payload.put("containerizationStatus", containerizationStatus);
        return payload;
    }

    /** 转换为供 DAG 检查点持久化的规范制品。 */
    public GenerationArtifact toArtifact() {
        return GenerationArtifact.of(KEY, ROLE, TITLE, toPayload());
    }

    private static Long requireLong(Object value, String field) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw invalidField(field, "必须为整数");
    }

    private static int requireInteger(Object value, String field) {
        long parsed = requireLong(value, field);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw invalidField(field, "超出整数范围");
        }
        return (int) parsed;
    }

    private static String requireTextValue(Object value, String field) {
        if (!(value instanceof String text)) {
            throw invalidField(field, "必须为字符串");
        }
        return requireText(text, field);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalidField(field, "不能为空");
        }
        return value;
    }

    private static String requireExactText(String actual, String expected, String field) {
        String value = requireText(actual, field);
        if (!expected.equals(value)) {
            throw invalidField(field, "必须为 " + expected);
        }
        return value;
    }

    private static void requirePort(int port, String field) {
        if (port < 1 || port > 65_535) {
            throw invalidField(field, "必须位于 1 到 65535 之间");
        }
    }

    private static IllegalArgumentException invalidField(String field, String reason) {
        return new IllegalArgumentException("全栈上下文字段 " + field + " " + reason);
    }
}
