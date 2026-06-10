package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.template.SlotFillResult;
import com.rush.rushaicodemother.orchestration.template.TemplateSlotFillService;
import com.rush.rushaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class CreateTemplateRuntimeTest {

    @Test
    void shouldKeepTemplateSkeletonWhenAnySlotGroupFails() {
        Path workspaceRoot = Path.of("target/test-workspaces/create-template-runtime")
                .toAbsolutePath()
                .normalize();
        TemplateSlotFillService slotFillService = mock(TemplateSlotFillService.class);
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        Path projectRoot = workspaceRoot.resolve("vue_project_1").toAbsolutePath().normalize();
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), anyString()))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-landing",
                        projectRoot.toString(),
                        1
                ));
        when(vueBootstrapService.resolveProjectRoot(anyLong())).thenReturn(projectRoot);
        CreateTemplateRuntime runtime = new CreateTemplateRuntime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                new CreatePreWriteValidationService(new com.rush.rushaicodemother.orchestration.codegraph.StructuredSyntaxValidationService()),
                null,
                patchApplyService,
                new LandingSlotFallbackRenderer(),
                slotFillService,
                vueBootstrapService
        );
        when(slotFillService.fillSlots(anyString(), anyLong(), anyString(), any())).thenReturn(null);

        SlotFillResult result = runtime.generate(app(), request(), plan());

        assertTrue(result.patchOperations().isEmpty());
        Map<?, ?> telemetry = (Map<?, ?>) result.metadata().get("telemetry");
        assertEquals(false, telemetry.get("fallback"));
        assertEquals(true, telemetry.get("degraded"));
        verify(patchApplyService, never()).applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString());
    }

    @Test
    void shouldCoalesceSlotGroupsByTemplateBeforeCallingAi() {
        Path workspaceRoot = Path.of("target/test-workspaces/create-template-runtime")
                .toAbsolutePath()
                .normalize();
        TemplateSlotFillService slotFillService = mock(TemplateSlotFillService.class);
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        Path projectRoot = workspaceRoot.resolve("vue_project_1").toAbsolutePath().normalize();
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), anyString()))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-landing",
                        projectRoot.toString(),
                        1
                ));
        when(vueBootstrapService.resolveProjectRoot(anyLong())).thenReturn(projectRoot);
        CreateTemplateRuntime runtime = new CreateTemplateRuntime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                new CreatePreWriteValidationService(new com.rush.rushaicodemother.orchestration.codegraph.StructuredSyntaxValidationService()),
                null,
                patchApplyService,
                new LandingSlotFallbackRenderer(),
                slotFillService,
                vueBootstrapService
        );
        when(slotFillService.fillSlots(anyString(), anyLong(), anyString(), any()))
                .thenReturn(new SlotFillResult(
                        "vue-web-landing",
                        List.of("landing_core_data"),
                        List.of(PatchOperation.modify("src/data/landingData.ts", "export const brand = {}\nexport const nav = []")),
                        "ok",
                        42,
                        List.of(),
                        Map.of()
                ));
        when(patchApplyService.applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(PatchApplyResult.applied(1L, "task", projectRoot.toString(), 1, List.of("src/data/landingData.ts")));

        SlotFillResult result = runtime.generate(app(), request(), multiGroupPlan());

        assertEquals(1, result.filledSlotCount());
        Map<?, ?> telemetry = (Map<?, ?>) result.metadata().get("telemetry");
        assertEquals(1, telemetry.get("slotGroupCount"));
        assertEquals(1, telemetry.get("aiCallCount"));
        ArgumentCaptor<List<String>> slotIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(slotFillService, times(1)).fillSlots(anyString(), anyLong(), anyString(), slotIdsCaptor.capture());
        assertEquals(List.of("landing_core_data"), slotIdsCaptor.getValue());
    }

    @Test
    void shouldUseLandingFallbackWhenAiSlotFillTimesOut() {
        Path workspaceRoot = Path.of("target/test-workspaces/create-template-runtime")
                .toAbsolutePath()
                .normalize();
        TemplateSlotFillService slotFillService = mock(TemplateSlotFillService.class);
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        Path projectRoot = workspaceRoot.resolve("vue_project_1").toAbsolutePath().normalize();
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), anyString()))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-landing",
                        projectRoot.toString(),
                        1
                ));
        when(vueBootstrapService.resolveProjectRoot(anyLong())).thenReturn(projectRoot);
        CreateTemplateRuntime runtime = new CreateTemplateRuntime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                new CreatePreWriteValidationService(new com.rush.rushaicodemother.orchestration.codegraph.StructuredSyntaxValidationService()),
                null,
                patchApplyService,
                new LandingSlotFallbackRenderer(),
                slotFillService,
                vueBootstrapService
        );
        when(slotFillService.fillSlots(anyString(), anyLong(), anyString(), any())).thenReturn(null);
        when(slotFillService.consumeLastFailureReason()).thenReturn("ai_slot_fill_exception:Read timed out");
        when(patchApplyService.applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(PatchApplyResult.applied(1L, "task", projectRoot.toString(), 1, List.of("src/data/landingData.ts")));

        SlotFillResult result = runtime.generate(app(), request(), landingDataPlan());

        assertEquals(1, result.filledSlotCount());
        assertEquals(1, result.patchOperationCount());
        Map<?, ?> telemetry = (Map<?, ?>) result.metadata().get("telemetry");
        assertEquals(false, telemetry.get("fallback"));
        assertEquals(true, telemetry.get("degraded"));
        verify(patchApplyService, times(1)).applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString());
    }

    private App app() {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        return app;
    }

    private GenerationTaskRequest request() {
        User user = new User();
        user.setId(2L);
        return new GenerationTaskRequest(app(), "做一个企业官网", user);
    }

    private CreateGenerationPlan plan() {
        return new CreateGenerationPlan(
                CodeGenTypeEnum.VUE_PROJECT,
                new CreateTemplateManifest("vue-web-landing", CodeGenTypeEnum.VUE_PROJECT, "landing"),
                List.of(),
                List.of(new SlotGroup("hero", "vue-web-landing", "base", List.of("landing_data"), 1)),
                0.9,
                "test",
                "test",
                ""
        );
    }

    private CreateGenerationPlan multiGroupPlan() {
        return new CreateGenerationPlan(
                CodeGenTypeEnum.VUE_PROJECT,
                new CreateTemplateManifest("vue-web-landing", CodeGenTypeEnum.VUE_PROJECT, "landing"),
                List.of(
                        new FeatureModuleManifest("landing-page", "Landing", "vue-web-landing", List.of("landing_core_data"), ""),
                        new FeatureModuleManifest("landing-contact", "Contact", "vue-web-landing", List.of("landing_core_data"), "")
                ),
                List.of(
                        new SlotGroup("landing-page-slots", "vue-web-landing", "landing-page", List.of("landing_core_data"), 0),
                        new SlotGroup("landing-contact-slots", "vue-web-landing", "landing-contact", List.of("landing_core_data"), 1)
                ),
                0.9,
                "test",
                "test",
                ""
        );
    }

    private CreateGenerationPlan landingDataPlan() {
        return new CreateGenerationPlan(
                CodeGenTypeEnum.VUE_PROJECT,
                new CreateTemplateManifest("vue-web-landing", CodeGenTypeEnum.VUE_PROJECT, "landing"),
                List.of(new FeatureModuleManifest("landing-page", "Landing", "vue-web-landing",
                        List.of("landing_core_data"), "")),
                List.of(new SlotGroup("landing-page-slots", "vue-web-landing", "landing-page",
                        List.of("landing_core_data"), 0)),
                0.9,
                "test",
                "test",
                ""
        );
    }
}
