package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.artifact.GeneratedArtifactLifecycleResolver;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 使用原子发布指针解析当前生成制品，并兼容尚未迁移的遗留工作区。 */
@Component
public class GenerationWorkspaceArtifactResolver implements GeneratedArtifactLifecycleResolver {

    private final GenerationWorkspaceService generationWorkspaceService;
    private final Path outputRoot;

    public GenerationWorkspaceArtifactResolver(GenerationWorkspaceService generationWorkspaceService,
                                               CodeStorageProperties storageProperties) {
        this.generationWorkspaceService = Objects.requireNonNull(
                generationWorkspaceService,
                "generationWorkspaceService must not be null"
        );
        Objects.requireNonNull(storageProperties, "storageProperties must not be null");
        this.outputRoot = storageProperties.outputRoot();
    }

    @Override
    public Path resolveCurrent(Long appId, CodeGenTypeEnum codeGenType) {
        return generationWorkspaceService.resolveCanonical(appId, codeGenType).canonicalRootPath();
    }

    @Override
    public List<Path> deletionRoots(Long appId) {
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("appId must be positive");
        }
        return List.of(
                // 先隔离发布指针，避免删除版本目录期间读请求仍命中即将消失的工作区。
                appRoot(GenerationWorkspacePublicationCatalog.PUBLICATION_ROOT_NAME, appId),
                appRoot(GenerationWorkspacePublicationCatalog.PUBLISHED_ROOT_NAME, appId),
                appRoot(GenerationExecutionWorkspaceService.EXECUTION_ROOT_NAME, appId),
                appRoot(GenerationExecutionWorkspaceService.QUARANTINE_ROOT_NAME, appId)
        );
    }

    /** 只暴露共享根下精确的应用级目录，禁止调用方删除整个生成存储根。 */
    private Path appRoot(String managedRootName, Long appId) {
        Path managedRoot = outputRoot.resolve(managedRootName).normalize();
        Path appRoot = managedRoot.resolve("app-" + appId).normalize();
        if (!managedRoot.startsWith(outputRoot)
                || managedRoot.equals(outputRoot)
                || !appRoot.startsWith(managedRoot)
                || appRoot.getParent() == null
                || !appRoot.getParent().equals(managedRoot)) {
            throw new IllegalStateException("managed generation artifact path escaped its storage root");
        }
        return appRoot;
    }
}
