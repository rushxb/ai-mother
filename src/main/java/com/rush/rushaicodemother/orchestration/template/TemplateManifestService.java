package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    public ManifestValidationResult validateManifest(String templateId) {
        TemplateManifest manifest = getManifest(templateId);
        if (manifest == null) {
            return ManifestValidationResult.invalid(List.of("manifest_missing"), List.of());
        }
        return validateManifest(templateId, manifest);
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
                TemplateManifest manifest = parseManifest(content);
                ManifestValidationResult validationResult = validateManifest(templateId, manifest);
                if (!validationResult.valid()) {
                    log.warn("模板 manifest 校验失败: templateId={}, errors={}, warnings={}",
                            templateId, validationResult.errors(), validationResult.warnings());
                } else if (!validationResult.warnings().isEmpty()) {
                    log.info("模板 manifest 校验通过但存在提醒: templateId={}, warnings={}",
                            templateId, validationResult.warnings());
                }
                log.info("已加载模板 manifest: {}, slots 数量: {}", templateId,
                        manifest.slots() != null ? manifest.slots().size() : 0);
                return manifest;
            }
        } catch (IOException e) {
            log.warn("读取模板 manifest 失败: {}", manifestPath, LogExceptionSanitizer.sanitize(e));
            return null;
        } catch (Exception e) {
            log.warn("解析模板 manifest 失败: {}", manifestPath, LogExceptionSanitizer.sanitize(e));
            return null;
        }
    }

    private TemplateManifest parseManifest(String content) {
        JSONObject json = JSONUtil.parseObj(content);
        return new TemplateManifest(
                json.getStr("templateId"),
                json.getStr("description"),
                parseSlots(json.getJSONArray("slots")),
                parseStringList(json.getJSONArray("protectedFiles")),
                json.getInt("maxSlotsPerGeneration")
        );
    }

    private List<TemplateSlot> parseSlots(JSONArray slotsArray) {
        if (slotsArray == null || slotsArray.isEmpty()) {
            return Collections.emptyList();
        }
        List<TemplateSlot> slots = new ArrayList<>();
        for (Object item : slotsArray) {
            if (!(item instanceof JSONObject slotJson)) {
                slots.add(null);
                continue;
            }
            slots.add(new TemplateSlot(
                    slotJson.getStr("id"),
                    slotJson.getStr("file"),
                    slotJson.getStr("description"),
                    slotJson.getBool("required"),
                    slotJson.getStr("type"),
                    slotJson.getStr("category"),
                    slotJson.getStr("example"),
                    parseStringList(slotJson.getJSONArray("dependencies")),
                    parseStringList(slotJson.getJSONArray("styleVariables")),
                    slotJson.getStr("outputFormat"),
                    slotJson.getStr("marker"),
                    slotJson.getStr("patchMode")
            ));
        }
        return slots;
    }

    private List<String> parseStringList(JSONArray array) {
        if (array == null) {
            return null;
        }
        if (array.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        for (Object item : array) {
            if (item != null) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }

    private ManifestValidationResult validateManifest(String templateId, TemplateManifest manifest) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (manifest == null) {
            errors.add("manifest_null");
            return ManifestValidationResult.invalid(errors, warnings);
        }
        if (StrUtil.isBlank(manifest.templateId())) {
            errors.add("templateId_missing");
        } else if (!templateId.equals(manifest.templateId())) {
            errors.add("templateId_mismatch:" + manifest.templateId());
        }
        if (manifest.slots() == null || manifest.slots().isEmpty()) {
            errors.add("slots_empty");
        }
        Set<String> slotIds = new HashSet<>();
        Set<String> slotFiles = new HashSet<>();
        if (manifest.slots() != null) {
            for (TemplateSlot slot : manifest.slots()) {
                if (slot == null) {
                    errors.add("slot_null");
                    continue;
                }
                if (StrUtil.isBlank(slot.id())) {
                    errors.add("slot_id_missing");
                } else if (!slotIds.add(slot.id())) {
                    errors.add("slot_id_duplicate:" + slot.id());
                }
                if (StrUtil.isBlank(slot.file())) {
                    errors.add("slot_file_missing:" + slot.id());
                    continue;
                }
                slotFiles.add(slot.file());
                Resource slotResource = resourceResolver.getResource("classpath:" + TEMPLATE_ROOT + "/" + templateId + "/" + slot.file());
                if (!slotResource.exists() || !slotResource.isReadable()) {
                    errors.add("slot_file_missing:" + slot.id() + ":" + slot.file());
                    continue;
                }
                if (StrUtil.isNotBlank(slot.marker())) {
                    validateSlotMarker(templateId, slot, errors);
                }
            }
        }
        if (manifest.protectedFiles() != null) {
            for (String protectedFile : manifest.protectedFiles()) {
                if (slotFiles.contains(protectedFile)) {
                    warnings.add("protected_file_also_slot:" + protectedFile);
                }
            }
        }
        if (manifest.maxSlotsPerGeneration() != null
                && manifest.slots() != null
                && manifest.maxSlotsPerGeneration() > manifest.slots().size()) {
            warnings.add("maxSlotsPerGeneration_gt_slot_count");
        }
        return new ManifestValidationResult(errors.isEmpty(), errors, warnings);
    }

    private void validateSlotMarker(String templateId, TemplateSlot slot, List<String> errors) {
        try {
            Resource slotResource = resourceResolver.getResource("classpath:" + TEMPLATE_ROOT + "/" + templateId + "/" + slot.file());
            try (InputStream inputStream = slotResource.getInputStream()) {
                String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                if (!content.contains(slot.marker())) {
                    errors.add("slot_marker_missing:" + slot.id() + ":" + slot.marker());
                }
            }
        } catch (IOException e) {
            errors.add("slot_marker_read_failed:" + slot.id());
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
            String category,
            String example,
            List<String> dependencies,
            List<String> styleVariables,
            String outputFormat,
            String marker,
            String patchMode
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

        public String getPatchMode() {
            return patchMode != null ? patchMode : "whole_file";
        }
    }

    public record ManifestValidationResult(
            boolean valid,
            List<String> errors,
            List<String> warnings
    ) {
        public static ManifestValidationResult invalid(List<String> errors, List<String> warnings) {
            return new ManifestValidationResult(false,
                    errors == null ? List.of() : errors,
                    warnings == null ? List.of() : warnings);
        }
    }
}
