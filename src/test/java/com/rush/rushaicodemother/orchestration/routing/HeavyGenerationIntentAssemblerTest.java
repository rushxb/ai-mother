package com.rush.rushaicodemother.orchestration.routing;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.edit.GenerationEditRouteService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HeavyGenerationIntentAssemblerTest {

    @Test
    void smallExistingProjectEditMustBypassTargetTypeRouting() throws Exception {
        App app = app(99L, CodeGenTypeEnum.VUE_PROJECT);
        Path workspace = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_99");
        try {
            Files.createDirectories(workspace);
            AppDatabaseResourceService databaseResourceService = mock(AppDatabaseResourceService.class);
            when(databaseResourceService.appendGenerationInstructionIfEnabled(app, "把标题改成仪表盘"))
                    .thenReturn("把标题改成仪表盘");
            HeavyGenerationTargetTypeRouter targetTypeRouter = mock(HeavyGenerationTargetTypeRouter.class);
            GenerationWorkspaceService workspaceService = workspaceService();
            HeavyGenerationIntentAssembler assembler = new HeavyGenerationIntentAssembler(
                    targetTypeRouter,
                    databaseResourceService,
                    new GenerationEditRouteService(workspaceService),
                    workspaceService
            );

            HeavyGenerationIntentDecision decision = assembler.assemble(app, "把标题改成仪表盘");

            assertEquals(GenerationRoute.LIGHTWEIGHT_EDIT, decision.route());
            assertTrue(decision.hasGeneratedCode());
            assertEquals(CodeGenTypeEnum.VUE_PROJECT, decision.targetType());
            verifyNoInteractions(targetTypeRouter);
        } finally {
            FileUtil.del(workspace);
        }
    }

    @Test
    void heavyRouteMustResolveTypeFromRawUserIntentBeforeDatabaseInstructionIsAppended() {
        String taskId = "heavy-routing-task";
        String userMessage = "升级为 Vue 项目并重构工程";
        String generationMessage = userMessage + "\n平台数据库接入说明";
        App app = app(912345L, CodeGenTypeEnum.HTML);
        AppDatabaseResourceService databaseResourceService = mock(AppDatabaseResourceService.class);
        when(databaseResourceService.appendGenerationInstructionIfEnabled(app, userMessage))
                .thenReturn(generationMessage);
        HeavyGenerationTargetTypeRouter targetTypeRouter = mock(HeavyGenerationTargetTypeRouter.class);
        when(targetTypeRouter.resolve(
                taskId, app.getId(), userMessage, CodeGenTypeEnum.HTML, false))
                .thenReturn(CodeGenTypeEnum.VUE_PROJECT);
        GenerationWorkspaceService workspaceService = workspaceService();
        HeavyGenerationIntentAssembler assembler = new HeavyGenerationIntentAssembler(
                targetTypeRouter,
                databaseResourceService,
                new GenerationEditRouteService(workspaceService),
                workspaceService
        );

        HeavyGenerationIntentDecision decision = assembler.assemble(taskId, app, userMessage);

        assertEquals(GenerationRoute.HEAVY_GENERATION, decision.route());
        assertEquals(CodeGenTypeEnum.VUE_PROJECT, decision.targetType());
        assertEquals(generationMessage, decision.generationMessage());
        verify(targetTypeRouter).resolve(
                taskId, app.getId(), userMessage, CodeGenTypeEnum.HTML, false);
    }

    @Test
    void heavyBackendGenerationMustRequireBuildValidation() {
        String userMessage = "创建一个简单的 Go 后端 API";
        App app = app(912346L, CodeGenTypeEnum.HTML);
        AppDatabaseResourceService databaseResourceService = mock(AppDatabaseResourceService.class);
        when(databaseResourceService.appendGenerationInstructionIfEnabled(app, userMessage))
                .thenReturn(userMessage);
        HeavyGenerationTargetTypeRouter targetTypeRouter = mock(HeavyGenerationTargetTypeRouter.class);
        when(targetTypeRouter.resolve(
                null, app.getId(), userMessage, CodeGenTypeEnum.HTML, false))
                .thenReturn(CodeGenTypeEnum.BACKEND_PROJECT);
        GenerationWorkspaceService workspaceService = workspaceService();
        HeavyGenerationIntentAssembler assembler = new HeavyGenerationIntentAssembler(
                targetTypeRouter,
                databaseResourceService,
                new GenerationEditRouteService(workspaceService),
                workspaceService
        );

        HeavyGenerationIntentDecision decision = assembler.assemble(app, userMessage);

        assertEquals(CodeGenTypeEnum.BACKEND_PROJECT, decision.targetType());
        assertTrue(decision.requiresBuild());
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
