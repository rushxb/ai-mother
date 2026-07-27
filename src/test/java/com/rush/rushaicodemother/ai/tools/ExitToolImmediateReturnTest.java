package com.rush.rushaicodemother.ai.tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ExitToolImmediateReturnTest {

    @Test
    void exitToolMustFinishWithoutAnotherModelRound() {
        AtomicInteger modelCalls = new AtomicInteger();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                modelCalls.incrementAndGet();
                ToolExecutionRequest exit = ToolExecutionRequest.builder()
                        .id("exit-1")
                        .name("exitTool")
                        .arguments("{}")
                        .build();
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.builder()
                                .toolExecutionRequests(List.of(exit))
                                .build())
                        .build());
            }
        };
        ExitService service = AiServices.builder(ExitService.class)
                .chatModel(mock(ChatModel.class))
                .streamingChatModel(model)
                .tools(new ExitTool())
                .build();
        AtomicBoolean completed = new AtomicBoolean();

        service.run("结束任务")
                .onCompleteResponse(ignored -> completed.set(true))
                .onError(error -> {
                    throw new AssertionError(error);
                })
                .start();

        assertTrue(completed.get());
        assertEquals(1, modelCalls.get());
    }

    private interface ExitService {

        @SystemMessage("你是编程助手")
        @UserMessage("{{request}}")
        TokenStream run(@V("request") String request);
    }
}
