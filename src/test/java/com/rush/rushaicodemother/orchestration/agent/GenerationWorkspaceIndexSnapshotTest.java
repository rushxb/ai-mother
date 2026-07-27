package com.rush.rushaicodemother.orchestration.agent;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.config.GenerationProjectContextProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemTestFactory;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.context.GeneratedProjectContextService;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipeLibrary;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkillLibrary;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.impl.GenerationContextCompressionServiceImpl;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GenerationWorkspaceIndexSnapshotTest {

    @Test
    void plannerAndContextMustShareOneWorkspaceIndexSnapshot() throws Exception {
        Path outputRoot = Files.createTempDirectory("generation-index-snapshot-");
        Path workspace = Files.createDirectories(outputRoot.resolve("vue_project_73"));
        WorkspaceFileSystemService fileSystemService = spy(WorkspaceFileSystemTestFactory.create());
        try {
            write(workspace, "src/views/Login.vue", "<template>login token</template>");
            write(workspace, "src/router/index.ts", "export const routes = []");
            GenerationAgentSupport support = support(outputRoot, fileSystemService);
            PlannerAgentNode planner = new PlannerAgentNode(support, new GenerationRoutingSupport(support));
            ContextAgentNode contextNode = new ContextAgentNode(support);
            GenerationAgentContext context = context();

            AgentNodeResult plannerResult = planner.execute(context);
            context.putArtifacts(plannerResult.artifacts());
            AgentNodeResult contextResult = contextNode.execute(context);

            assertFalse(contextResult.artifacts().isEmpty());
            verify(fileSystemService, times(1)).scanProject(any(Path.class));
        } finally {
            FileUtil.del(outputRoot.toFile());
        }
    }

    private GenerationAgentSupport support(Path outputRoot,
                                             WorkspaceFileSystemService fileSystemService) {
        CodeStorageProperties storageProperties = new CodeStorageProperties();
        storageProperties.setOutputRootDir(outputRoot);
        return new GenerationAgentSupport(
                new GenerationRecipeLibrary(),
                new GenerationSkillLibrary(),
                new WorkspaceSemanticIndexService(fileSystemService),
                new GenerationContextCompressionServiceImpl(),
                new GenerationWorkspaceService(storageProperties),
                new GeneratedProjectContextService(
                        fileSystemService, new GenerationProjectContextProperties())
        );
    }

    private GenerationAgentContext context() {
        App app = App.builder()
                .id(73L)
                .appName("登录工作台")
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .build();
        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app,
                "优化登录 token 处理",
                CodeGenTypeEnum.VUE_PROJECT,
                "生成中",
                true,
                ignored -> CodeGenTypeEnum.VUE_PROJECT,
                "",
                "task-index-snapshot"
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-index-snapshot");
        task.setAppId(73L);
        task.setStatus("running");
        return new GenerationAgentContext(request, task, true);
    }

    private void write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
