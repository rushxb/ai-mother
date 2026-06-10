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

import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;
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
    private static final String BACKEND_TEMPLATE = "go-sqlite-backend-basic";
    private static final String BACKEND_MODULE_IMPORT_PREFIX = "backend-template/internal/modules/";

    private final TemplateManifestService templateManifestService;
    private final AiSlotFillServiceFactory aiSlotFillServiceFactory;
    private final PathMatchingResourcePatternResolver resourceResolver;
    private final Map<String, String> templateContentCache = new ConcurrentHashMap<>();
    private final ThreadLocal<String> lastFailureReason = new ThreadLocal<>();

    @Autowired
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
        return fillSlots(templateId, appId, userMessage, null);
    }

    public SlotFillResult fillSlots(String templateId, Long appId, String userMessage, List<String> requestedSlotIds) {
        lastFailureReason.remove();
        if (StrUtil.isBlank(templateId) || appId == null || StrUtil.isBlank(userMessage)) {
            lastFailureReason.set("invalid_slot_fill_request");
            return null;
        }

        // 1. 获取模板 manifest（支持自动生成）
        TemplateManifestService.TemplateManifest manifest = getOrGenerateManifest(templateId);
        if (manifest == null || manifest.slots() == null || manifest.slots().isEmpty()) {
            log.debug("模板没有 slot 定义且无法自动生成: {}", templateId);
            lastFailureReason.set("template_manifest_slots_unavailable:" + templateId);
            return null;
        }

        // 2. 构建 slot 定义 JSON
        String moduleName = inferBackendModuleName(templateId, userMessage);
        TemplateManifestService.TemplateManifest scopedManifest = filterManifest(manifest, requestedSlotIds);
        if (scopedManifest.slots().isEmpty()) {
            log.debug("模板 slot group 为空: templateId={}, requestedSlotIds={}", templateId, requestedSlotIds);
            lastFailureReason.set("requested_slot_group_empty:" + templateId + ":" + requestedSlotIds);
            return null;
        }

        String slotDefinition = buildSlotDefinition(scopedManifest, moduleName);

        // 3. 读取模板上下文（从 classpath 读取模板源文件）
        String templateContext = buildTemplateContextFromResources(templateId, scopedManifest);

        // 4. 调用 AI 填充 slots
        SlotFillOutput aiOutput;
        try {
            AiSlotFillService aiService = aiSlotFillServiceFactory.createAiSlotFillService();
            aiOutput = aiService.fillSlots(userMessage, slotDefinition, templateContext);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            lastFailureReason.set("ai_slot_fill_exception:" + reason);
            log.warn("AI slot 填充失败: {}", reason);
            return null;
        }

        if (aiOutput == null || aiOutput.slots() == null || aiOutput.slots().isEmpty()) {
            log.warn("AI 未返回有效的 slot 填充内容");
            lastFailureReason.set("ai_slot_fill_empty_output");
            return null;
        }

        // 5. 转换为 PatchOperation
        List<PatchOperation> patchOperations = convertToPatchOperations(aiOutput.slots(), scopedManifest, moduleName);

        // 6. 统计信息
        List<String> filledSlots = new ArrayList<>(aiOutput.slots().stream()
                .map(SlotFillOutput.SlotContent::slotId)
                .toList());
        deterministicFilledSlotIds(scopedManifest, moduleName).stream()
                .filter(slotId -> !filledSlots.contains(slotId))
                .forEach(filledSlots::add);

        List<String> skippedSlots = scopedManifest.slots().stream()
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

    public String consumeLastFailureReason() {
        String reason = lastFailureReason.get();
        lastFailureReason.remove();
        return StrUtil.blankToDefault(reason, "slot_fill_failed");
    }

    private TemplateManifestService.TemplateManifest filterManifest(TemplateManifestService.TemplateManifest manifest,
                                                                    List<String> requestedSlotIds) {
        if (requestedSlotIds == null || requestedSlotIds.isEmpty()) {
            return manifest;
        }
        List<String> normalizedSlotIds = requestedSlotIds.stream()
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        List<TemplateManifestService.TemplateSlot> slots = manifest.slots().stream()
                .filter(slot -> normalizedSlotIds.contains(slot.id()))
                .toList();
        return new TemplateManifestService.TemplateManifest(
                manifest.templateId(),
                manifest.description(),
                slots,
                manifest.protectedFiles(),
                manifest.maxSlotsPerGeneration()
        );
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
                        category,
                        null,  // example - 自动生成的不提供示例
                        null,  // dependencies - 自动生成的不提供依赖
                        null,  // styleVariables - 自动生成的不提供样式变量
                        null,  // outputFormat - 自动生成的不提供输出格式
                        null,  // marker - 自动生成的不提供锚点
                        null   // patchMode - 默认 whole_file
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

    private String buildSlotDefinition(TemplateManifestService.TemplateManifest manifest, String moduleName) {
        List<Map<String, Object>> slots = manifest.slots().stream()
                .map(slot -> {
                    Map<String, Object> slotMap = new java.util.HashMap<>();
                    slotMap.put("id", slot.id());
                    slotMap.put("file", resolveDynamicSlotFile(slot.file(), moduleName));
                    if (StrUtil.isNotBlank(moduleName)) {
                        slotMap.put("moduleName", moduleName);
                        slotMap.put("packageName", moduleName);
                    }
                    slotMap.put("description", slot.description());
                    slotMap.put("required", slot.isRequired());
                    slotMap.put("type", slot.getType());
                    if (StrUtil.isNotBlank(slot.category())) {
                        slotMap.put("category", slot.category());
                    }

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
                    if (StrUtil.isNotBlank(slot.marker())) {
                        slotMap.put("marker", slot.marker());
                        slotMap.put("patchMode", slot.getPatchMode());
                    }

                    return slotMap;
                })
                .collect(Collectors.toList());

        return JSONUtil.toJsonPrettyStr(Map.of(
                "templateId", manifest.templateId(),
                "description", manifest.description(),
                "dynamicModuleName", moduleName,
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
                                                           TemplateManifestService.TemplateManifest manifest,
                                                           String moduleName) {
        // 构建 slotId -> file 映射
        Map<String, TemplateManifestService.TemplateSlot> slotMap = manifest.slots().stream()
                .collect(Collectors.toMap(TemplateManifestService.TemplateSlot::id, slot -> slot));

        List<PatchOperation> operations = new ArrayList<>();

        for (SlotFillOutput.SlotContent slot : slots) {
            TemplateManifestService.TemplateSlot templateSlot = slotMap.get(slot.slotId());
            if (templateSlot == null || StrUtil.isBlank(templateSlot.file())) {
                log.warn("未知的 slotId: {}", slot.slotId());
                continue;
            }

            if (StrUtil.isBlank(slot.content())) {
                log.debug("Slot 内容为空: {}", slot.slotId());
                continue;
            }

            operations.add(toPatchOperation(templateSlot, slot.content(), moduleName));
        }
        for (TemplateManifestService.TemplateSlot templateSlot : manifest.slots()) {
            if (isDeterministicBackendImportSlot(templateSlot, moduleName)
                    && operations.stream().noneMatch(operation -> sameSlotTarget(operation, templateSlot, moduleName))) {
                operations.add(toPatchOperation(templateSlot, "", moduleName));
            }
        }

        return operations;
    }

    private PatchOperation toPatchOperation(TemplateManifestService.TemplateSlot slot, String content, String moduleName) {
        String file = resolveDynamicSlotFile(slot.file(), moduleName);
        if (PatchOperation.ACTION_GO_ADD_IMPORT.equals(slot.getPatchMode())) {
            return PatchOperation.goAddImport(file, buildBackendModuleImport(moduleName));
        }
        if (PatchOperation.ACTION_APPEND_SQL_MIGRATION.equals(slot.getPatchMode())) {
            return PatchOperation.appendSqlMigration(file, content);
        }
        if (StrUtil.isBlank(slot.marker())) {
            if (!file.equals(slot.file())) {
                return PatchOperation.add(file, content);
            }
            return PatchOperation.modify(file, content);
        }
        return switch (slot.getPatchMode()) {
            case PatchOperation.ACTION_INSERT_BEFORE_MARKER -> PatchOperation.insertBeforeMarker(file, slot.marker(), content);
            case PatchOperation.ACTION_INSERT_AFTER_MARKER -> PatchOperation.insertAfterMarker(file, slot.marker(), content);
            case PatchOperation.ACTION_GO_ADD_IMPORT -> PatchOperation.goAddImport(file, content);
            case PatchOperation.ACTION_GO_APPEND_TO_FUNCTION -> PatchOperation.goAppendToFunction(file, slot.marker(), content);
            case PatchOperation.ACTION_GO_ADD_STRUCT_FIELDS -> PatchOperation.goAddStructFields(file, slot.marker(), content);
            case PatchOperation.ACTION_APPEND_SQL_MIGRATION -> PatchOperation.appendSqlMigration(file, content);
            default -> PatchOperation.replace(file, slot.marker(), content);
        };
    }

    private String resolveDynamicSlotFile(String file, String moduleName) {
        if (StrUtil.isBlank(file) || StrUtil.isBlank(moduleName) || "sample".equals(moduleName)) {
            return file;
        }
        return file.replace("internal/modules/sample/", "internal/modules/" + moduleName + "/");
    }

    private List<String> deterministicFilledSlotIds(TemplateManifestService.TemplateManifest manifest, String moduleName) {
        return manifest.slots().stream()
                .filter(slot -> isDeterministicBackendImportSlot(slot, moduleName))
                .map(TemplateManifestService.TemplateSlot::id)
                .toList();
    }

    private boolean isDeterministicBackendImportSlot(TemplateManifestService.TemplateSlot slot, String moduleName) {
        return slot != null
                && StrUtil.isNotBlank(moduleName)
                && PatchOperation.ACTION_GO_ADD_IMPORT.equals(slot.getPatchMode());
    }

    private boolean sameSlotTarget(PatchOperation operation,
                                   TemplateManifestService.TemplateSlot slot,
                                   String moduleName) {
        return operation != null
                && operation.action().equals(slot.getPatchMode())
                && operation.relativePath().equals(resolveDynamicSlotFile(slot.file(), moduleName));
    }

    private String buildBackendModuleImport(String moduleName) {
        return BACKEND_MODULE_IMPORT_PREFIX + StrUtil.blankToDefault(moduleName, "app");
    }

    private String inferBackendModuleName(String templateId, String userMessage) {
        if (!BACKEND_TEMPLATE.equals(templateId)) {
            return "";
        }
        String normalized = StrUtil.blankToDefault(userMessage, "").toLowerCase(Locale.ROOT);
        Map<String, String> dictionary = Map.ofEntries(
                Map.entry("商品", "product"),
                Map.entry("产品", "product"),
                Map.entry("product", "product"),
                Map.entry("订单", "order"),
                Map.entry("order", "order"),
                Map.entry("文章", "article"),
                Map.entry("article", "article"),
                Map.entry("任务", "task"),
                Map.entry("task", "task"),
                Map.entry("客户", "customer"),
                Map.entry("customer", "customer"),
                Map.entry("库存", "inventory"),
                Map.entry("inventory", "inventory"),
                Map.entry("用户", "user"),
                Map.entry("user", "user")
        );
        for (Map.Entry<String, String> entry : dictionary.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "app";
    }
}
