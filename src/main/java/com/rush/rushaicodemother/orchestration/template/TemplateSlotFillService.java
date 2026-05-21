package com.rush.rushaicodemother.orchestration.template;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.ai.AiSlotFillService;
import com.rush.rushaicodemother.ai.AiSlotFillServiceFactory;
import com.rush.rushaicodemother.ai.model.SlotFillOutput;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 模板 slot 填充服务。
 * <p>
 * 负责协调 AI 填充模板的各个 slot，生成 patch 操作。
 * 支持自动为没有 manifest 的模板生成 slot 定义。
 */
@Slf4j
@Service
public class TemplateSlotFillService {

    private static final int MAX_TEMPLATE_CONTEXT_CHARS = 30000;
    private static final String TEMPLATE_ROOT = "project-templates";

    private final TemplateManifestService templateManifestService;
    private final AiSlotFillServiceFactory aiSlotFillServiceFactory;
    private final PathMatchingResourcePatternResolver resourceResolver;
    private final Map<String, String> templateContentCache = new ConcurrentHashMap<>();

    public TemplateSlotFillService(TemplateManifestService templateManifestService,
                                    AiSlotFillServiceFactory aiSlotFillServiceFactory) {
        this(templateManifestService, aiSlotFillServiceFactory, new PathMatchingResourcePatternResolver());
    }

    public TemplateSlotFillService(TemplateManifestService templateManifestService,
                                    AiSlotFillServiceFactory aiSlotFillServiceFactory,
                                    PathMatchingResourcePatternResolver resourceResolver) {
        this.templateManifestService = templateManifestService;
        this.aiSlotFillServiceFactory = aiSlotFillServiceFactory;
        this.resourceResolver = resourceResolver;
    }

    /**
     * 执行 slot 填充。
     *
     * @param templateId  模板 ID
     * @param appId       应用 ID
     * @param userMessage 用户需求
     * @return 填充结果，如果无法填充则返回 null
     */
    public SlotFillResult fillSlots(String templateId, Long appId, String userMessage) {
        if (StrUtil.isBlank(templateId) || appId == null || StrUtil.isBlank(userMessage)) {
            return null;
        }

        // 1. 获取模板 manifest（支持自动生成）
        TemplateManifestService.TemplateManifest manifest = getOrGenerateManifest(templateId);
        if (manifest == null || manifest.slots() == null || manifest.slots().isEmpty()) {
            log.debug("模板没有 slot 定义且无法自动生成: {}", templateId);
            return null;
        }

        // 2. 构建 slot 定义 JSON
        String slotDefinition = buildSlotDefinition(manifest);

        // 3. 读取模板上下文（从 classpath 读取模板源文件）
        String templateContext = buildTemplateContextFromResources(templateId, manifest);

        // 4. 调用 AI 填充 slots
        SlotFillOutput aiOutput;
        try {
            AiSlotFillService aiService = aiSlotFillServiceFactory.createAiSlotFillService();
            aiOutput = aiService.fillSlots(userMessage, slotDefinition, templateContext);
        } catch (Exception e) {
            log.warn("AI slot 填充失败: {}", e.getMessage());
            return null;
        }

        if (aiOutput == null || aiOutput.slots() == null || aiOutput.slots().isEmpty()) {
            log.warn("AI 未返回有效的 slot 填充内容");
            return null;
        }

        // 5. 转换为 PatchOperation
        List<PatchOperation> patchOperations = convertToPatchOperations(aiOutput.slots(), manifest);

        // 6. 统计信息
        List<String> filledSlots = aiOutput.slots().stream()
                .map(SlotFillOutput.SlotContent::slotId)
                .collect(Collectors.toList());

        List<String> skippedSlots = manifest.slots().stream()
                .map(TemplateManifestService.TemplateSlot::id)
                .filter(id -> !filledSlots.contains(id))
                .collect(Collectors.toList());

        int totalChars = aiOutput.slots().stream()
                .mapToInt(slot -> slot.content() != null ? slot.content().length() : 0)
                .sum();

        log.info("Slot 填充完成: templateId={}, filledSlots={}, skippedSlots={}, totalChars={}",
                templateId, filledSlots.size(), skippedSlots.size(), totalChars);

        return SlotFillResult.partial(templateId, filledSlots, patchOperations,
                aiOutput.summary(), totalChars, skippedSlots);
    }

    /**
     * 检查模板是否支持 slot 填充。
     *
     * @param templateId 模板 ID
     * @return 是否支持
     */
    public boolean supportsSlotFill(String templateId) {
        return getOrGenerateManifest(templateId) != null;
    }

    /**
     * 获取或自动生成模板 manifest。
     * <p>
     * 如果模板没有 manifest，尝试根据模板文件结构自动生成。
     */
    private TemplateManifestService.TemplateManifest getOrGenerateManifest(String templateId) {
        // 先尝试获取已有的 manifest
        TemplateManifestService.TemplateManifest manifest = templateManifestService.getManifest(templateId);
        if (manifest != null) {
            return manifest;
        }

        // 自动生成 slot 定义
        return generateManifestFromTemplate(templateId);
    }

    /**
     * 根据模板文件结构自动生成 manifest。
     * <p>
     * 扫描模板的 src/views 和 src/data 目录，自动识别可填充的文件。
     */
    private TemplateManifestService.TemplateManifest generateManifestFromTemplate(String templateId) {
        try {
            List<TemplateManifestService.TemplateSlot> slots = new ArrayList<>();

            // 扫描 views 目录
            scanDirectoryForSlots(templateId, "src/views", "视图", slots);

            // 扫描 data 目录
            scanDirectoryForSlots(templateId, "src/data", "数据", slots);

            // 扫描 styles 目录
            scanDirectoryForSlots(templateId, "src/styles", "样式", slots);

            if (slots.isEmpty()) {
                return null;
            }

            TemplateManifestService.TemplateManifest generatedManifest = new TemplateManifestService.TemplateManifest(
                    templateId,
                    "自动生成的 slot 定义",
                    slots,
                    List.of("package.json", "vite.config.js", "index.html", "src/main.js", "src/App.vue", "src/router/index.js"),
                    slots.size()
            );

            log.info("为模板 {} 自动生成了 {} 个 slot 定义", templateId, slots.size());
            return generatedManifest;
        } catch (Exception e) {
            log.debug("为模板 {} 自动生成 manifest 失败: {}", templateId, e.getMessage());
            return null;
        }
    }

    /**
     * 扫描目录中的可填充文件。
     */
    private void scanDirectoryForSlots(String templateId, String dirPath, String category,
                                        List<TemplateManifestService.TemplateSlot> slots) {
        try {
            String resourcePath = TEMPLATE_ROOT + "/" + templateId + "/" + dirPath;
            Resource[] resources = resourceResolver.getResources("classpath:" + resourcePath + "/*");

            for (Resource resource : resources) {
                if (!resource.exists() || !resource.isReadable()) {
                    continue;
                }

                String filename = resource.getFilename();
                if (filename == null || filename.startsWith(".")) {
                    continue;
                }

                // 只处理 Vue、JS、TS、CSS 文件
                if (!isSlotCandidate(filename)) {
                    continue;
                }

                String slotId = dirPath.replace("/", "_") + "_" + filename.replace(".", "_");
                String description = category + "文件: " + filename;
                String type = inferSlotType(filename);

                slots.add(new TemplateManifestService.TemplateSlot(
                        slotId,
                        dirPath + "/" + filename,
                        description,
                        false,
                        type,
                        null,  // example - 自动生成的不提供示例
                        null,  // dependencies - 自动生成的不提供依赖
                        null,  // styleVariables - 自动生成的不提供样式变量
                        null   // outputFormat - 自动生成的不提供输出格式
                ));
            }
        } catch (Exception e) {
            log.debug("扫描目录 {} 失败: {}", dirPath, e.getMessage());
        }
    }

    /**
     * 根据文件名推断 slot 类型。
     */
    private String inferSlotType(String filename) {
        if (StrUtil.isBlank(filename)) {
            return "auto";
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".vue")) {
            return "view";
        } else if (lower.endsWith(".ts") || lower.endsWith(".js")) {
            return "data";
        } else if (lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".less")) {
            return "style";
        }
        return "auto";
    }

    /**
     * 判断文件是否可以作为 slot 候选。
     */
    private boolean isSlotCandidate(String filename) {
        if (StrUtil.isBlank(filename)) {
            return false;
        }
        String lower = filename.toLowerCase();
        return lower.endsWith(".vue")
                || lower.endsWith(".ts")
                || lower.endsWith(".js")
                || lower.endsWith(".css")
                || lower.endsWith(".scss")
                || lower.endsWith(".less");
    }

    private String buildSlotDefinition(TemplateManifestService.TemplateManifest manifest) {
        List<Map<String, Object>> slots = manifest.slots().stream()
                .map(slot -> {
                    Map<String, Object> slotMap = new java.util.HashMap<>();
                    slotMap.put("id", slot.id());
                    slotMap.put("file", slot.file());
                    slotMap.put("description", slot.description());
                    slotMap.put("required", slot.isRequired());
                    slotMap.put("type", slot.getType());

                    if (slot.example() != null && !slot.example().isEmpty()) {
                        slotMap.put("example", slot.example());
                    }
                    if (slot.dependencies() != null && !slot.dependencies().isEmpty()) {
                        slotMap.put("dependencies", slot.dependencies());
                    }
                    if (slot.styleVariables() != null && !slot.styleVariables().isEmpty()) {
                        slotMap.put("styleVariables", slot.styleVariables());
                    }
                    if (slot.getOutputFormat() != null && !slot.getOutputFormat().isEmpty()) {
                        slotMap.put("outputFormat", slot.getOutputFormat());
                    }

                    return slotMap;
                })
                .collect(Collectors.toList());

        return JSONUtil.toJsonPrettyStr(Map.of(
                "templateId", manifest.templateId(),
                "description", manifest.description(),
                "slots", slots
        ));
    }

    /**
     * 从 classpath 资源读取模板文件内容。
     */
    private String buildTemplateContextFromResources(String templateId,
                                                      TemplateManifestService.TemplateManifest manifest) {
        StringBuilder context = new StringBuilder();
        int totalChars = 0;

        for (TemplateManifestService.TemplateSlot slot : manifest.slots()) {
            if (totalChars >= MAX_TEMPLATE_CONTEXT_CHARS) {
                context.append("\n... (更多文件已省略)\n");
                break;
            }

            String filePath = slot.file();
            String content = readTemplateFileFromResources(templateId, filePath);

            if (StrUtil.isNotBlank(content)) {
                context.append("## 文件: ").append(filePath).append("\n");
                context.append("```").append(getFileExtension(filePath)).append("\n");
                context.append(truncateContent(content, 5000));
                context.append("\n```\n\n");
                totalChars += content.length();
            }
        }

        return context.toString();
    }

    /**
     * 从 classpath 读取模板文件。
     */
    private String readTemplateFileFromResources(String templateId, String relativePath) {
        String cacheKey = templateId + "/" + relativePath;
        return templateContentCache.computeIfAbsent(cacheKey, key -> {
            try {
                String resourcePath = TEMPLATE_ROOT + "/" + templateId + "/" + relativePath;
                Resource resource = resourceResolver.getResource("classpath:" + resourcePath);
                if (resource.exists() && resource.isReadable()) {
                    try (InputStream is = resource.getInputStream()) {
                        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            } catch (Exception e) {
                log.debug("读取模板文件失败: {}/{}", templateId, relativePath);
            }
            return null;
        });
    }

    private String getFileExtension(String filePath) {
        if (StrUtil.isBlank(filePath)) {
            return "";
        }
        int lastDot = filePath.lastIndexOf('.');
        return lastDot >= 0 ? filePath.substring(lastDot + 1) : "";
    }

    private String truncateContent(String content, int maxLength) {
        if (content == null || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "\n... (内容已截断)";
    }

    private List<PatchOperation> convertToPatchOperations(List<SlotFillOutput.SlotContent> slots,
                                                           TemplateManifestService.TemplateManifest manifest) {
        // 构建 slotId -> file 映射
        Map<String, String> slotFileMap = manifest.slots().stream()
                .collect(Collectors.toMap(
                        TemplateManifestService.TemplateSlot::id,
                        TemplateManifestService.TemplateSlot::file
                ));

        List<PatchOperation> operations = new ArrayList<>();

        for (SlotFillOutput.SlotContent slot : slots) {
            String filePath = slotFileMap.get(slot.slotId());
            if (StrUtil.isBlank(filePath)) {
                log.warn("未知的 slotId: {}", slot.slotId());
                continue;
            }

            if (StrUtil.isBlank(slot.content())) {
                log.debug("Slot 内容为空: {}", slot.slotId());
                continue;
            }

            // 使用 modify 操作，因为模板文件已经存在
            operations.add(PatchOperation.modify(filePath, slot.content()));
        }

        return operations;
    }
}
