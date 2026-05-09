package com.yupi.yuaicodemother.ai.guardrail;

import com.yupi.yuaicodemother.constant.AppConstant;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptSafetyInputGuardrailTest {

    private final PromptSafetyInputGuardrail guardrail = new PromptSafetyInputGuardrail();

    @Test
    void shouldIgnoreInjectedProjectContextWhenCheckingLength() {
        String originalUserInput = "还原到之前的博客内容";
        String longProjectContext = "A".repeat(5000);
        String combinedInput = originalUserInput + "\n\n" + AppConstant.PROJECT_CONTEXT_MARKER + "\n" + longProjectContext;

        var result = guardrail.validate(UserMessage.from(combinedInput));

        assertFalse(result.isFatal());
    }

    @Test
    void shouldStillRejectTooLongOriginalUserInput() {
        String tooLongUserInput = "A".repeat(1001);

        var result = guardrail.validate(UserMessage.from(tooLongUserInput));

        assertTrue(result.isFatal());
    }
}
