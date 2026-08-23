package com.rush.rushaicodemother.orchestration.routing;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationResourceRequirements;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeavyGenerationIntentAssemblerTest {

    @Test
    void frozenScenarioMustSupplyTargetValidationAndDatabaseInstruction() {
        String userMessage = "把现有项目升级为企业应用";
        String generationMessage = userMessage + "\n平台数据库接入说明";
        App app = app(912_347L, CodeGenTypeEnum.VUE_PROJECT);
        AppDatabaseResourceService databaseResourceService = mock(AppDatabaseResourceService.class);
        when(databaseResourceService.appendGenerationInstructionIfEnabled(app, userMessage))
                .thenReturn(generationMessage);
        HeavyGenerationIntentAssembler assembler = new HeavyGenerationIntentAssembler(
                databaseResourceService,
                workspaceService()
        );

        HeavyGenerationIntentDecision decision = assembler.assemble(
                app,
                userMessage,
                frozenScenario(
                        CodeGenTypeEnum.FULL_STACK_PROJECT,
                        GenerationMode.HEAVY_EXPERT,
                        ExpectedValidationLevel.EXPERT)
        );

        assertEquals(GenerationRoute.HEAVY_GENERATION, decision.route());
        assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT, decision.targetType());
        assertEquals(generationMessage, decision.generationMessage());
        verify(databaseResourceService).appendGenerationInstructionIfEnabled(app, userMessage);
    }

    @Test
    void existingWorkspaceMustOnlyEnrichExecutionState() throws Exception {
        App app = app(912_348L, CodeGenTypeEnum.VUE_PROJECT);
        Path workspace = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + app.getId());
        try {
            Files.createDirectories(workspace);
            AppDatabaseResourceService databaseResourceService = mock(AppDatabaseResourceService.class);
            when(databaseResourceService.appendGenerationInstructionIfEnabled(app, "更新页面"))
                    .thenReturn("更新页面");
            HeavyGenerationIntentAssembler assembler = new HeavyGenerationIntentAssembler(
                    databaseResourceService,
                    workspaceService()
            );

            HeavyGenerationIntentDecision decision = assembler.assemble(
                    app,
                    "更新页面",
                    frozenScenario(
                            CodeGenTypeEnum.VUE_PROJECT,
                            GenerationMode.HEAVY_EXPERT,
                            ExpectedValidationLevel.FAST)
            );

            assertTrue(decision.hasGeneratedCode());
            assertEquals(AppConstant.GENERATING_STAGE_UPDATE, decision.generatingStage());
            assertEquals(CodeGenTypeEnum.VUE_PROJECT, decision.targetType());
        } finally {
            FileUtil.del(workspace);
        }
    }

    @Test
    void nonHeavyScenarioMustFailClosed() {
        App app = app(912_349L, CodeGenTypeEnum.VUE_PROJECT);
        HeavyGenerationIntentAssembler assembler = new HeavyGenerationIntentAssembler(
                mock(AppDatabaseResourceService.class),
                workspaceService()
        );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> assembler.assemble(
                        app,
                        "更新页面",
                        frozenScenario(
                                CodeGenTypeEnum.VUE_PROJECT,
                                GenerationMode.LIGHT_EDIT,
                                ExpectedValidationLevel.FAST)
                )
        );

        assertEquals("Heavy 准备阶段只能消费 HEAVY_EXPERT 场景决策", failure.getMessage());
    }

    @Test
    void frozenTargetMustNotDowngradeExistingProject() {
        App app = app(912_350L, CodeGenTypeEnum.FULL_STACK_PROJECT);
        HeavyGenerationIntentAssembler assembler = new HeavyGenerationIntentAssembler(
                mock(AppDatabaseResourceService.class),
                workspaceService()
        );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> assembler.assemble(
                        app,
                        "更新页面",
                        frozenScenario(
                                CodeGenTypeEnum.VUE_PROJECT,
                                GenerationMode.HEAVY_EXPERT,
                                ExpectedValidationLevel.EXPERT)
                )
        );

        assertEquals("冻结场景决策不得降低应用工程类型", failure.getMessage());
    }

    private GenerationScenarioDecision frozenScenario(CodeGenTypeEnum targetType,
                                                       GenerationMode mode,
                                                       ExpectedValidationLevel validationLevel) {
        return GenerationScenarioDecision.restoreLegacy(
                IntentProfile.unknown(),
                targetType,
                GenerationResourceRequirements.none(),
                new GenerationModeDecision(
                        mode,
                        0.91,
                        "frozen scenario",
                        FallbackPolicy.NONE,
                        validationLevel,
                        ""
                ),
                10
        );
    }

    private App app(Long appId, CodeGenTypeEnum codeGenType) {
        App app = new App();
        app.setId(appId);
        app.setCodeGenType(codeGenType.getValue());
        return app;
    }

    private GenerationWorkspaceService workspaceService() {
        return new GenerationWorkspaceService(new CodeStorageProperties());
    }
}
