package com.rush.rushaicodemother.orchestration.context;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话 token 会计回归。
 *
 * <p>本类守护换成 token 窗口的根本理由：预算必须按模型真实输入口径计量。
 * 只数消息条数时，「条数正常但参数巨大」的对话可以合法绕过窗口 ——
 * 一次写入整份源码的 writeFile 与一次 readFile 结果占用相差两个数量级。</p>
 */
class AgentConversationTokenAccountantTest {

    private final AgentConversationTokenAccountant accountant =
            new AgentConversationTokenAccountant(new OpenAiCompatibleContextTokenEstimator(
                    new AiContextPackBudgetProperties()));

    @Test
    void toolRequestArgumentsMustCountTowardsBudget() {
        // 这是消息条数窗口的致命漏洞：文本为空、只有巨大工具参数的一条消息。
        String hugeSource = "export const x = 1;\n".repeat(2_000);
        AiMessage argumentHeavy = AiMessage.from(ToolExecutionRequest.builder()
                .id("call-1").name("writeFile")
                .arguments("{\"relativeFilePath\":\"src/a.ts\",\"content\":\""
                        + hugeSource + "\"}")
                .build());
        AiMessage plain = AiMessage.from("好的");

        int heavyTokens = accountant.estimate(argumentHeavy);
        assertTrue(heavyTokens > 1_000,
                "工具参数未计入占用，条数正常但参数巨大的对话会绕过预算，实际=" + heavyTokens);
        assertTrue(heavyTokens > accountant.estimate(plain) * 100,
                "两条消息条数相同，占用必须相差数量级");
    }

    @Test
    void toolNameMustCountEvenWhenArgumentsAreEmpty() {
        AiMessage named = AiMessage.from(ToolExecutionRequest.builder()
                .id("call-1").name("aVeryDistinctiveToolNameRepeatedManyTimes")
                .arguments("").build());

        assertTrue(accountant.estimate(named) > accountant.estimate(AiMessage.from("")),
                "工具名也属于模型输入，必须计入占用");
    }

    @Test
    void toolResultTextMustCountTowardsBudget() {
        ToolExecutionResultMessage large = ToolExecutionResultMessage.from(
                "call-1", "readFile", "行内容\n".repeat(3_000));

        assertTrue(accountant.estimate(large) > 1_000, "工具结果文本必须计入占用");
    }

    @Test
    void everyMessageKindMustBeAccountedAndCarryProtocolOverhead() {
        // 任何一种消息被漏算为 0，都会让预算在该类消息上失效。
        List<ChatMessage> kinds = List.of(
                SystemMessage.from("系统提示"),
                UserMessage.from("用户诉求"),
                AiMessage.from("模型回复"),
                ToolExecutionResultMessage.from("call-1", "readFile", "结果"));

        for (ChatMessage message : kinds) {
            assertTrue(accountant.estimate(message) > 0,
                    message.getClass().getSimpleName() + " 被漏算，该类消息可绕过预算");
        }
        // 空文本消息也占协议开销，否则大量空消息可以无限堆积。
        assertTrue(accountant.estimate(AiMessage.from("")) > 0,
                "空消息仍有 role 等协议开销，必须计入");
    }

    @Test
    void totalMustBeSumOfIndividualMessages() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("系统提示"),
                UserMessage.from("做一个登录页"),
                AiMessage.from(ToolExecutionRequest.builder()
                        .id("call-1").name("writeFile")
                        .arguments("{\"relativeFilePath\":\"src/a.ts\"}").build()),
                ToolExecutionResultMessage.from("call-1", "writeFile", "写入成功"));

        int expected = messages.stream().mapToInt(accountant::estimate).sum();
        assertEquals(expected, accountant.estimate(messages));
    }

    @Test
    void estimateMustGrowMonotonicallyWithConversationLength() {
        // 预算收敛依赖单调性：加消息不能让估算变小，否则折叠循环可能不终止。
        List<ChatMessage> growing = new ArrayList<>();
        int previous = 0;
        for (int index = 0; index < 12; index++) {
            growing.add(UserMessage.from("第 " + index + " 条诉求，内容略长一些以产生差异"));
            int current = accountant.estimate(growing);
            assertTrue(current > previous, "第 " + index + " 条消息后估算未增长");
            previous = current;
        }
    }

    @Test
    void nullAndEmptyInputMustDegradeToZeroInsteadOfThrowing() {
        assertEquals(0, accountant.estimate((List<ChatMessage>) null));
        assertEquals(0, accountant.estimate(List.of()));
        assertEquals(0, accountant.estimate((ChatMessage) null));
    }

    @Test
    void multiPartUserMessageMustNotBeAccountedAsZero() {
        // 多模态/多段 UserMessage 不能走 singleText 分支，漏算会让图文诉求绕过预算。
        UserMessage multiPart = UserMessage.from(
                dev.langchain4j.data.message.TextContent.from("第一段内容"),
                dev.langchain4j.data.message.TextContent.from("第二段内容"));

        assertTrue(accountant.estimate(multiPart) > accountant.estimate(UserMessage.from("")),
                "多段消息被漏算为空，图文诉求可绕过预算");
    }
}
