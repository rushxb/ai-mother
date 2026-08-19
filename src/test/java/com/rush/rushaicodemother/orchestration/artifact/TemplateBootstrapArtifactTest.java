package com.rush.rushaicodemother.orchestration.artifact;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateBootstrapArtifactTest {

    @Test
    void shouldRoundTripTypedTemplateFactsWithoutDroppingProviderSpecificPayload() {
        TemplateBootstrapArtifact typedArtifact = TemplateBootstrapArtifact.fromPayload(Map.of(
                "bootstrapped", true,
                "templateId", "vue-web-basic+go-sqlite-basic",
                "projectPath", "target/workspaces/1",
                "fileCount", 27L,
                "targetType", CodeGenTypeEnum.FULL_STACK_PROJECT.getValue(),
                "reason", "",
                "frontendTemplateId", "vue-web-basic"
        ));

        GenerationArtifact persisted = typedArtifact.toArtifact("全栈项目模板");
        TemplateBootstrapArtifact restored = TemplateBootstrapArtifact.fromArtifact(persisted);

        assertEquals(TemplateBootstrapArtifact.KEY, persisted.key());
        assertTrue(restored.bootstrapped());
        assertEquals("vue-web-basic+go-sqlite-basic", restored.templateId());
        assertEquals("target/workspaces/1", restored.projectPath());
        assertEquals(27, restored.fileCount());
        assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT, restored.targetType());
        assertEquals("vue-web-basic", persisted.payload().get("frontendTemplateId"));
    }

    @Test
    void skippedArtifactMustRetainAStableSchemaEvenWhenRoutingHasNoTargetType() {
        GenerationArtifact persisted = TemplateBootstrapArtifact
                .skipped(null, "unsupported_template_type")
                .toArtifact("项目模板");

        TemplateBootstrapArtifact restored = TemplateBootstrapArtifact.fromArtifact(persisted);

        assertEquals(false, restored.bootstrapped());
        assertEquals("", restored.templateId());
        assertEquals("", restored.projectPath());
        assertEquals(0, restored.fileCount());
        assertEquals(null, restored.targetType());
        assertEquals("unsupported_template_type", restored.reason());
    }

    @Test
    void legacyCheckpointWithoutTargetTypeMustUseTheCurrentDagTargetType() {
        GenerationArtifact legacy = GenerationArtifact.of(
                TemplateBootstrapArtifact.KEY,
                "Template",
                "Vue 项目模板",
                Map.of(
                        "bootstrapped", true,
                        "templateId", "vue-web-basic",
                        "projectPath", "target/workspaces/1",
                        "fileCount", 12,
                        "reason", ""
                )
        );

        TemplateBootstrapArtifact restored = TemplateBootstrapArtifact.fromArtifact(
                legacy, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals(CodeGenTypeEnum.VUE_PROJECT, restored.targetType());
        assertEquals(
                CodeGenTypeEnum.VUE_PROJECT.getValue(),
                restored.toArtifact("Vue 项目模板").payload().get("targetType")
        );
    }

    @Test
    void payloadTargetTypeMustNotContradictTheRegistryTargetType() {
        Map<String, Object> payload = Map.of(
                "bootstrapped", true,
                "templateId", "vue-web-basic",
                "projectPath", "target/workspaces/1",
                "fileCount", 12,
                "targetType", CodeGenTypeEnum.VUE_PROJECT.getValue(),
                "reason", ""
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> TemplateBootstrapArtifact.fromPayload(
                        payload, CodeGenTypeEnum.BACKEND_PROJECT)
        );

        assertTrue(exception.getMessage().contains("targetType"));
    }
}
