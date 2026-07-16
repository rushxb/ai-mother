package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.config.CodeStorageProperties;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.config.GenerationProjectContextProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationResult;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrator;
import com.rush.rushaicodemother.orchestration.context.GeneratedProjectContextService;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.routing.HeavyGenerationIntentAssembler;
import com.rush.rushaicodemother.orchestration.routing.HeavyGenerationIntentDecision;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.GenerationMemoryContextService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HeavyGenerationPreparationWorkspaceTest {

    @Test
    void projectContextMustUseResolvedWorkspaceAndPruneHiddenDirectories() throws Exception {
        long appId = 982_001L;
        App app = new App();
        app.setId(appId);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        GenerationWorkspaceService workspaceService = new GenerationWorkspaceService(new CodeStorageProperties());
        GenerationWorkspace workspace = workspaceService.resolve(app, CodeGenTypeEnum.VUE_PROJECT);
        Path projectRoot = workspace.canonicalRootPath();
        FileUtil.del(projectRoot.toFile());
        Files.createDirectories(projectRoot.resolve("src"));
        Files.createDirectories(projectRoot.resolve("node_modules"));
        Files.writeString(
                projectRoot.resolve("src/App.vue"),
                "<template><main>workspace-boundary</main></template>",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                projectRoot.resolve("node_modules/secret.ts"),
                "hidden-dependency-content",
                StandardCharsets.UTF_8
        );

        try {
            AtomicReference<String> projectContext = new AtomicReference<>();
            AtomicReference<String> orchestrationTaskId = new AtomicReference<>();
            HeavyGenerationIntentAssembler intentAssembler = mock(HeavyGenerationIntentAssembler.class);
            when(intentAssembler.assemble(eq(app), eq("更新页面"))).thenReturn(new HeavyGenerationIntentDecision(
                    GenerationRoute.HEAVY_GENERATION,
                    "test",
                    1.0,
                    CodeGenTypeEnum.VUE_PROJECT,
                    CodeGenTypeEnum.VUE_PROJECT,
                    "更新页面",
                    "生成中",
                    true,
                    true
            ));
            GenerationMemoryContextService memoryContextService = mock(GenerationMemoryContextService.class);
            when(memoryContextService.buildGenerationMemoryContext(
                    eq(app),
                    eq("更新页面"),
                    eq(CodeGenTypeEnum.VUE_PROJECT)
            )).thenReturn("");
            GenerationOrchestrator orchestrator = mock(GenerationOrchestrator.class);
            when(orchestrator.prepare(any(GenerationOrchestrationRequest.class))).thenAnswer(invocation -> {
                GenerationOrchestrationRequest request = invocation.getArgument(0);
                projectContext.set(request.projectContextSupplier().get());
                orchestrationTaskId.set(request.taskId());
                return new GenerationOrchestrationResult(
                        CodeGenTypeEnum.VUE_PROJECT,
                        CodeGenTypeEnum.VUE_PROJECT,
                        false,
                        "生成中",
                        "更新页面",
                        List.of(),
                        new HashMap<>(),
                        null,
                        Map.of(),
                        "runtime-workspace-task"
                );
            });
            GeneratedProjectContextService projectContextService = new GeneratedProjectContextService(
                    workspaceService,
                    new WorkspaceFileSystemService(new WorkspaceFileSystemProperties()),
                    new GenerationProjectContextProperties()
            );
            HeavyGenerationPreparationService service = new HeavyGenerationPreparationService(
                    intentAssembler,
                    memoryContextService,
                    orchestrator,
                    mock(GenerationToolExecutionContextService.class),
                    projectContextService
            );

            service.prepare("runtime-workspace-task", app, "更新页面");

            assertEquals("runtime-workspace-task", orchestrationTaskId.get());
            String context = projectContext.get();
            assertTrue(context.contains("src/App.vue"));
            assertTrue(context.contains("workspace-boundary"));
            assertFalse(context.contains("node_modules"));
            assertFalse(context.contains("hidden-dependency-content"));
        } finally {
            FileUtil.del(projectRoot.toFile());
        }
    }
}