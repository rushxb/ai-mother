package com.rush.rushaicodemother.orchestration.fullstack;

import java.util.LinkedHashMap;
import java.util.Map;

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

    public static FullStackGenerationContext create(Long appId, int frontendPort, int backendPort, String workspaceRoot) {
        String backendBaseUrl = "http://127.0.0.1:" + backendPort;
        String apiPrefix = "/api";
        return new FullStackGenerationContext(
                appId,
                workspaceRoot,
                workspaceRoot + "/frontend",
                workspaceRoot + "/backend",
                frontendPort,
                backendPort,
                "http://127.0.0.1:" + frontendPort,
                backendBaseUrl,
                apiPrefix,
                "VITE_API_BASE_URL",
                backendBaseUrl + apiPrefix,
                ":" + backendPort,
                "reserved"
        );
    }

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
}
