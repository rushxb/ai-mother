package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeProbe;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PreviewRuntimeDiagnosticToolTest {

    private DevServerManager devServerManager;
    private PreviewRuntimeDiagnosticTool tool;

    @BeforeEach
    void setUp() {
        devServerManager = mock(DevServerManager.class);
        tool = new PreviewRuntimeDiagnosticTool(
                mock(VueProjectBuilder.class),
                devServerManager,
                ToolPathSupportTestFixture.forApp(11L),
                mock(BrowserRuntimeProbe.class),
                8123,
                "/api"
        );
    }

    @Test
    void devServerTargetMustUseUnifiedManagerPort() {
        when(devServerManager.getPort(11L)).thenReturn(5180);

        String url = tool.resolveTargetUrl("diagnoseDevServer", null, null, 11L);

        assertEquals("http://127.0.0.1:5180/", url);
    }

    @Test
    void missingDevServerMustFailWithoutFallingBackToAnotherRuntime() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> tool.resolveTargetUrl("diagnoseDevServer", null, null, 11L)
        );

        assertTrue(exception.getMessage().contains("没有运行中的 Dev Server"));
    }

    @Test
    void explicitTargetMustRejectNonLoopbackAddress() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> tool.resolveTargetUrl(
                        "diagnoseDevServer",
                        "http://169.254.169.254/latest/meta-data/",
                        null,
                        11L
                )
        );

        assertTrue(exception.getMessage().contains("仅允许访问本机回环地址"));
    }

    @Test
    void unsupportedActionMustFailEvenWhenExplicitTargetIsProvided() {
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.resolveTargetUrl("unknown", "http://127.0.0.1:8123/", null, 11L)
        );
    }

    @Test
    void explicitLocalhostHttpTargetMustBeAccepted() {
        String url = tool.resolveTargetUrl(
                "diagnoseBuildPreview",
                "http://localhost:8123/api/static/vue_project_11/dist/",
                null,
                11L
        );

        assertEquals("http://localhost:8123/api/static/vue_project_11/dist/", url);
    }

    @Test
    void unexpectedDiagnosticFailureMustNotExposeInternalDetails() {
        when(devServerManager.getPort(11L))
                .thenThrow(new IllegalStateException("provider-api-key=secret-value"));

        String result = tool.diagnosePreviewRuntime(
                "diagnoseDevServer",
                null,
                null,
                2,
                11L
        );

        assertEquals("运行时诊断失败，请稍后重试", result);
        assertFalse(result.contains("secret-value"));
    }
}
