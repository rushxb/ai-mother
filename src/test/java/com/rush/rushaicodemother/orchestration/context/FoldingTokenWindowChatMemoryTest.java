package com.rush.rushaicodemother.orchestration.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * token 窗口记忆回归。
 *
 * <p>三条不可退让的性质：
 * <ol>
 *   <li>预算收敛：长对话必须落回预算内，否则模型调用直接被服务端拒绝；</li>
 *   <li>不产孤儿：折叠只能发生在轮次边界，一旦留下没有对应 AiMessage 的
 *       工具结果消息，OpenAI 兼容服务端会整单报错；</li>
 *   <li>不静默丢弃：溢出的工具轮变成系统提示摘要，模型仍记得改过哪些文件。</li>
 * </ol>
 */
class FoldingTokenWindowChatMemoryTest {

    /** 仅出现在最早一轮的内容标记，确保断言不会被其他轮次的相似文本误命中。 */
    private static final String EARLIEST_CONTENT_MARKER = "最早一轮的独有内容标记";

    private final AgentConversationTokenAccountant accountant =
            new AgentConversationTokenAccountant(new OpenAiCompatibleContextTokenEstimator(
                    new AiContextPackBudgetProperties()));
    private final AgentConversationFolder folder =
            new AgentConversationFolder(new ToolRoundPathExtractor(new ObjectMapper()));

    private ChatMemory memory(int maxTokens) {
        return memory(maxTokens, new InMemoryChatMemoryStore());
    }

    private ChatMemory memory(int maxTokens, ChatMemoryStore store) {
        return FoldingTokenWindowChatMemory.builder()
                .id("app-1")
                .chatMemoryStore(store)
                .folder(folder)
                .accountant(accountant)
                .maxTokens(maxTokens)
                .build();
    }

    @Test
    void conversationWithinBudgetMustBeKeptVerbatim() {
        ChatMemory memory = memory(48_000);
        memory.add(SystemMessage.from("你是代码生成智能体"));
        memory.add(UserMessage.from("做一个登录页"));
        memory.add(AiMessage.from("好的"));

        assertEquals(3, memory.messages().size(), "未超预算时不应发生任何折叠");
    }

    @Test
    void overflowingConversationMustConvergeIntoBudget() {
        ChatMemory memory = memory(2_000);
        memory.add(SystemMessage.from("你是代码生成智能体"));
        memory.add(UserMessage.from("做一个后台管理系统"));
        for (int round = 0; round < 12; round++) {
            addToolRound(memory, round, "export const v" + round + " = 1;\n".repeat(200));
        }

        List<ChatMessage> messages = memory.messages();
        assertTrue(accountant.estimate(messages) <= 2_000,
                "折叠后仍超预算，模型调用会被服务端拒绝，实际=" + accountant.estimate(messages));
        assertTrue(messages.size() < 26, "消息数量未下降，说明折叠没有真正生效");
    }

    @Test
    void foldingMustNeverLeaveOrphanToolResults() {
        ChatMemory memory = memory(1_500);
        memory.add(SystemMessage.from("系统提示"));
        memory.add(UserMessage.from("诉求"));
        for (int round = 0; round < 10; round++) {
            addToolRound(memory, round, "内容\n".repeat(300));
        }

        assertNoOrphanToolResults(memory.messages());
    }

    @Test
    void foldedRoundsMustSurviveAsSummaryInsteadOfBeingDropped() {
        ChatMemory memory = memory(1_200);
        memory.add(SystemMessage.from("系统提示"));
        memory.add(UserMessage.from("诉求"));
        // 第一轮写入可识别路径与唯一内容标记，随后用大量轮次把它挤出预算。
        addToolRoundWriting(memory, 0, "src/earliest.ts",
                EARLIEST_CONTENT_MARKER + "\n".repeat(200));
        for (int round = 1; round < 10; round++) {
            addToolRound(memory, round, "后续内容\n".repeat(300));
        }

        List<ChatMessage> messages = memory.messages();
        String systemText = messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(message -> ((SystemMessage) message).text())
                .findFirst()
                .orElse("");

        assertTrue(systemText.contains("src/earliest.ts"),
                "最早的工具轮被静默丢弃，模型会重复写入已完成的文件，系统提示=" + systemText);
        assertTrue(messages.stream().noneMatch(message ->
                        message instanceof ToolExecutionResultMessage result
                                && result.text().contains(EARLIEST_CONTENT_MARKER)),
                "已折叠轮次的原文仍在上下文中，预算并未真正下降");
    }

    @Test
    void systemPromptMustAlwaysSurviveFolding() {
        ChatMemory memory = memory(1_200);
        memory.add(SystemMessage.from("绝不能丢失的系统约束"));
        memory.add(UserMessage.from("诉求"));
        for (int round = 0; round < 10; round++) {
            addToolRound(memory, round, "内容\n".repeat(300));
        }

        assertTrue(memory.messages().stream()
                        .anyMatch(message -> message instanceof SystemMessage system
                                && system.text().contains("绝不能丢失的系统约束")),
                "系统提示被折叠掉，模型将失去全部任务约束");
    }

    @Test
    void singleOversizedRoundMustBeKeptRatherThanTruncated() {
        // 折叠到只剩一轮仍超预算时，本类明确选择保持原样并把问题暴露给调用层，
        // 而不是悄悄产出一份语义已被破坏的对话。
        ChatMemory memory = memory(50);
        memory.add(SystemMessage.from("系统提示"));
        addToolRound(memory, 0, "无法压缩的巨大内容\n".repeat(500));

        List<ChatMessage> messages = memory.messages();
        assertTrue(accountant.estimate(messages) > 50, "该场景本就超预算");
        assertNoOrphanToolResults(messages);
        assertTrue(messages.stream().anyMatch(ToolExecutionResultMessage.class::isInstance),
                "唯一一轮不应被丢弃");
    }

    @Test
    void budgetMustBeEnforcedOnWriteNotOnlyOnRead() {
        // 只在读取时收敛是不够的：共享存储会随会话无界增长，且每次读取都要重新
        // 对全量历史分词。这里直接断言落库内容，绕过 messages() 的兜底收敛。
        ChatMemoryStore store = new InMemoryChatMemoryStore();
        ChatMemory memory = memory(4_000, store);
        memory.add(SystemMessage.from("系统提示"));
        memory.add(UserMessage.from("诉求"));
        for (int round = 0; round < 12; round++) {
            addToolRound(memory, round, "内容\n".repeat(300));
        }

        List<ChatMessage> persisted = store.getMessages("app-1");
        assertTrue(accountant.estimate(persisted) <= 4_000,
                "写入时未收敛预算，共享存储会无界增长，落库占用="
                        + accountant.estimate(persisted));
        // 落库内容必须与读取结果同样收敛，否则收敛只是读取期的假象。
        assertEquals(accountant.estimate(memory.messages()), accountant.estimate(persisted),
                "落库内容与读取结果占用不一致，说明预算只在读取期生效");
        assertNoOrphanToolResults(persisted);
    }

    @Test
    void readMustBeIdempotentAndNotDegradeFurtherOnRepeatedCalls() {
        ChatMemory memory = memory(1_500);
        memory.add(SystemMessage.from("系统提示"));
        memory.add(UserMessage.from("诉求"));
        for (int round = 0; round < 10; round++) {
            addToolRound(memory, round, "内容\n".repeat(300));
        }

        List<ChatMessage> first = memory.messages();
        List<ChatMessage> second = memory.messages();
        assertEquals(first.size(), second.size(), "重复读取不应持续折叠已收敛的对话");
        assertEquals(accountant.estimate(first), accountant.estimate(second));
    }

    @Test
    void setMustReplaceHistoryAndEnforceBudgetInOneShot() {
        ChatMemory memory = memory(1_500);
        List<ChatMessage> restored = new ArrayList<>();
        restored.add(SystemMessage.from("系统提示"));
        restored.add(UserMessage.from("诉求"));
        for (int round = 0; round < 10; round++) {
            restored.add(AiMessage.from(writeRequest(round, "src/f" + round + ".ts",
                    "内容\n".repeat(300))));
            restored.add(ToolExecutionResultMessage.from(
                    "call-" + round, "writeFile", "写入成功"));
        }

        memory.set(restored);

        assertTrue(accountant.estimate(memory.messages()) <= 1_500,
                "一次性写入的历史也必须收敛到预算内");
        assertNoOrphanToolResults(memory.messages());
    }

    @Test
    void clearMustRemoveEverything() {
        ChatMemory memory = memory(48_000);
        memory.add(SystemMessage.from("系统提示"));
        memory.clear();

        assertTrue(memory.messages().isEmpty());
    }

    @Test
    void invalidConstructionMustFailFast() {
        assertThrows(IllegalArgumentException.class, () -> FoldingTokenWindowChatMemory.builder()
                .id("app-1").folder(folder).accountant(accountant).maxTokens(0).build(),
                "预算为 0 会让每次调用都折叠到底，必须构造期失败");
        assertThrows(NullPointerException.class, () -> FoldingTokenWindowChatMemory.builder()
                .folder(folder).accountant(accountant).maxTokens(100).build());
        assertThrows(NullPointerException.class, () -> FoldingTokenWindowChatMemory.builder()
                .id("app-1").accountant(accountant).maxTokens(100).build());
        assertThrows(NullPointerException.class, () -> FoldingTokenWindowChatMemory.builder()
                .id("app-1").folder(folder).maxTokens(100).build());
    }

    @Test
    void nullMessageMustBeIgnoredInsteadOfCorruptingHistory() {
        ChatMemory memory = memory(48_000);
        memory.add(SystemMessage.from("系统提示"));
        memory.add((ChatMessage) null);

        assertEquals(1, memory.messages().size());
    }

    private void addToolRound(ChatMemory memory, int round, String resultText) {
        memory.add(AiMessage.from(readRequest(round)));
        memory.add(ToolExecutionResultMessage.from("call-" + round, "readFile", resultText));
    }

    private void addToolRoundWriting(ChatMemory memory, int round, String path, String content) {
        memory.add(AiMessage.from(writeRequest(round, path, content)));
        memory.add(ToolExecutionResultMessage.from("call-" + round, "writeFile", content));
    }

    private static ToolExecutionRequest readRequest(int round) {
        return ToolExecutionRequest.builder()
                .id("call-" + round).name("readFile")
                .arguments("{\"relativeFilePath\":\"src/r" + round + ".ts\"}")
                .build();
    }

    private static ToolExecutionRequest writeRequest(int round, String path, String content) {
        return ToolExecutionRequest.builder()
                .id("call-" + round).name("writeFile")
                .arguments("{\"relativeFilePath\":\"" + path + "\",\"content\":\""
                        + content.replace("\n", "\\n") + "\"}")
                .build();
    }

    /**
     * 断言不存在孤儿工具结果。
     *
     * <p>OpenAI 兼容服务端要求每条工具结果都能追溯到发起它的 AiMessage，
     * 否则整次请求报错 —— 这是折叠只能在轮次边界发生的根本原因。</p>
     */
    private static void assertNoOrphanToolResults(List<ChatMessage> messages) {
        List<String> pendingIds = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                ai.toolExecutionRequests().forEach(request -> pendingIds.add(request.id()));
            }
            if (message instanceof ToolExecutionResultMessage result) {
                assertTrue(pendingIds.remove(result.id()),
                        "出现孤儿工具结果 " + result.id() + "，OpenAI 兼容服务端会整单拒绝");
            }
        }
        assertFalse(messages.isEmpty(), "消息序列不应为空");
    }
}
