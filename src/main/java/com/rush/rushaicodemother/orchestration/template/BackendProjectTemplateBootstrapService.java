package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class BackendProjectTemplateBootstrapService {

    private static final String TEMPLATE_ROOT = "project-templates";
    private static final String TEMPLATE_ID = "go-sqlite-backend-basic";

    private final Path codeOutputRoot;
    private final PathMatchingResourcePatternResolver resourceResolver;

    public BackendProjectTemplateBootstrapService() {
        this(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR), new PathMatchingResourcePatternResolver());
    }

    public BackendProjectTemplateBootstrapService(Path codeOutputRoot,
                                                  PathMatchingResourcePatternResolver resourceResolver) {
        this.codeOutputRoot = codeOutputRoot.toAbsolutePath().normalize();
        this.resourceResolver = resourceResolver;
    }

    public BootstrapResult bootstrapIfNecessary(Long appId) {
        if (appId == null || appId <= 0) {
            return BootstrapResult.skipped("", "", "invalid_app_id");
        }
        return bootstrapIfNecessary(resolveProjectRoot(appId));
    }

    public BootstrapResult bootstrapIfNecessary(Path targetRoot) {
        if (targetRoot == null) {
            return BootstrapResult.skipped("", "", "invalid_target_root");
        }
        targetRoot = targetRoot.toAbsolutePath().normalize();
        ensureChildOf(codeOutputRoot, targetRoot);
        if (Files.exists(targetRoot)) {
            return BootstrapResult.skipped("", targetRoot.toString(), "workspace_exists");
        }
        boolean workspaceCreated = false;
        try {
            Files.createDirectories(targetRoot.getParent());
            Files.createDirectory(targetRoot);
            workspaceCreated = true;
            int fileCount = copyTemplate(targetRoot);
            BootstrapResult result = BootstrapResult.created(TEMPLATE_ID, targetRoot.toString(), fileCount);
            log.info("已复制后端项目模板，targetRoot: {}, templateId: {}, fileCount: {}", targetRoot, TEMPLATE_ID, fileCount);
            return result;
        } catch (Exception e) {
            if (workspaceCreated) {
                TemplateWorkspaceFailureCleanup.deleteOwnedWorkspace(targetRoot, e);
            }
            log.warn("复制后端项目模板失败，targetRoot: {}, templateId: {}", targetRoot, TEMPLATE_ID, LogExceptionSanitizer.sanitize(e));
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "初始化后端项目模板失败，请稍后重试",
                    e
            );
        }
    }

    public Path resolveProjectRoot(Long appId) {
        String projectDirName = "backend_project_" + appId;
        Path targetRoot = codeOutputRoot.resolve(projectDirName).toAbsolutePath().normalize();
        ensureChildOf(codeOutputRoot, targetRoot);
        return targetRoot;
    }

    private int copyTemplate(Path targetRoot) throws IOException {
        String templatePrefix = TEMPLATE_ROOT + "/" + TEMPLATE_ID + "/";
        Resource[] resources = resourceResolver.getResources("classpath:" + templatePrefix + "**/*");
        int copiedFiles = 0;
        for (Resource resource : resources) {
            if (!resource.exists() || !resource.isReadable()) {
                continue;
            }
            String relativePath = resolveRelativePath(resource, templatePrefix);
            if (StrUtil.isBlank(relativePath) || relativePath.endsWith("/")) {
                continue;
            }
            Path targetPath = targetRoot.resolve(relativePath).toAbsolutePath().normalize();
            ensureChildOf(targetRoot, targetPath);
            Files.createDirectories(targetPath.getParent());
            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            copiedFiles++;
        }
        if (copiedFiles == 0) {
            throw new IllegalStateException("模板目录为空或无法读取：" + TEMPLATE_ID);
        }
        return copiedFiles;
    }

    private String resolveRelativePath(Resource resource, String templatePrefix) throws IOException {
        String url = URLDecoder.decode(resource.getURL().toString(), StandardCharsets.UTF_8);
        String normalizedUrl = url.replace("\\", "/");
        int index = normalizedUrl.indexOf(templatePrefix);
        if (index < 0) {
            return "";
        }
        return normalizedUrl.substring(index + templatePrefix.length());
    }

    private void ensureChildOf(Path root, Path child) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (!normalizedChild.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("非法路径，超出当前项目目录范围");
        }
    }

    public record BootstrapResult(
            boolean bootstrapped,
            String templateId,
            String projectPath,
            int fileCount,
            String reason
    ) {

        public static BootstrapResult created(String templateId, String projectPath, int fileCount) {
            return new BootstrapResult(true, templateId, projectPath, fileCount, "");
        }

        public static BootstrapResult skipped(String templateId, String projectPath, String reason) {
            return new BootstrapResult(false, templateId, projectPath, 0, reason);
        }

        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("bootstrapped", bootstrapped);
            payload.put("templateId", templateId);
            payload.put("projectPath", projectPath);
            payload.put("fileCount", fileCount);
            payload.put("reason", reason);
            return payload;
        }
    }
}
