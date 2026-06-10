package com.rush.rushaicodemother.orchestration.routing;

import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingServiceFactory;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.edit.GenerationEditRouteService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import org.junit.jupiter.api.Test;

import cn.hutool.core.io.FileUtil;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HeavyGenerationIntentAssemblerTest {

    @Test
    void shouldRouteSmallExistingProjectRequestToLightweightEdit() throws Exception {
        App app = new App();
        app.setId(99L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        Path workspace = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_99");
        try {
            Files.createDirectories(workspace);
            AppDatabaseResourceService databaseResourceService = mock(AppDatabaseResourceService.class);
            when(databaseResourceService.appendGenerationInstructionIfEnabled(app, "把标题改成仪表盘"))
                    .thenReturn("把标题改成仪表盘");
            GenerationWorkspaceService workspaceService = new GenerationWorkspaceService();
            HeavyGenerationIntentAssembler assembler = new HeavyGenerationIntentAssembler(
                    mock(AiCodeGenTypeRoutingServiceFactory.class),
                    databaseResourceService,
                    new GenerationEditRouteService(workspaceService),
                    workspaceService
            );

            HeavyGenerationIntentDecision decision = assembler.assemble(app, "把标题改成仪表盘");

            assertEquals(GenerationRoute.LIGHTWEIGHT_EDIT, decision.route());
            assertTrue(decision.hasGeneratedCode());
            assertEquals(CodeGenTypeEnum.VUE_PROJECT, decision.targetType());
        } finally {
            FileUtil.del(workspace);
        }
    }
}
