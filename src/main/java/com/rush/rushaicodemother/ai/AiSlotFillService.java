package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.ai.model.SlotFillOutput;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * AI slot 填充服务接口。
 * <p>
 * 根据用户需求和 slot 定义，生成需要填充的内容。
 */
public interface AiSlotFillService {

    /**
     * 根据用户需求填充模板 slots。
     *
     * @param userMessage    用户需求
     * @param slotDefinition slot 定义（JSON 格式）
     * @param templateContext 模板上下文（现有文件内容）
     * @return 填充结果
     */
    @SystemMessage(fromResource = "prompt/slot-fill-system-prompt.txt")
    @UserMessage("用户需求：{{userMessage}}\n\nSlot 定义：{{slotDefinition}}\n\n模板上下文：{{templateContext}}")
    SlotFillOutput fillSlots(@V("userMessage") String userMessage,
                              @V("slotDefinition") String slotDefinition,
                              @V("templateContext") String templateContext);
}
