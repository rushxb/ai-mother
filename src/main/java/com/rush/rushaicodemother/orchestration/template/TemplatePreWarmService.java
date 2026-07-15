package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Stores validated, application-lifetime node_modules caches for known packaged templates. */
@Slf4j
@Service
public class TemplatePreWarmService {

    private static final String NODE_MODULES_DIRECTORY = "node_modules";

    private final ProjectTemplateCatalog templateCatalog;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final ConcurrentMap<String, Path> templateNodeModulesCache = new ConcurrentHashMap<>();

    public TemplatePreWarmService(ProjectTemplateCatalog templateCatalog,
                                  WorkspaceFileSystemService workspaceFileSystemService) {
        this.templateCatalog = Objects.requireNonNull(templateCatalog, "templateCatalog must not be null");
        this.workspaceFileSystemService = Objects.requireNonNull(
                workspaceFileSystemService,
                "workspaceFileSystemService must not be null"
        );
    }

    /** Copies a validated cache into a caller-owned template staging directory. */
    public boolean copyPreWarmedModules(String templateId, Path targetRoot) throws IOException {
        templateCatalog.requireNodeTemplate(templateId);
        Path safeTargetRoot = Objects.requireNonNull(targetRoot, "targetRoot must not be null")
                .toAbsolutePath()
                .normalize();
        if (!workspaceFileSystemService.isDirectory(safeTargetRoot)) {
            throw new IOException("Template staging directory does not exist");
        }

        Path targetNodeModules = safeTargetRoot.resolve(NODE_MODULES_DIRECTORY).normalize();
        if (Files.exists(targetNodeModules, LinkOption.NOFOLLOW_LINKS)) {
            workspaceFileSystemService.isDirectory(targetNodeModules);
            return true;
        }

        Path cachedModules = templateNodeModulesCache.get(templateId);
        if (cachedModules == null) {
            return false;
        }
        try {
            if (!workspaceFileSystemService.isDirectory(cachedModules)) {
                templateNodeModulesCache.remove(templateId, cachedModules);
                return false;
            }
        } catch (IOException exception) {
            templateNodeModulesCache.remove(templateId, cachedModules);
            log.warn(
                    "Discarded unsafe pre-warmed dependency cache: templateId={}, error={}",
                    templateId,
                    LogExceptionSanitizer.sanitizeMessage(exception)
            );
            return false;
        }
        try {
            workspaceFileSystemService.copyDirectory(cachedModules, targetNodeModules);
            log.info("Copied pre-warmed dependencies: templateId={}, target={}", templateId, safeTargetRoot);
            return true;
        } catch (IOException exception) {
            log.warn(
                    "Pre-warmed dependency copy was skipped: templateId={}, error={}",
                    templateId,
                    LogExceptionSanitizer.sanitizeMessage(exception)
            );
            return false;
        }
    }

    /** Registers one safe node_modules directory for a known Node.js template. */
    public void registerPreWarmedModules(String templateId, Path nodeModulesPath) throws IOException {
        templateCatalog.requireNodeTemplate(templateId);
        Path safeNodeModules = Objects.requireNonNull(nodeModulesPath, "nodeModulesPath must not be null")
                .toAbsolutePath()
                .normalize();
        if (safeNodeModules.getFileName() == null
                || !NODE_MODULES_DIRECTORY.equals(safeNodeModules.getFileName().toString())
                || !workspaceFileSystemService.isDirectory(safeNodeModules)) {
            throw new IOException("Pre-warmed dependency cache is not a safe node_modules directory");
        }
        templateNodeModulesCache.put(templateId, safeNodeModules);
        log.info("Registered pre-warmed dependencies: templateId={}", templateId);
    }
}