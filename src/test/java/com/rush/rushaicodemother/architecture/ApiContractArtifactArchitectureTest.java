package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止 API 字段契约重新退化为 Planner、Code 与 Review 各自解释的动态 Map。 */
class ApiContractArtifactArchitectureTest {

    private static final Path SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration");

    @Test
    void plannerMustPublishApiContractThroughItsDomainArtifact() throws Exception {
        String source = Files.readString(SOURCE_ROOT.resolve(Path.of(
                "agent", "PlannerAgentNode.java")));

        assertThat(source)
                .contains("ApiContractArtifact.create(", "apiContract.toArtifact()")
                .contains("scenarioDecision.intentProfile().primaryBusinessDomain()")
                .doesNotContain("inferContractDomain", "containsAny(normalized")
                .doesNotContain("GenerationArtifact.of(\n                \"api_contract\"");
    }

    @Test
    void codeAndBackendReviewMustParseTheSameApiContract() throws Exception {
        String codeNode = Files.readString(SOURCE_ROOT.resolve(Path.of(
                "agent", "CodeAgentNode.java")));
        String backendReview = Files.readString(SOURCE_ROOT.resolve(Path.of(
                "review", "BackendQualityReviewService.java")));

        assertThat(codeNode)
                .contains("ApiContractArtifact.fromArtifact(", "ApiContractArtifact.KEY")
                .doesNotContain("getArtifactValue(\"api_contract\"");
        assertThat(backendReview)
                .contains("ApiContractArtifact.fromArtifact(", "ApiContractArtifact.KEY")
                .doesNotContain("payload().get(\"contract\")");
    }
}
