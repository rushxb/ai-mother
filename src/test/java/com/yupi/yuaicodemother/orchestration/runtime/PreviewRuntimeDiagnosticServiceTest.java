package com.yupi.yuaicodemother.orchestration.runtime;

import com.yupi.yuaicodemother.orchestration.artifact.RuntimeDiagnosticResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewRuntimeDiagnosticServiceTest {

    private final PreviewRuntimeDiagnosticService service = new PreviewRuntimeDiagnosticService();

    @Test
    void analyzeShouldFailWhiteScreenReport() {
        RuntimeDiagnosticResult result = service.analyze("http://localhost", "正文长度: 0\n挂载节点存在: 是\n挂载节点存在但没有渲染出可见内容");

        assertFalse(result.passed());
        assertTrue(result.whiteScreenDetected());
    }

    @Test
    void analyzeShouldPassCleanReport() {
        RuntimeDiagnosticResult result = service.analyze("http://localhost", "正文长度: 100\n诊断结论:\n- 未发现明显的浏览器级运行时异常");

        assertTrue(result.passed());
    }
}
