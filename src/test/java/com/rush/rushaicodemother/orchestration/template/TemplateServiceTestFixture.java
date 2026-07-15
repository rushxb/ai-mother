package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.config.TemplateMaterializationProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.file.Path;

public final class TemplateServiceTestFixture {

    final ProjectTemplateCatalog templateCatalog;
    final WorkspaceFileSystemService workspaceFileSystemService;
    public final GenerationWorkspaceService generationWorkspaceService;
    final TemplateMaterializationProperties materializationProperties;
    final ProjectTemplateMaterializer templateMaterializer;
    final TemplatePreWarmService templatePreWarmService;
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
        templatePreWarmService = new TemplatePreWarmService(templateCatalog, workspaceFileSystemService);
        templateBootstrapper = new ProjectTemplateBootstrapper(
                templateMaterializer,
                templatePreWarmService,
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
}