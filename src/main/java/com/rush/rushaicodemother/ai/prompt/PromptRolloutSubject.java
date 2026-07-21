package com.rush.rushaicodemother.ai.prompt;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;

import java.util.List;

/** Privacy-safe deterministic subject used for stable prompt canary bucketing. */
public record PromptRolloutSubject(
        String interfaceName,
        String methodName,
        String cohortKey
) {
    public PromptRolloutSubject {
        interfaceName = normalize(interfaceName);
        methodName = normalize(methodName);
        cohortKey = normalize(cohortKey);
    }

    public static PromptRolloutSubject from(InvocationContext context) {
        if (context == null) {
            return new PromptRolloutSubject("unknown", "unknown", PromptDigest.sha256("unknown"));
        }
        String material = context.chatMemoryId() == null
                ? userMaterial(context)
                : "memory:" + context.chatMemoryId();
        if (material.isBlank()) {
            material = "invocation:" + context.invocationId();
        }
        return new PromptRolloutSubject(
                context.interfaceName(),
                context.methodName(),
                PromptDigest.sha256(material)
        );
    }

    public String bindingKey() {
        return interfaceName + "#" + methodName;
    }

    private static String userMaterial(InvocationContext context) {
        try {
            UserMessage userMessage = context.userMessage();
            if (userMessage != null && userMessage.hasSingleText()) {
                return "message:" + userMessage.singleText();
            }
        } catch (RuntimeException ignored) {
            // Fall back to invocation arguments when no rendered user message exists.
        }
        List<Object> arguments = context.methodArguments();
        return arguments == null ? "" : "arguments:" + arguments;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
