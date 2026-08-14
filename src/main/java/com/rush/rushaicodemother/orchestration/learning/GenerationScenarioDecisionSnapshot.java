package com.rush.rushaicodemother.orchestration.learning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从冻结任务命令确定性派生的场景与路由决策事实，不保存原始提示词。 */
public record GenerationScenarioDecisionSnapshot(
        String intentSignature,
        String profileVersion,
        String decisionVersion,
        String evidenceJson,
        String alternativesJson,
        String releaseIdentity
) {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public static GenerationScenarioDecisionSnapshot from(GenerationTaskCommand command) {
        var decision = command.scenarioDecision();
        var profile = decision.intentProfile();
        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("operationType", profile.operationType());
        scenario.put("affectedScopes", profile.affectedScopes().stream().sorted().toList());
        scenario.put("semanticComplexity", profile.semanticComplexity());
        scenario.put("requiresBackend", profile.requiresBackend());
        scenario.put("requiresDatabase", profile.requiresDatabase());
        scenario.put("destructiveRisk", profile.destructiveRisk());
        scenario.put("expectedFileBucket", fileBucket(profile.expectedFileCount()));
        scenario.put("validationRisk", profile.validationRisk());
        scenario.put("codeGenType", decision.targetType());
        scenario.put("mutability", decision.mutability());
        scenario.put("databaseResourceRequired", decision.requiredResources().databaseRequired());
        scenario.put("toolPermissionProfile", decision.toolPermissionProfile());

        Map<String, Object> evidence = new LinkedHashMap<>(scenario);
        evidence.put("confidence", profile.confidence());
        Map<String, Object> ambiguity = new LinkedHashMap<>();
        ambiguity.put("unresolvedDimensions", profile.ambiguitySignal().unresolvedDimensions()
                .stream().sorted().toList());
        ambiguity.put("scopeFallback", profile.ambiguitySignal().scopeFallback());
        ambiguity.put("shortPrompt", profile.ambiguitySignal().shortPrompt());
        evidence.put("ambiguity", ambiguity);
        evidence.put("decisionCode", decision.routeDecision().decisionCode());
        evidence.put("selectedRoute", decision.routeDecision().route());
        evidence.put("validationLevel", decision.validationFloor());
        List<String> alternatives = Arrays.stream(GenerationMode.values())
                .map(GenerationMode::route)
                .filter(route -> !route.equals(decision.routeDecision().route()))
                .sorted()
                .toList();
        return new GenerationScenarioDecisionSnapshot(
                sha256(json(scenario)), decision.ruleVersion(), decision.releaseFingerprint(),
                json(evidence), json(alternatives),
                decision.releaseFingerprint());
    }

    private static String fileBucket(int count) {
        if (count <= 2) return "small";
        if (count <= 7) return "medium";
        return "large";
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("场景决策快照序列化失败", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }
}
