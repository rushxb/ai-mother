package com.rush.rushaicodemother.orchestration.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史工具循环折叠回归。
 *
 * <p>三条不可破坏的约束（见 {@link AgentConversationFolder} 类注释）在此逐条设卡：
 * 按整轮折叠、摘要并入既有系统消息、只折叠已闭合轮次。任一条被破坏都会导致
 * 模型请求被服务端拒绝，或系统提示在检查点里被顶掉。</p>
 */
class AgentConversationFolderTest {

    private final AgentConversationFolder folder =
            new AgentConversationFolder(new ToolRoundPathExtractor(new ObjectMapper()));

    @Test
    void foldingMustNotLeaveOrphanToolResultMessages() {
        List<ChatMessage> messages = conversation(4);

        AgentConversationFolder.FoldResult result = folder.fold(messages, 1);

        assertTrue(result.folded());
        assertNoOrphanToolResults(result.messages());
    }

    @Test
    void summaryMustBeMergedIntoExistingSystemMessageRatherThanAppendedAsNewOne() {
        List<ChatMessage> messages = conversation(3);

        AgentConversationFolder.FoldResult result = folder.fold(messages, 1);

        long systemCount = result.messages().stream()
                .filter(SystemMessage.class::isInstance)
                .count();
        assertEquals(1, systemCount,
                "折叠摘要必须并入既有系统消息：新增系统消息会在检查点续跑时顶掉真正的系统提示");
        SystemMessage system = assertInstanceOf(
                SystemMessage.class, result.messages().getFirst());
        assertTrue(system.text().startsWith("系统提示"), "原系统提示必须保留在最前");
        assertTrue(system.text().contains("【已折叠的历史工具循环】"), "摘要必须写入系统消息");
    }

    @Test
    void mutatedPathsMustBeReportedSoModelDoesNotRewriteThem() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("生成后台"));
        messages.addAll(toolRound("r1", "writeFile",
                "{\"relativeFilePath\":\"src/main.ts\"}", "已写入", false));
        messages.addAll(toolRound("r2", "readFile",
                "{\"relativeFilePath\":\"src/App.vue\"}", "文件内容", false));
        messages.addAll(toolRound("r3", "writeFile",
                "{\"relativeFilePath\":\"src/router.ts\"}", "已写入", false));

        AgentConversationFolder.FoldResult result = folder.fold(messages, 1);

        String system = assertInstanceOf(
                SystemMessage.class, result.messages().getFirst()).text();
        assertTrue(system.contains("已写入或修改文件：src/main.ts"),
                "已改动路径必须出现在摘要:\n" + system);
        assertTrue(system.contains("已读取文件：src/App.vue"),
                "已读取路径必须出现在摘要:\n" + system);
        assertTrue(system.contains("请勿重复执行"), "摘要必须阻止模型重复落盘");
    }

    @Test
    void failedWriteMustNotBeReportedAsLandedChange() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("生成后台"));
        messages.addAll(toolRound("r1", "writeFile",
                "{\"relativeFilePath\":\"src/failed.ts\"}", "写入失败：路径越界", true));
        messages.addAll(toolRound("r2", "readFile",
                "{\"relativeFilePath\":\"src/ok.ts\"}", "内容", false));

        AgentConversationFolder.FoldResult result = folder.fold(messages, 1);

        String system = assertInstanceOf(
                SystemMessage.class, result.messages().getFirst()).text();
        assertFalse(system.contains("src/failed.ts"),
                "失败的写操作未落盘，不得计入已改动，否则模型会跳过必要的重试:\n" + system);
        assertFalse(system.contains("已写入或修改文件"),
                "本用例唯一的写操作已失败，不应出现改动栏:\n" + system);
        assertTrue(system.contains("写入失败：路径越界"), "失败证据必须保留供模型重试");
    }

    @Test
    void deletedPathMustNotAlsoBeListedAsMutated() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("清理无用文件"));
        messages.addAll(toolRound("r1", "writeFile",
                "{\"relativeFilePath\":\"src/tmp.ts\"}", "已写入", false));
        messages.addAll(toolRound("r2", "deleteFile",
                "{\"relativeFilePath\":\"src/tmp.ts\"}", "已删除", false));
        messages.addAll(toolRound("r3", "readFile",
                "{\"relativeFilePath\":\"src/keep.ts\"}", "内容", false));

        AgentConversationFolder.FoldResult result = folder.fold(messages, 1);

        String system = assertInstanceOf(
                SystemMessage.class, result.messages().getFirst()).text();
        assertTrue(system.contains("已删除文件：src/tmp.ts"), "删除必须记录:\n" + system);
        assertFalse(system.contains("已写入或修改文件"),
                "已删除的文件不得同时列为已改动，否则模型会认为它仍然存在:\n" + system);
    }

    @Test
    void openRoundMustNeverBeFolded() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("生成后台"));
        // 第一轮请求两个工具但只回了一个结果：未闭合，折叠会产生孤儿结果。
        messages.add(AiMessage.builder().toolExecutionRequests(List.of(
                ToolExecutionRequest.builder().id("a").name("readFile")
                        .arguments("{\"relativeFilePath\":\"src/a.ts\"}").build(),
                ToolExecutionRequest.builder().id("b").name("readFile")
                        .arguments("{\"relativeFilePath\":\"src/b.ts\"}").build()
        )).build());
        messages.add(ToolExecutionResultMessage.from("a", "readFile", "内容"));
        messages.addAll(toolRound("r2", "readFile",
                "{\"relativeFilePath\":\"src/c.ts\"}", "内容", false));

        AgentConversationFolder.FoldResult result = folder.fold(messages, 1);

        assertFalse(result.folded(), "未闭合轮不得被折叠，否则请求会被服务端拒绝");
        assertEquals(messages, result.messages());
    }

    @Test
    void foldingMustPreserveTaskAnchorUserMessage() {
        List<ChatMessage> messages = conversation(4);

        AgentConversationFolder.FoldResult result = folder.fold(messages, 1);

        boolean hasUser = result.messages().stream().anyMatch(UserMessage.class::isInstance);
        assertTrue(hasUser, "折叠后必须保留任务锚点用户消息，否则模型不知道要做什么");
    }

    @Test
    void valueIdenticalRoundsMustNotConfuseBoundaryLocation() {
        // 轮次以记录类型表示，值相等的重复轮次会让基于 equals 的下标定位命中靠前的那一轮，
        // 导致「宣称折叠 N 轮、实际只折叠 1 轮」，预算并未真正下降。
        // 调用 id 的唯一性由模型服务端决定，不能作为折叠正确性的前提。
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("同一个诉求"));
        for (int index = 0; index < 4; index++) {
            messages.addAll(toolRound("same-id", "readFile",
                    "{\"relativeFilePath\":\"src/same.ts\"}", "同样的内容", false));
        }

        AgentConversationFolder.FoldResult result = folder.fold(messages, 1);

        assertTrue(result.folded());
        assertEquals(3, result.foldedRounds());
        assertNoOrphanToolResults(result.messages());
        long retainedRounds = result.messages().stream()
                .filter(AiMessage.class::isInstance)
                .map(AiMessage.class::cast)
                .filter(AiMessage::hasToolExecutionRequests)
                .count();
        assertEquals(1, retainedRounds,
                "宣称折叠 3 轮就必须真的只剩 1 轮原文，否则预算并未下降");
        boolean hasUser = result.messages().stream().anyMatch(UserMessage.class::isInstance);
        assertTrue(hasUser, "重复轮次下仍须保留任务锚点");
    }

    @Test
    void alreadyWithinKeepBudgetMustBeReturnedUnchanged() {
        List<ChatMessage> messages = conversation(2);

        AgentConversationFolder.FoldResult result = folder.fold(messages, 3);

        assertFalse(result.folded());
        assertEquals(messages, result.messages());
    }

    @Test
    void nullOrEmptyInputMustBeTolerated() {
        assertFalse(folder.fold(null, 1).folded());
        assertFalse(folder.fold(List.of(), 1).folded());
        assertFalse(folder.fold(conversation(3), 0).folded());
    }

    private static void assertNoOrphanToolResults(List<ChatMessage> messages) {
        for (int index = 0; index < messages.size(); index++) {
            if (!(messages.get(index) instanceof ToolExecutionResultMessage)) {
                continue;
            }
            // 结果消息之前必须存在同轮的工具请求消息。
            int scan = index - 1;
            while (scan >= 0 && messages.get(scan) instanceof ToolExecutionResultMessage) {
                scan--;
            }
            assertTrue(scan >= 0 && messages.get(scan) instanceof AiMessage aiMessage
                            && aiMessage.hasToolExecutionRequests(),
                    "第 " + index + " 条工具结果缺少对应的工具请求，服务端会拒绝该请求");
        }
    }

    private static List<ChatMessage> conversation(int toolRounds) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("生成管理后台"));
        for (int index = 0; index < toolRounds; index++) {
            messages.addAll(toolRound("call-" + index, "readFile",
                    "{\"relativeFilePath\":\"src/file-" + index + ".ts\"}",
                    "文件 " + index + " 的内容", false));
        }
        return messages;
    }

    private static List<ChatMessage> toolRound(String id,
                                               String tool,
                                               String arguments,
                                               String resultText,
                                               boolean failed) {
        AiMessage request = AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id(id).name(tool).arguments(arguments).build()))
                .build();
        ToolExecutionResultMessage result = failed
                ? ToolExecutionResultMessage.builder()
                        .id(id).toolName(tool).text(resultText).isError(true).build()
                : ToolExecutionResultMessage.from(id, tool, resultText);
        return List.of(request, result);
    }
}
