package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.orchestration.create.TemplateVariableManifest;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;

import java.util.List;

/**
 * 确定性 CREATE 配方渲染的不可变输出。
 */
public record RecipeRenderResult(
        List<String> filledSlots,
        List<PatchOperation> patchOperations,
        int totalChars,
        String summary,
        TemplateVariableManifest manifest
) {

    /** 创建{@code Recipe}{@code Render}结果实例并完成必要的依赖和初始状态设置。 */
    public RecipeRenderResult {
        filledSlots = List.copyOf(filledSlots == null ? List.of() : filledSlots);
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
        List<PatchOperation> operations = List.copyOf(patchOperations == null ? List.of() : patchOperations);
        int totalChars = operations.stream().mapToInt(RecipeRenderResult::payloadLength).sum();
        return new RecipeRenderResult(filledSlots, operations, totalChars, summary, manifest);
    }

    /**
 * 返回{@code empty}。
 *
 * @return {@code Recipe}{@code Render}结果
 */
    public static RecipeRenderResult empty() {
        return new RecipeRenderResult(List.of(), List.of(), 0, "", null);
    }

    public boolean available() {
        return !patchOperations.isEmpty();
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
