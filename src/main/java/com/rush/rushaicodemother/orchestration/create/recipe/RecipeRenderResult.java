package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.orchestration.create.TemplateVariableManifest;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 确定性 CREATE 配方渲染的不可变输出。
 */
public record RecipeRenderResult(
        List<String> requestedSlots,
        List<String> filledSlots,
        List<PatchOperation> patchOperations,
        int totalChars,
        String summary,
        TemplateVariableManifest manifest
) {

    /** 创建{@code Recipe}{@code Render}结果实例并完成必要的依赖和初始状态设置。 */
    public RecipeRenderResult {
        requestedSlots = normalizeSlots(requestedSlots);
        filledSlots = normalizeSlots(filledSlots);
        if (!requestedSlots.containsAll(filledSlots)) {
            throw new IllegalArgumentException("已填充 slot 必须属于本次请求范围");
        }
        patchOperations = List.copyOf(patchOperations == null ? List.of() : patchOperations);
        if (totalChars < 0) {
            throw new IllegalArgumentException("totalChars must not be negative");
        }
        summary = summary == null ? "" : summary;
    }

    /**
 * 根据给定参数创建当前对象。
 *
 * @param filledSlots 待处理的 {@code filledSlots} 集合
 * @param patchOperations 补丁操作
 * @param summary 汇总
 * @param manifest 清单
 * @return {@code Recipe}{@code Render}结果
 */
    public static RecipeRenderResult of(List<String> filledSlots,
                                        List<PatchOperation> patchOperations,
                                        String summary,
                                        TemplateVariableManifest manifest) {
        return of(filledSlots, filledSlots, patchOperations, summary, manifest);
    }

    /**
     * 创建保留完整覆盖事实的 recipe 结果。
     *
     * <p>{@code available} 只表示产生了补丁；是否完整覆盖请求必须通过
     * {@link #complete()} 判断，禁止把“部分可写”误当成“意图已满足”。</p>
     */
    public static RecipeRenderResult of(List<String> requestedSlots,
                                        List<String> filledSlots,
                                        List<PatchOperation> patchOperations,
                                        String summary,
                                        TemplateVariableManifest manifest) {
        List<PatchOperation> operations = List.copyOf(patchOperations == null ? List.of() : patchOperations);
        int totalChars = operations.stream().mapToInt(RecipeRenderResult::payloadLength).sum();
        return new RecipeRenderResult(
                requestedSlots, filledSlots, operations, totalChars, summary, manifest);
    }

    /**
 * 返回{@code empty}。
 *
 * @return {@code Recipe}{@code Render}结果
 */
    public static RecipeRenderResult empty() {
        return new RecipeRenderResult(List.of(), List.of(), List.of(), 0, "", null);
    }

    public boolean available() {
        return !patchOperations.isEmpty();
    }

    /** 只有产生补丁且覆盖全部请求 slot 时，recipe 才能证明本组意图完整。 */
    public boolean complete() {
        return available() && unfilledSlots().isEmpty();
    }

    /** 返回按请求顺序排列、尚未被 renderer 覆盖的必需 slot。 */
    public List<String> unfilledSlots() {
        Set<String> filled = Set.copyOf(filledSlots);
        return requestedSlots.stream()
                .filter(slotId -> !filled.contains(slotId))
                .toList();
    }

    private static List<String> normalizeSlots(List<String> slots) {
        if (slots == null || slots.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String slot : slots) {
            if (slot != null && !slot.isBlank()) {
                normalized.add(slot.trim());
            }
        }
        return List.copyOf(normalized);
    }

    /** 返回载荷{@code Length}。 */
    private static int payloadLength(PatchOperation operation) {
        if (operation == null) {
            return 0;
        }
        String content = operation.content();
        if (content == null || content.isBlank()) {
            content = operation.newContent();
        }
        return content == null ? 0 : content.length();
    }
}
