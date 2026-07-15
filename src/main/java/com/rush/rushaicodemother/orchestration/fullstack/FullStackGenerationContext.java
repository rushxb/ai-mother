package com.rush.rushaicodemother.orchestration.fullstack;

import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

import java.nio.file.Path;
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

    public static FullStackGenerationContext create(int frontendPort,
                                                    int backendPort,
                                                    GenerationWorkspace workspace) {
        String workspaceRoot = portablePath(workspace.canonicalRootPath());
        String frontendPath = portablePath(workspace.frontendRootPath());
        String backendPath = portablePath(workspace.backendRootPath());
        String backendBaseUrl = "http://127.0.0.1:" + backendPort;
        String apiPrefix = "/api";
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
                "VITE_API_BASE_URL",
                backendBaseUrl + apiPrefix,
                ":" + backendPort,
                "reserved"
        );
    }

    private static String portablePath(Path path) {
        return path.toString().replace('\\', '/');
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
