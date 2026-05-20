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
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 模板 slot 填充服务。
 * <p>
 * 负责协调 AI 填充模板的各个 slot，生成 patch 操作。
 */
@Slf4j
@Service
public class TemplateSlotFillService {

    private static final int MAX_TEMPLATE_CONTEXT_CHARS = 30000;

    private final TemplateManifestService templateManifestService;
    private final AiSlotFillServiceFactory aiSlotFillServiceFactory;

    public TemplateSlotFillService(TemplateManifestService templateManifestService,
                                    AiSlotFillServiceFactory aiSlotFillServiceFactory) {
        this.templateManifestService = templateManifestService;
        this.aiSlotFillServiceFactory = aiSlotFillServiceFactory;
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

        // 1. 获取模板 manifest
        TemplateManifestService.TemplateManifest manifest = templateManifestService.getManifest(templateId);
        if (manifest == null || manifest.slots() == null || manifest.slots().isEmpty()) {
            log.debug("模板没有 slot 定义: {}", templateId);
            return null;
        }

        // 2. 构建 slot 定义 JSON
        String slotDefinition = buildSlotDefinition(manifest);

        // 3. 读取模板上下文（现有文件内容）
        String templateContext = buildTemplateContext(templateId, appId, manifest);

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
        return templateManifestService.hasManifest(templateId);
    }

    private String buildSlotDefinition(TemplateManifestService.TemplateManifest manifest) {
        List<Map<String, Object>> slots = manifest.slots().stream()
                .map(slot -> Map.<String, Object>of(
                        "id", slot.id(),
                        "file", slot.file(),
                        "description", slot.description(),
                        "required", slot.isRequired()
                ))
                .collect(Collectors.toList());

        return JSONUtil.toJsonPrettyStr(Map.of(
                "templateId", manifest.templateId(),
                "description", manifest.description(),
                "slots", slots
        ));
    }

    private String buildTemplateContext(String templateId, Long appId,
                                         TemplateManifestService.TemplateManifest manifest) {
        StringBuilder context = new StringBuilder();
        int totalChars = 0;

        // 读取每个 slot 对应的文件内容
        for (TemplateManifestService.TemplateSlot slot : manifest.slots()) {
            if (totalChars >= MAX_TEMPLATE_CONTEXT_CHARS) {
                context.append("\n... (更多文件已省略)\n");
                break;
            }

            String filePath = slot.file();
            String content = readTemplateFile(templateId, filePath);

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

    private String readTemplateFile(String templateId, String relativePath) {
        try {
            String templatePath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator
                    + templateId + File.separator + relativePath;
            File file = new File(templatePath);
            if (file.exists() && file.isFile()) {
                return FileUtil.readString(file, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("读取模板文件失败: {}/{}", templateId, relativePath);
        }
        return null;
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
