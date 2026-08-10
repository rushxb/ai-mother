package com.rush.rushaicodemother.orchestration.context;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 智能体短期记忆的窗口策略与唯一构造入口。
 *
 * <p>窗口按 token 计量而非消息条数：同样「12 条消息」，纯读取结果与整份源码写入的
 * 实际占用相差两个数量级，条数窗口因此无法真正约束模型输入，也无法解释
 * 「为什么模型忘了自己改过什么」。</p>
 *
 * <p>预算按固定常量下沉，不进 yaml；prod/worker 若需差异化由配置后处理器注入。
 * 新增窗口相关常量时须同步登记进发布配置指纹。</p>
 */
@Component
public class AgentConversationWindowPolicy {

    /**
     * 首次生成会话的 token 预算。
     *
     * <p>取值依据：系统提示约 5k token（vue v2 提示 18KB），单次源码写入的工具参数
     * 可达 8k token（{@code ChatMemoryProperties.COMPLETED_TOOL_ARGUMENTS_MAX_CHARS}），
     * 预算须容纳系统提示 + 用户诉求 + 至少三轮完整工具循环，否则折叠会过于频繁。</p>
     */
    public static final int GENERATION_MAX_TOKENS = 48_000;

    /** 审批恢复会话的 token 预算；恢复路径只需承载待处理轮次与近期证据。 */
    public static final int CONTINUATION_MAX_TOKENS = 32_000;

    private final AgentConversationFolder folder;
    private final AgentConversationTokenAccountant accountant;

    public AgentConversationWindowPolicy(AgentConversationFolder folder,
                                         AgentConversationTokenAccountant accountant) {
        this.folder = Objects.requireNonNull(folder, "对话折叠器不能为空");
        this.accountant = Objects.requireNonNull(accountant, "token 会计不能为空");
    }

    /**
     * 创建首次生成会话的记忆。
     *
     * @param memoryId 记忆标识，通常为 appId
     * @param store    记忆存储
     * @return 折叠式 token 窗口记忆
     */
    public ChatMemory createGenerationMemory(Object memoryId, ChatMemoryStore store) {
        return build(memoryId, store, GENERATION_MAX_TOKENS);
    }

    /**
     * 创建审批恢复会话的记忆。
     *
     * @param memoryId 记忆标识
     * @param store    记忆存储；为空时使用进程内存储
     * @return 折叠式 token 窗口记忆
     */
    public ChatMemory createContinuationMemory(Object memoryId, ChatMemoryStore store) {
        return build(memoryId, store, CONTINUATION_MAX_TOKENS);
    }

    /**
     * 按预算收敛一段消息序列，供不持有记忆实例的调用方复用同一折叠语义。
     *
     * @param messages  消息序列
     * @param maxTokens token 预算
     * @return 折叠结果
     */
    public AgentConversationFolder.FoldResult foldToBudget(List<ChatMessage> messages,
                                                           int maxTokens) {
        if (messages == null || messages.isEmpty()
                || accountant.estimate(messages) <= maxTokens) {
            return AgentConversationFolder.FoldResult.unchanged(messages);
        }
        return folder.fold(messages, 1);
    }

    private ChatMemory build(Object memoryId, ChatMemoryStore store, int maxTokens) {
        return FoldingTokenWindowChatMemory.builder()
                .id(Objects.requireNonNull(memoryId, "对话记忆标识不能为空"))
                .chatMemoryStore(store)
                .folder(folder)
                .accountant(accountant)
                .maxTokens(maxTokens)
                .build();
    }
}
