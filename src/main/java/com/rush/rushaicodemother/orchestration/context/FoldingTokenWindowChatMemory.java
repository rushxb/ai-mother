package com.rush.rushaicodemother.orchestration.context;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 以 token 预算为界、溢出时折叠而非丢弃的智能体短期记忆。
 *
 * <p>相比消息条数窗口的两点改进：
 * <ol>
 *   <li>窗口大小以 token 计量，不会因「消息有长有短」而含义漂移 ——
 *       12 条 readFile 结果和 12 条整份源码写入的实际占用相差两个数量级；</li>
 *   <li>溢出时把最早的工具循环折叠为系统提示摘要（见
 *       {@link AgentConversationFolder}），而不是静默丢弃，
 *       模型因此仍记得自己改过哪些文件、哪些构建错误尚未解决。</li>
 * </ol>
 *
 * <p>本类不做任何截断：折叠到只剩一轮仍超预算时保持原样返回，把问题暴露给
 * 模型调用层，而不是悄悄产出一份语义已被破坏的对话。</p>
 */
public class FoldingTokenWindowChatMemory implements ChatMemory {

    /** 折叠后至少保留原文的最近工具轮数。 */
    private static final int MINIMUM_RETAINED_ROUNDS = 1;

    private final Object id;
    private final ChatMemoryStore store;
    private final AgentConversationFolder folder;
    private final AgentConversationTokenAccountant accountant;
    private final int maxTokens;

    private FoldingTokenWindowChatMemory(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "对话记忆标识不能为空");
        // 未指定存储时使用进程内存储，供审批恢复等一次性会话使用。
        this.store = builder.store == null ? new InMemoryChatMemoryStore() : builder.store;
        this.folder = Objects.requireNonNull(builder.folder, "对话折叠器不能为空");
        this.accountant = Objects.requireNonNull(builder.accountant, "token 会计不能为空");
        if (builder.maxTokens <= 0) {
            throw new IllegalArgumentException("对话记忆 token 预算必须为正");
        }
        this.maxTokens = builder.maxTokens;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public void add(ChatMessage message) {
        if (message == null) {
            return;
        }
        List<ChatMessage> messages = new ArrayList<>(store.getMessages(id));
        messages.add(message);
        store.updateMessages(id, enforceBudget(messages));
    }

    @Override
    public void set(Iterable<ChatMessage> messages) {
        Objects.requireNonNull(messages, "对话消息不能为空");
        List<ChatMessage> replacement = new ArrayList<>();
        messages.forEach(replacement::add);
        if (replacement.isEmpty()) {
            throw new IllegalArgumentException("对话消息不能为空");
        }
        store.updateMessages(id, enforceBudget(replacement));
    }

    @Override
    public List<ChatMessage> messages() {
        // 读取时同样收敛预算：存储可能由其他版本或路径写入过更长的历史。
        return new ArrayList<>(enforceBudget(new ArrayList<>(store.getMessages(id))));
    }

    @Override
    public void clear() {
        store.deleteMessages(id);
    }

    /**
     * 把消息序列收敛到 token 预算内。
     *
     * <p>从「尽量多保留原文」开始逐步降低保留轮数，取第一个落入预算的结果，
     * 因此正常情况下只折叠必要的最少轮次。</p>
     */
    private List<ChatMessage> enforceBudget(List<ChatMessage> messages) {
        if (messages.isEmpty() || accountant.estimate(messages) <= maxTokens) {
            return messages;
        }
        int toolRounds = countToolRounds(messages);
        for (int keep = toolRounds - 1; keep >= MINIMUM_RETAINED_ROUNDS; keep--) {
            AgentConversationFolder.FoldResult result = folder.fold(messages, keep);
            if (!result.folded()) {
                continue;
            }
            if (accountant.estimate(result.messages()) <= maxTokens) {
                return result.messages();
            }
            // 已折叠但仍超预算时继续降低保留轮数，最后一次结果作为兜底。
            if (keep == MINIMUM_RETAINED_ROUNDS) {
                return result.messages();
            }
        }
        return messages;
    }

    private int countToolRounds(List<ChatMessage> messages) {
        int rounds = 0;
        for (ChatMessage message : messages) {
            if (message instanceof dev.langchain4j.data.message.AiMessage aiMessage
                    && aiMessage.hasToolExecutionRequests()) {
                rounds++;
            }
        }
        return rounds;
    }

    /** 创建构建器。 */
    public static Builder builder() {
        return new Builder();
    }

    /** {@link FoldingTokenWindowChatMemory} 的构建器。 */
    public static final class Builder {

        private Object id;
        private ChatMemoryStore store;
        private AgentConversationFolder folder;
        private AgentConversationTokenAccountant accountant;
        private int maxTokens;

        public Builder id(Object id) {
            this.id = id;
            return this;
        }

        public Builder chatMemoryStore(ChatMemoryStore store) {
            this.store = store;
            return this;
        }

        public Builder folder(AgentConversationFolder folder) {
            this.folder = folder;
            return this;
        }

        public Builder accountant(AgentConversationTokenAccountant accountant) {
            this.accountant = accountant;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public FoldingTokenWindowChatMemory build() {
            return new FoldingTokenWindowChatMemory(this);
        }
    }
}
