package com.yupi.yuaicodemother.exception;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    @Test
    void shouldExtractReadableGuardrailMessage() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Method method = GlobalExceptionHandler.class.getDeclaredMethod("extractGuardrailMessage", String.class);
        method.setAccessible(true);

        String rawMessage = "The guardrail com.yupi.yuaicodemother.ai.guardrail.PromptSafetyInputGuardrail failed with this message: 输入内容过长，不要超过 1000 字";
        String readableMessage = (String) method.invoke(handler, rawMessage);

        assertEquals("输入内容过长，不要超过 1000 字", readableMessage);
    }
}
