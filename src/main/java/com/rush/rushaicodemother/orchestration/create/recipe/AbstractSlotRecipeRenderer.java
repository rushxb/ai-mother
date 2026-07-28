package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.create.SlotGroup;
import com.rush.rushaicodemother.orchestration.create.TemplateVariableManifest;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 配方的共享确定性调度，其中一个槽产生一个补丁操作。
 */
abstract class AbstractSlotRecipeRenderer<R> implements CreateRecipeRenderer {

    private final String templateId;
    private final String summary;

    protected AbstractSlotRecipeRenderer(String templateId, String summary) {
        this.templateId = Objects.requireNonNull(templateId, "templateId must not be null");
        this.summary = Objects.requireNonNull(summary, "summary must not be null");
    }

    @Override
    public final String templateId() {
        return templateId;
    }

    /**
 * 渲染{@code Abstract}插槽{@code Recipe}渲染器。
 *
 * @param userMessage 用户消息
 * @param group 分组
 * @param spec {@code spec} 对应的调用参数
 * @param manifest 清单
 * @return {@code Abstract}插槽{@code Recipe}渲染器
 */
    @Override
    public final RecipeRenderResult render(String userMessage,
                                           SlotGroup group,
                                           CreateSpec spec,
                                           TemplateVariableManifest manifest) {
        if (group == null || spec == null || !templateId.equals(group.templateId())) {
            return RecipeRenderResult.empty();
        }
        R recipe = Objects.requireNonNull(createRecipe(userMessage, spec), "recipe must not be null");
        List<PatchOperation> operations = new ArrayList<>();
        List<String> filledSlots = new ArrayList<>();
        List<String> requestedSlots = group.slotIds() == null ? List.of() : group.slotIds();
        for (String slotId : new LinkedHashSet<>(requestedSlots)) {
            if (slotId == null || slotId.isBlank()) {
                continue;
            }
            PatchOperation operation = renderSlot(slotId, recipe);
            if (operation != null) {
                operations.add(operation);
                filledSlots.add(slotId);
            }
        }
        if (operations.isEmpty()) {
            return RecipeRenderResult.empty();
        }
        return RecipeRenderResult.of(filledSlots, operations, summary, manifest);
    }

    protected abstract R createRecipe(String userMessage, CreateSpec spec);

    protected abstract PatchOperation renderSlot(String slotId, R recipe);
}
