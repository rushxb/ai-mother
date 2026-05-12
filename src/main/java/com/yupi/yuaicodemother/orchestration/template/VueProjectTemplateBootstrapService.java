package com.yupi.yuaicodemother.orchestration.template;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Vue 工程模板引导服务。
 */
@Slf4j
@Component
public class VueProjectTemplateBootstrapService {

    private static final String TEMPLATE_ROOT = "project-templates";
    private static final String DEFAULT_TEMPLATE_ID = "vue-web-basic";
    private static final List<String> TEMPLATE_IDS = List.of(
            "vue-web-basic",
            "vue-web-admin",
            "vue-web-mobile",
            "vue-web-landing"
    );

    private final Path codeOutputRoot;
    private final PathMatchingResourcePatternResolver resourceResolver;

    public VueProjectTemplateBootstrapService() {
        this(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR), new PathMatchingResourcePatternResolver());
    }

    public VueProjectTemplateBootstrapService(Path codeOutputRoot,
                                              PathMatchingResourcePatternResolver resourceResolver) {
        this.codeOutputRoot = codeOutputRoot.toAbsolutePath().normalize();
        this.resourceResolver = resourceResolver;
    }

    public BootstrapResult bootstrapIfNecessary(Long appId, String userMessage) {
        if (appId == null || appId <= 0) {
            return BootstrapResult.skipped("", "", "invalid_app_id");
        }
        return bootstrapIfNecessary(resolveProjectRoot(appId), userMessage);
    }

    public BootstrapResult bootstrapIfNecessary(Path targetRoot, String userMessage) {
        if (targetRoot == null) {
            return BootstrapResult.skipped("", "", "invalid_target_root");
        }
        targetRoot = targetRoot.toAbsolutePath().normalize();
        ensureChildOf(codeOutputRoot, targetRoot);
        if (Files.exists(targetRoot)) {
            return BootstrapResult.skipped("", targetRoot.toString(), "workspace_exists");
        }
        String templateId = selectTemplateId(userMessage);
        try {
            Files.createDirectories(targetRoot);
            int fileCount = copyTemplate(templateId, targetRoot);
            BootstrapResult result = BootstrapResult.created(templateId, targetRoot.toString(), fileCount);
            log.info("已复制 Vue 项目模板，targetRoot: {}, templateId: {}, fileCount: {}", targetRoot, templateId, fileCount);
            return result;
        } catch (Exception e) {
            log.warn("复制 Vue 项目模板失败，targetRoot: {}, templateId: {}", targetRoot, templateId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "初始化 Vue 项目模板失败：" + e.getMessage());
        }
    }

    public Path resolveProjectRoot(Long appId) {
        String projectDirName = "vue_project_" + appId;
        Path targetRoot = codeOutputRoot.resolve(projectDirName).toAbsolutePath().normalize();
        ensureChildOf(codeOutputRoot, targetRoot);
        return targetRoot;
    }

    String selectTemplateId(String userMessage) {
        String normalized = StrUtil.blankToDefault(userMessage, "").toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "移动端", "手机", "h5", "mobile", "商城", "会员", "预约", "vant")) {
            return "vue-web-mobile";
        }
        if (containsAny(normalized, "后台", "管理", "admin", "dashboard", "仪表盘", "工作台", "表格", "crud")) {
            return "vue-web-admin";
        }
        if (containsAny(normalized, "官网", "落地页", "landing", "活动页", "营销", "展示", "产品介绍", "宣传")) {
            return "vue-web-landing";
        }
        return DEFAULT_TEMPLATE_ID;
    }

    private int copyTemplate(String templateId, Path targetRoot) throws IOException {
        if (!TEMPLATE_IDS.contains(templateId)) {
            throw new IllegalArgumentException("未知 Vue 项目模板：" + templateId);
        }
        String templatePrefix = TEMPLATE_ROOT + "/" + templateId + "/";
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
            throw new IllegalStateException("模板目录为空或无法读取：" + templateId);
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

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
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
