package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Publishes one complete template workspace through the shared atomic materialization boundary. */
@Component
public class ProjectTemplateBootstrapper {

    private static final int TARGET_LOCK_STRIPES = 64;
    private static final ReentrantLock[] TARGET_LOCKS = createTargetLocks();

    private final ProjectTemplateMaterializer templateMaterializer;
    private final TemplatePreWarmService templatePreWarmService;
    private final WorkspaceFileSystemService workspaceFileSystemService;

    public ProjectTemplateBootstrapper(ProjectTemplateMaterializer templateMaterializer,
                                       TemplatePreWarmService templatePreWarmService,
                                       WorkspaceFileSystemService workspaceFileSystemService) {
        this.templateMaterializer = Objects.requireNonNull(templateMaterializer, "templateMaterializer must not be null");
        this.templatePreWarmService = Objects.requireNonNull(templatePreWarmService, "templatePreWarmService must not be null");
        this.workspaceFileSystemService = Objects.requireNonNull(
                workspaceFileSystemService,
                "workspaceFileSystemService must not be null"
        );
    }

    public BootstrapOutcome bootstrap(String templateId, Path targetDirectory, boolean copyPreWarmedModules)
            throws Exception {
        Path target = normalizeTarget(targetDirectory);
        ReentrantLock targetLock = targetLockFor(target);
        targetLock.lock();
        try {
            return bootstrapUnderLock(templateId, target, copyPreWarmedModules);
        } finally {
            targetLock.unlock();
        }
    }

    private BootstrapOutcome bootstrapUnderLock(String templateId, Path target, boolean copyPreWarmedModules)
            throws Exception {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            workspaceFileSystemService.isDirectory(target);
            return BootstrapOutcome.skipped(target, "workspace_exists");
        }
        try {
            ProjectTemplateMaterializer.MaterializationResult result = templateMaterializer.materializeAtomically(
                    templateId,
                    target,
                    staging -> {
                        if (copyPreWarmedModules) {
                            templatePreWarmService.copyPreWarmedModules(templateId, staging);
                        }
                    }
            );
            return BootstrapOutcome.created(result.targetDirectory(), result.fileCount());
        } catch (TemplateMaterializationException exception) {
            if (exception.reason() != TemplateMaterializationException.Reason.TARGET_ALREADY_EXISTS) {
                throw exception;
            }
            workspaceFileSystemService.isDirectory(target);
            return BootstrapOutcome.skipped(target, "workspace_exists");
        }
    }

    private Path normalizeTarget(Path targetDirectory) throws TemplateMaterializationException {
        if (targetDirectory == null || targetDirectory.toString().isBlank()) {
            throw new TemplateMaterializationException(
                    TemplateMaterializationException.Reason.UNSAFE_TARGET,
                    "Template target is required"
            );
        }
        return targetDirectory.toAbsolutePath().normalize();
    }

    private ReentrantLock targetLockFor(Path target) {
        int index = Math.floorMod(target.toString().hashCode(), TARGET_LOCKS.length);
        return TARGET_LOCKS[index];
    }

    private static ReentrantLock[] createTargetLocks() {
        ReentrantLock[] locks = new ReentrantLock[TARGET_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    public record BootstrapOutcome(boolean bootstrapped, Path projectPath, int fileCount, String reason) {

        static BootstrapOutcome created(Path projectPath, int fileCount) {
            return new BootstrapOutcome(true, projectPath, fileCount, "");
        }

        static BootstrapOutcome skipped(Path projectPath, String reason) {
            return new BootstrapOutcome(false, projectPath, 0, reason);
        }
    }
}
