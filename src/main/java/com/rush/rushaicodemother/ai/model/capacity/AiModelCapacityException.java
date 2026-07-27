package com.rush.rushaicodemother.ai.model.capacity;

/** 安全、低细节的信号，允许请求级模型故障转移，而不会泄漏提示数据。 */
public final class AiModelCapacityException extends RuntimeException {

    private final String gate;

    private AiModelCapacityException(String message, String gate, Throwable cause) {
        super(message, cause, false, false);
        this.gate = gate;
    }

    public static AiModelCapacityException rejected(String gate) {
        return new AiModelCapacityException(
                "AI model rate limit capacity exhausted",
                normalizeGate(gate),
                null
        );
    }

    public static AiModelCapacityException unavailable(Throwable cause) {
        return new AiModelCapacityException(
                "AI model capacity service unavailable",
                "infrastructure",
                cause
        );
    }

    public String gate() {
        return gate;
    }

    private static String normalizeGate(String gate) {
        if (gate == null || !gate.matches("[a-z_]{1,32}")) {
            return "unknown";
        }
        return gate;
    }
}
