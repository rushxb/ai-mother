package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.config.TemplateMaterializationProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.orchestration.agent.template.BackendGenerationTemplateBootstrapAdapter;
import com.rush.rushaicodemother.orchestration.agent.template.FullStackGenerationTemplateBootstrapAdapter;
import com.rush.rushaicodemother.orchestration.agent.template.GenerationTemplateBootstrapAdapter;
import com.rush.rushaicodemother.orchestration.agent.template.VueGenerationTemplateBootstrapAdapter;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackPortAllocator;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.file.Path;
import java.util.List;

public final class TemplateServiceTestFixture {

    final ProjectTemplateCatalog templateCatalog;
    final WorkspaceFileSystemService workspaceFileSystemService;
    public final GenerationWorkspaceService generationWorkspaceService;
    final TemplateMaterializationProperties materializationProperties;
    final ProjectTemplateMaterializer templateMaterializer;
    final ProjectTemplateBootstrapper templateBootstrapper;

    public TemplateServiceTestFixture(Path outputRoot) {
        this(outputRoot, new PathMatchingResourcePatternResolver(), new TemplateMaterializationProperties());
    }

    TemplateServiceTestFixture(Path outputRoot,
                               PathMatchingResourcePatternResolver resourceResolver,
                               TemplateMaterializationProperties properties) {
        Path normalizedOutputRoot = outputRoot.toAbsolutePath().normalize();
        CodeStorageProperties storageProperties = new CodeStorageProperties();
        storageProperties.setOutputRootDir(normalizedOutputRoot);
        storageProperties.setDeployRootDir(normalizedOutputRoot.resolveSibling(normalizedOutputRoot.getFileName() + "-deploy"));
        storageProperties.setSnapshotRootDir(normalizedOutputRoot.resolveSibling(normalizedOutputRoot.getFileName() + "-snapshot"));
        templateCatalog = new ProjectTemplateCatalog();
        workspaceFileSystemService = new WorkspaceFileSystemService(new WorkspaceFileSystemProperties());
        generationWorkspaceService = new GenerationWorkspaceService(storageProperties);
        materializationProperties = properties;
        templateMaterializer = new ProjectTemplateMaterializer(
                properties,
                templateCatalog,
                workspaceFileSystemService,
                resourceResolver
        );
        templateBootstrapper = new ProjectTemplateBootstrapper(
                templateMaterializer,
                workspaceFileSystemService
        );
    }

    public VueProjectTemplateBootstrapService vueBootstrapService() {
        return new VueProjectTemplateBootstrapService(
                generationWorkspaceService,
                templateBootstrapper
        );
    }

    public BackendProjectTemplateBootstrapService backendBootstrapService() {
        return new BackendProjectTemplateBootstrapService(
                generationWorkspaceService,
                templateBootstrapper
        );
    }

    /** 构造与生产一致的三类模板初始化 adapter。 */
    public List<GenerationTemplateBootstrapAdapter> templateBootstrapAdapters() {
        VueProjectTemplateBootstrapService vueService = vueBootstrapService();
        BackendProjectTemplateBootstrapService backendService = backendBootstrapService();
        return List.of(
                new VueGenerationTemplateBootstrapAdapter(vueService),
                new BackendGenerationTemplateBootstrapAdapter(backendService),
                new FullStackGenerationTemplateBootstrapAdapter(
                        vueService,
                        backendService,
                        new FullStackPortAllocator(generationWorkspaceService)
                )
        );
    }
}
