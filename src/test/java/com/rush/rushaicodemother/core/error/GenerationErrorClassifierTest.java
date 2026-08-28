package com.rush.rushaicodemother.core.error;

import com.rush.rushaicodemother.orchestration.GenerationStoppedException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationErrorClassifierTest {

    @Test
    void shouldClassifyInsufficientBalanceAsModelQuota() {
        GenerationErrorClassifier.GenerationError error = GenerationErrorClassifier.classify(
                "{\"error\":{\"message\":\"Insufficient Balance\",\"type\":\"unknown_error\"}}"
        );

        assertEquals(GenerationErrorClassifier.CATEGORY_MODEL_QUOTA, error.category());
        assertFalse(error.recoverable());
    }

    @Test
    void shouldKeepBuildErrorsRecoverable() {
        GenerationErrorClassifier.GenerationError error = GenerationErrorClassifier.classify(
                "pnpm run build 执行失败: failed to resolve import"
        );

        assertEquals(GenerationErrorClassifier.CATEGORY_DEPENDENCY, error.category());
        assertTrue(error.recoverable());
    }

    @Test
    void shouldNotExposeRawThrowableMessage() {
        GenerationErrorClassifier.GenerationError error = GenerationErrorClassifier.classify(
                new IllegalStateException("provider-api-key=secret-value")
        );

        assertEquals(GenerationErrorClassifier.CATEGORY_RUNTIME, error.category());
        assertEquals("代码生成失败，请稍后重试。", error.message());
    }

    @Test
    void agentLoopMustBeClassifiedAsNonRecoverableWithoutExposingToolArguments() {
        GenerationErrorClassifier.GenerationError error = GenerationErrorClassifier.classify(
                new IllegalStateException("wrapped",
                        new GenerationAgentLoopException("no_progress", "readFile"))
        );

        assertEquals(GenerationErrorClassifier.CATEGORY_AGENT_LOOP, error.category());
        assertEquals("AI 生成连续重复相同操作且未取得新进展，已提前停止。", error.message());
        assertFalse(error.recoverable());
    }

    @Test
    void cancellationMustBeClassifiedAsNeutralNonRecoverableControlFlow() {
        GenerationErrorClassifier.GenerationError typed = GenerationErrorClassifier.classify(
                new IllegalStateException("wrapped", new CancellationException("cancelled")));
        GenerationErrorClassifier.GenerationError platformCancellation = GenerationErrorClassifier.classify(
                new IllegalStateException("wrapped", new GenerationExecutionCancelledException("user_requested")));
        GenerationErrorClassifier.GenerationError sessionStop = GenerationErrorClassifier.classify(
                new IllegalStateException("wrapped", new GenerationStoppedException()));
        GenerationErrorClassifier.GenerationError providerSignal =
                GenerationErrorClassifier.classify("stream canceled");

        assertEquals(GenerationErrorClassifier.CATEGORY_MODEL_CANCELLED, typed.category());
        assertEquals(GenerationErrorClassifier.CATEGORY_MODEL_CANCELLED, platformCancellation.category());
        assertEquals(GenerationErrorClassifier.CATEGORY_MODEL_CANCELLED, sessionStop.category());
        assertEquals(GenerationErrorClassifier.CATEGORY_MODEL_CANCELLED, providerSignal.category());
        assertFalse(typed.recoverable());
        assertFalse(platformCancellation.recoverable());
        assertFalse(sessionStop.recoverable());
        assertFalse(providerSignal.recoverable());
    }

    @Test
    void workspaceOutcomeUnknownMustRequireManualReconciliationWithoutBlindRetry() {
        GenerationErrorClassifier.GenerationError typed = GenerationErrorClassifier.classify(
                new IllegalStateException("wrapped",
                        new IllegalStateException("physical outcome unknown")));
        GenerationErrorClassifier.GenerationError durableReason =
                GenerationErrorClassifier.classify("rollback_restore_outcome_unknown");

        assertEquals(GenerationErrorClassifier.CATEGORY_WORKSPACE_RESULT_UNKNOWN, typed.category());
        assertEquals(GenerationErrorClassifier.CATEGORY_WORKSPACE_RESULT_UNKNOWN,
                durableReason.category());
        assertEquals("工作区操作结果无法确认，请先刷新并核对当前文件状态；确认前请勿重试或回滚。",
                typed.message());
        assertFalse(typed.recoverable());
        assertFalse(durableReason.recoverable());
    }
}
