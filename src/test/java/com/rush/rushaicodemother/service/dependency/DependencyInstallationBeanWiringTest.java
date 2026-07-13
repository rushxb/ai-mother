package com.rush.rushaicodemother.service.dependency;

import com.rush.rushaicodemother.config.DependencyInstallProperties;
import com.rush.rushaicodemother.config.ExternalProcessProperties;
import com.rush.rushaicodemother.infrastructure.process.ProjectProcessTerminator;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DependencyInstallationBeanWiringTest {

    @Test
    void shouldWireDependencyInstallationModuleWithProductionConstructors() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DependencyInstallProperties.class);
            context.registerBean(ExternalProcessProperties.class);
            context.register(ProjectProcessTerminator.class);
            context.register(PnpmInstallCommandExecutor.class);
            context.register(NodeModulesIntegrityService.class);
            context.register(PnpmProjectDependencyInstaller.class);

            context.refresh();

            assertNotNull(context.getBean(ProjectDependencyInstaller.class));
            assertNotNull(context.getBean(PnpmInstallCommandExecutor.class));
            assertNotNull(context.getBean(NodeModulesIntegrityService.class));
        }
    }
}
