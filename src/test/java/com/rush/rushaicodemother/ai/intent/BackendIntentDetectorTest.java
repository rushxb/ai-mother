package com.rush.rushaicodemother.ai.intent;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackendIntentDetectorTest {

    private final BackendIntentDetector detector = new BackendIntentDetector();

    @Test
    void shouldDetectRawApiAndFrontendAsAmbiguous() {
        assertEquals(
                BackendIntentDetector.BackendIntentResult.IntentLevel.AMBIGUOUS,
                detector.detectIntent("创建 Vue 页面并连接 API").level()
        );
    }

    @Test
    void shouldNotTreatBuildSubstringAsUiIntent() {
        assertEquals(
                BackendIntentDetector.BackendIntentResult.IntentLevel.EXPLICIT_BACKEND,
                detector.detectIntent("build a Go backend service").level()
        );
    }

    @Test
    void shouldRequireFrontendAndBackendSignalsForGenericCompleteApp() {
        assertEquals(
                BackendIntentDetector.BackendIntentResult.IntentLevel.NONE,
                detector.detectIntent("构建一款完整应用").level()
        );
        assertEquals(
                BackendIntentDetector.BackendIntentResult.IntentLevel.FULLSTACK,
                detector.detectIntent("构建完整应用，包含 Vue 页面和 Go 后端").level()
        );
    }

    @Test
    void shouldConstrainAmbiguousAiResultToVueOrFullstack() {
        BackendIntentDetector.BackendIntentResult ambiguous =
                BackendIntentDetector.BackendIntentResult.ambiguous();

        assertEquals(
                CodeGenTypeEnum.VUE_PROJECT,
                detector.constrainCodeGenType(ambiguous, CodeGenTypeEnum.HTML)
        );
        assertEquals(
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                detector.constrainCodeGenType(ambiguous, CodeGenTypeEnum.FULL_STACK_PROJECT)
        );
    }
}
