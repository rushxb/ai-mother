package com.rush.rushaicodemother.orchestration.template;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模板 manifest 服务。
 * <p>
 * 负责读取和解析模板的 template-manifest.json 文件，获取模板的 slots 定义。
 */
@Slf4j
@Service
public class TemplateManifestService {

    private static final String MANIFEST_FILENAME = "template-manifest.json";
    private static final String TEMPLATE_ROOT = "project-templates";

    private final PathMatchingResourcePatternResolver resourceResolver;
    private final Map<String, TemplateManifest> manifestCache = new ConcurrentHashMap<>();

    public TemplateManifestService() {
        this(new PathMatchingResourcePatternResolver());
    }

    public TemplateManifestService(PathMatchingResourcePatternResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
    }

    /**
     * 获取模板的 manifest 信息。
     *
     * @param templateId 模板 ID（如 vue-web-basic）
     * @return 模板 manifest，如果不存在则返回 null
     */
    public TemplateManifest getManifest(String templateId) {
        if (StrUtil.isBlank(templateId)) {
            return null;
        }
        return manifestCache.computeIfAbsent(templateId, this::loadManifest);
    }

    /**
     * 获取模板的 slots 列表。
     *
     * @param templateId 模板 ID
     * @return slots 列表，如果模板不存在则返回空列表
     */
    public List<TemplateSlot> getSlots(String templateId) {
        TemplateManifest manifest = getManifest(templateId);
        if (manifest == null || manifest.slots() == null) {
            return Collections.emptyList();
        }
        return manifest.slots();
    }

    /**
     * 获取模板的受保护文件列表。
     *
     * @param templateId 模板 ID
     * @return 受保护文件列表，如果模板不存在则返回空列表
     */
    public List<String> getProtectedFiles(String templateId) {
        TemplateManifest manifest = getManifest(templateId);
        if (manifest == null || manifest.protectedFiles() == null) {
            return Collections.emptyList();
        }
        return manifest.protectedFiles();
    }

    /**
     * 检查模板是否存在 manifest。
     *
     * @param templateId 模板 ID
     * @return 是否存在
     */
    public boolean hasManifest(String templateId) {
        return getManifest(templateId) != null;
    }

    private TemplateManifest loadManifest(String templateId) {
        String manifestPath = TEMPLATE_ROOT + "/" + templateId + "/" + MANIFEST_FILENAME;
        try {
            Resource resource = resourceResolver.getResource("classpath:" + manifestPath);
            if (!resource.exists() || !resource.isReadable()) {
                log.debug("模板 manifest 不存在: {}", manifestPath);
                return null;
            }
            try (InputStream inputStream = resource.getInputStream()) {
                String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                TemplateManifest manifest = JSONUtil.toBean(content, TemplateManifest.class);
                log.info("已加载模板 manifest: {}, slots 数量: {}", templateId,
                        manifest.slots() != null ? manifest.slots().size() : 0);
                return manifest;
            }
        } catch (IOException e) {
            log.warn("读取模板 manifest 失败: {}", manifestPath, e);
            return null;
        }
    }

    /**
     * 模板 manifest 记录。
     */
    public record TemplateManifest(
            String templateId,
            String description,
            List<TemplateSlot> slots,
            List<String> protectedFiles,
            Integer maxSlotsPerGeneration
    ) {
    }

    /**
     * 模板 slot 定义。
     */
    public record TemplateSlot(
            String id,
            String file,
            String description,
            Boolean required,
            String type,
            String example,
            List<String> dependencies,
            List<String> styleVariables,
            String outputFormat
    ) {
        public boolean isRequired() {
            return required != null && required;
        }

        /**
         * 获取 slot 类型，默认为 auto。
         */
        public String getType() {
            return type != null ? type : "auto";
        }

        /**
         * 获取输出格式说明。
         */
        public String getOutputFormat() {
            return outputFormat != null ? outputFormat : "";
        }
    }
}
