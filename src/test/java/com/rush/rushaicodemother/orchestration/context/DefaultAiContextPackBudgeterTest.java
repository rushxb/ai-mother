package com.rush.rushaicodemother.orchestration.context;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAiContextPackBudgeterTest {

    private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");

    @Test
    void budgetKeepsMandatoryRulesAndSelectsRecentHighValueMemory() {
        AiContextPackBudgetProperties properties = constrainedProperties();
        properties.setMaxSemanticMemorySections(1);
        AiContextTokenEstimator estimator = new OpenAiCompatibleContextTokenEstimator(properties);
        DefaultAiContextPackBudgeter budgeter = new DefaultAiContextPackBudgeter(
                properties, estimator, Clock.fixed(NOW, ZoneOffset.UTC));
        AiContextBoundaryService boundary = new AiContextBoundaryService();
        String recentMemory = boundary.protectHistoricalMemory(
                "RECENT_HIGH_VALUE " + "x".repeat(1_000)).content();
        String oldMemory = boundary.protectHistoricalMemory(
                "OLD_STALE_MEMORY " + "y".repeat(1_000)).content();
        AiContextPack source = new AiContextPack(1L, "app", "vue_project", List.of(
                section(AiContextPackSectionType.APP_SCOPE, "scope", "appId=1", 10, Map.of()),
                section(AiContextPackSectionType.SEMANTIC_MEMORY, "recent", recentMemory, 21,
                        Map.of("score", 0.82, "createdAt", NOW.minus(Duration.ofDays(1)))),
                section(AiContextPackSectionType.SEMANTIC_MEMORY, "old", oldMemory, 22,
                        Map.of("score", 0.99, "createdAt", NOW.minus(Duration.ofDays(180)))),
                section(AiContextPackSectionType.RECENT_TASK, "task", "current related task", 30, Map.of()),
                section(AiContextPackSectionType.USAGE_RULE, "rules", "current request wins", 90, Map.of())
        ));

        AiContextPack budgeted = budgeter.apply(source);
        String rendered = budgeted.render();

        assertTrue(estimator.estimate(rendered) <= properties.maxTokens("vue_project"));
        assertTrue(rendered.contains("scope"));
        assertTrue(rendered.contains("current request wins"));
        assertTrue(budgeted.sections().stream().anyMatch(section -> "recent".equals(section.title())));
        assertFalse(budgeted.sections().stream().anyMatch(section -> "old".equals(section.title())));
        assertTrue(rendered.contains("END_UNTRUSTED_HISTORICAL_MEMORY"));
    }

    @Test
    void repairPackUsesSmallerDedicatedBudget() {
        AiContextPackBudgetProperties properties = constrainedProperties();
        properties.setGenerationMaxTokens(400);
        properties.setRepairMaxTokens(256);
        AiContextTokenEstimator estimator = new OpenAiCompatibleContextTokenEstimator(properties);
        DefaultAiContextPackBudgeter budgeter = new DefaultAiContextPackBudgeter(
                properties, estimator, Clock.fixed(NOW, ZoneOffset.UTC));
        AiContextPack source = new AiContextPack(1L, "", "repair", List.of(
                section(AiContextPackSectionType.APP_SCOPE, "scope", "error scope", 10, Map.of()),
                section(AiContextPackSectionType.BUILD_TRACE, "current", "z".repeat(2_000), 20, Map.of()),
                section(AiContextPackSectionType.USAGE_RULE, "rules", "repair only relevant files", 90, Map.of())
        ));

        AiContextPack budgeted = budgeter.apply(source);

        assertTrue(estimator.estimate(budgeted.render()) <= properties.maxTokens("repair"));
        assertTrue(budgeted.render().contains("repair only relevant files"));
    }

    @Test
    void chineseContextIsTruncatedByTokenizerBudgetRatherThanCharacterRatio() {
        AiContextPackBudgetProperties properties = constrainedProperties();
        properties.setGenerationMaxTokens(512);
        properties.setMaxSectionTokens(180);
        AiContextTokenEstimator estimator = new OpenAiCompatibleContextTokenEstimator(properties);
        DefaultAiContextPackBudgeter budgeter = new DefaultAiContextPackBudgeter(
                properties, estimator, Clock.fixed(NOW, ZoneOffset.UTC));
        String chineseMemory = new AiContextBoundaryService().protectHistoricalMemory(
                "修复登录页面的手机号验证码、倒计时按钮和错误提示。".repeat(160)).content();
        AiContextPack source = new AiContextPack(1L, "", "vue_project", List.of(
                section(AiContextPackSectionType.APP_SCOPE, "scope", "appId=1", 10, Map.of()),
                section(AiContextPackSectionType.SEMANTIC_MEMORY, "memory", chineseMemory, 20,
                        Map.of("score", 0.95, "createdAt", NOW)),
                section(AiContextPackSectionType.USAGE_RULE, "rules", "current request wins", 90, Map.of())
        ));

        AiContextPack budgeted = budgeter.apply(source);
        AiContextPackSection memory = budgeted.sections().stream()
                .filter(section -> section.type() == AiContextPackSectionType.SEMANTIC_MEMORY)
                .findFirst()
                .orElseThrow();

        assertTrue(estimator.estimate(source.render()) > properties.maxTokens("vue_project"));
        assertTrue(estimator.estimate(budgeted.render()) <= properties.maxTokens("vue_project"));
        assertEquals(Boolean.TRUE, memory.metadata().get("truncated"));
        assertTrue(((Number) memory.metadata().get("estimatedTokens")).intValue()
                <= properties.getMaxSectionTokens());
        assertTrue(memory.content().contains("END_UNTRUSTED_HISTORICAL_MEMORY"));
    }

    private AiContextPackBudgetProperties constrainedProperties() {
        AiContextPackBudgetProperties properties = new AiContextPackBudgetProperties();
        properties.setGenerationMaxTokens(512);
        properties.setRepairMaxTokens(384);
        properties.setMaxSectionTokens(160);
        properties.setMinimumSectionTokens(16);
        properties.setSemanticMemoryHalfLife(Duration.ofDays(30));
        properties.setMinimumSemanticTrust(0.1);
        return properties;
    }

    private AiContextPackSection section(AiContextPackSectionType type,
                                         String title,
                                         String content,
                                         int priority,
                                         Map<String, Object> metadata) {
        return new AiContextPackSection(type, title, content, priority, metadata);
    }
}
