package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackPortAllocator;
import com.rush.rushaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.template.bootstrap.BackendGenerationTemplateBootstrapAdapter;
import com.rush.rushaicodemother.orchestration.template.bootstrap.FullStackGenerationTemplateBootstrapAdapter;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapAdapter;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapRegistry;
import com.rush.rushaicodemother.orchestration.template.bootstrap.VueGenerationTemplateBootstrapAdapter;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class TemplateAgentNodeSpringWiringTest {

    @Test
    void productionTemplateBootstrapAdaptersMustBeRegisteredAsOneDagNode() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    VueProjectTemplateBootstrapService.class,
                    () -> mock(VueProjectTemplateBootstrapService.class));
            context.registerBean(
                    BackendProjectTemplateBootstrapService.class,
                    () -> mock(BackendProjectTemplateBootstrapService.class));
            context.registerBean(
                    FullStackPortAllocator.class,
                    () -> mock(FullStackPortAllocator.class));
            context.registerBean(
                    GenerationWorkspaceService.class,
                    () -> mock(GenerationWorkspaceService.class));
            context.register(
                    VueGenerationTemplateBootstrapAdapter.class,
                    BackendGenerationTemplateBootstrapAdapter.class,
                    FullStackGenerationTemplateBootstrapAdapter.class,
                    GenerationTemplateBootstrapRegistry.class,
                    TemplateAgentNode.class
            );

            context.refresh();

            var adapters = context.getBeansOfType(
                    GenerationTemplateBootstrapAdapter.class).values();
            assertEquals(3, adapters.size());
            assertEquals(
                    Set.of(
                            CodeGenTypeEnum.VUE_PROJECT,
                            CodeGenTypeEnum.BACKEND_PROJECT,
                            CodeGenTypeEnum.FULL_STACK_PROJECT
                    ),
                    adapters.stream()
                            .map(GenerationTemplateBootstrapAdapter::codeGenType)
                            .collect(Collectors.toSet())
            );
            assertNotNull(context.getBean(TemplateAgentNode.class));
        }
    }
}
