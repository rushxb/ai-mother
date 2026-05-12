package com.yupi.yuaicodemother.orchestration.agent;

import cn.hutool.core.io.FileUtil;
import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.GenerationOrchestrationRequest;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.dag.GenerationAgentContext;
import com.yupi.yuaicodemother.orchestration.dag.GenerationOrchestrationTask;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextAgentNodeTemplateTest {

    @Test
    void shouldReadBootstrappedVueWorkspaceAsContextForNewProject() {
        Path outputRoot = Path.of("target", "test-workspaces", "template-context");
        FileUtil.del(outputRoot.toFile());
        try {
            GenerationAgentSupport support = new GenerationAgentSupport(
                    new com.yupi.yuaicodemother.orchestration.recipe.GenerationRecipeLibrary(),
                    new com.yupi.yuaicodemother.orchestration.index.WorkspaceSemanticIndexService(),
                    outputRoot
            );
            TemplateAgentNode templateAgentNode = new TemplateAgentNode(
                    new com.yupi.yuaicodemother.orchestration.template.VueProjectTemplateBootstrapService(
                            outputRoot,
                            new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    ),
                    new com.yupi.yuaicodemother.orchestration.template.BackendProjectTemplateBootstrapService(
                            outputRoot,
                            new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    )
            );
            ContextAgentNode contextAgentNode = new ContextAgentNode(support);

            App app = new App();
            app.setId(301L);
            app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
            GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                    app,
                    "创建一个 Vue 后台管理仪表盘",
                    CodeGenTypeEnum.HTML,
                    "create",
                    false,
                    null,
                    prompt -> CodeGenTypeEnum.VUE_PROJECT
            );
            GenerationAgentContext context = new GenerationAgentContext(request, new GenerationOrchestrationTask(), true);
            context.setTargetType(CodeGenTypeEnum.VUE_PROJECT);

            templateAgentNode.execute(context).artifacts().forEach(artifact -> context.putArtifacts(java.util.List.of(artifact)));
            GenerationArtifact artifact = contextAgentNode.execute(context).artifacts().getFirst();

            assertEquals("context_summary", artifact.key());
            assertEquals("management", artifact.payload().get("intent"));
            assertEquals("intent_selected_files", artifact.payload().get("contextMode"));
            java.util.List<?> selectedFiles = (java.util.List<?>) artifact.payload().get("selectedFiles");
            assertFalse(selectedFiles.isEmpty());
            assertTrue(String.valueOf(artifact.payload().get("projectContext")).contains(String.valueOf(selectedFiles.getFirst())));
        } finally {
            FileUtil.del(outputRoot.toFile());
        }
    }
}
