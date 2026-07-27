package com.rush.rushaicodemother.orchestration.agent;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackPortAllocator;
import com.rush.rushaicodemother.orchestration.template.TemplateServiceTestFixture;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static com.rush.rushaicodemother.orchestration.agent.GenerationAgentTestFixture.support;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ContextAgentNodeTemplateTest {

    @Test
    void shouldReadBootstrappedVueWorkspaceAsContextForNewProject() {
        Path outputRoot = Path.of("target", "test-workspaces", "template-context");
        FileUtil.del(outputRoot.toFile());
        try {
            GenerationAgentSupport support = support(outputRoot);
            TemplateServiceTestFixture templateFixture = new TemplateServiceTestFixture(outputRoot);
            TemplateAgentNode templateAgentNode = new TemplateAgentNode(
                    templateFixture.vueBootstrapService(),
                    templateFixture.backendBootstrapService(),
                    new FullStackPortAllocator(templateFixture.generationWorkspaceService)
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
                    prompt -> CodeGenTypeEnum.VUE_PROJECT,
                    null
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
