package com.rush.rushaicodemother.orchestration.artifact;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QualityGateArtifactTest {

    @Test
    void shouldRoundTripGateResultAndPreserveReviewDetails() {
        QualityGateResult result = QualityGateResult.passed(
                List.of("依赖版本建议升级"),
                List.of("生成规范已构建")
        );
        Map<String, GenerationArtifact> reviewedArtifacts = reviewedArtifacts();
        QualityGateArtifact.ReviewSubject reviewSubject = QualityGateArtifact.reviewSubject(
                CodeGenTypeEnum.VUE_PROJECT,
                reviewedArtifacts
        );

        GenerationArtifact persisted = QualityGateArtifact.fromResult(
                result,
                reviewSubject,
                Map.of("securityWarnings", List.of("依赖版本建议升级"))
        ).toArtifact("Review", "质量门禁");
        QualityGateArtifact restored = QualityGateArtifact.fromArtifact(
                persisted,
                reviewSubject
        );

        assertEquals(result, restored.result());
        assertEquals(
                List.of("依赖版本建议升级"),
                persisted.payload().get("securityWarnings")
        );
    }

    @Test
    void blankReviewMessagesMustBeRejectedBeforePersistence() {
        QualityGateResult result = QualityGateResult.passed(
                List.of(" "),
                List.of("生成规范已构建")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> QualityGateArtifact.fromResult(
                        result,
                        QualityGateArtifact.reviewSubject(
                                CodeGenTypeEnum.VUE_PROJECT,
                                reviewedArtifacts()
                        ),
                        Map.of()
                )
        );
    }

    @Test
    void changedChangePlanMustInvalidatePersistedGate() {
        Map<String, GenerationArtifact> originalArtifacts = reviewedArtifactsWithPlan("src/App.vue");
        QualityGateArtifact.ReviewSubject originalSubject = QualityGateArtifact.reviewSubject(
                CodeGenTypeEnum.VUE_PROJECT,
                originalArtifacts
        );
        GenerationArtifact persisted = QualityGateArtifact.fromResult(
                QualityGateResult.passed(List.of(), List.of("变更计划已通过审查")),
                originalSubject,
                Map.of()
        ).toArtifact("Review", "质量门禁");

        QualityGateArtifact.ReviewSubject changedSubject = QualityGateArtifact.reviewSubject(
                CodeGenTypeEnum.VUE_PROJECT,
                reviewedArtifactsWithPlan("src/main.js")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> QualityGateArtifact.fromArtifact(persisted, changedSubject)
        );
    }

    @Test
    void changedBackendApiContractMustInvalidatePersistedGate() {
        QualityGateArtifact.ReviewSubject originalSubject = QualityGateArtifact.reviewSubject(
                CodeGenTypeEnum.BACKEND_PROJECT,
                reviewedArtifactsWithApiContract("product", "Product", "products")
        );
        GenerationArtifact persisted = QualityGateArtifact.fromResult(
                QualityGateResult.passed(List.of(), List.of("API 契约已通过审查")),
                originalSubject,
                Map.of()
        ).toArtifact("Review", "质量门禁");

        QualityGateArtifact.ReviewSubject changedSubject = QualityGateArtifact.reviewSubject(
                CodeGenTypeEnum.BACKEND_PROJECT,
                reviewedArtifactsWithApiContract("order", "Order", "orders")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> QualityGateArtifact.fromArtifact(persisted, changedSubject)
        );
    }

    private Map<String, GenerationArtifact> reviewedArtifacts() {
        GenerationArtifact specification = GenerationSpecificationArtifact.execution(
                "生成可维护的管理后台",
                true,
                true,
                Map.of()
        ).toArtifact("Code", "生成规范");
        return Map.of(GenerationSpecificationArtifact.KEY, specification);
    }

    private Map<String, GenerationArtifact> reviewedArtifactsWithPlan(String modifiedFile) {
        Map<String, GenerationArtifact> artifacts = new LinkedHashMap<>(reviewedArtifacts());
        ChangePlan changePlan = new ChangePlan(
                "v1",
                "targeted_edit",
                List.of(),
                List.of(modifiedFile),
                List.of(),
                List.of("frontend"),
                "build_validation",
                "rollback_to_last_stable_snapshot_or_manual_retry"
        );
        artifacts.put(ChangePlan.KEY, changePlan.toArtifact("Code", "变更计划"));
        return Map.copyOf(artifacts);
    }

    private Map<String, GenerationArtifact> reviewedArtifactsWithApiContract(
            String moduleName,
            String entityName,
            String tableName
    ) {
        Map<String, GenerationArtifact> artifacts = new LinkedHashMap<>(reviewedArtifacts());
        ApiContractArtifact apiContract = ApiContractArtifact.create(
                false,
                "生成管理接口",
                new ApiContractArtifact.ApiDomain(
                        moduleName,
                        entityName,
                        tableName,
                        List.of(new ApiContractArtifact.ApiField(
                                "id",
                                "int64",
                                "integer",
                                "主键"
                        ))
                )
        );
        artifacts.put(ApiContractArtifact.KEY, apiContract.toArtifact());
        return Map.copyOf(artifacts);
    }
}
