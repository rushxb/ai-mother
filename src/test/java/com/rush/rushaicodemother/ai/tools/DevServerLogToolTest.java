package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.devserver.DevServerAppTargetLookup;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import com.rush.rushaicodemother.service.devserver.DevServerStartResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DevServerLogToolTest {

    private DevServerAppTargetLookup appTargetLookup;
    private DevServerManager devServerManager;
    private DevServerLogTool tool;

    @BeforeEach
    void setUp() {
        appTargetLookup = mock(DevServerAppTargetLookup.class);
        devServerManager = mock(DevServerManager.class);
        tool = new DevServerLogTool(appTargetLookup, devServerManager);
    }

    @Test
    void startMustDelegateToUnifiedManagerWithApplicationOwner() {
        App app = app(11L, 7L);
        when(appTargetLookup.requireTarget(11L)).thenReturn(app);
        when(devServerManager.startDevServer(app, 7L))
                .thenReturn(new DevServerStartResult(5180, true));
        when(devServerManager.getPort(11L)).thenReturn(5180);
        when(devServerManager.getRecentOutputLines(11L, 80))
                .thenReturn(List.of("VITE ready", "Local: http://127.0.0.1:5180/"));

        String result = tool.manageDevServer("startDevServer", 11L);

        assertTrue(result.contains("状态: 运行中"));
        assertTrue(result.contains("http://127.0.0.1:5180/"));
        assertTrue(result.contains("VITE ready"));
        verify(devServerManager).startDevServer(app, 7L);
    }

    @Test
    void restartMustStopBeforeStartingThroughTheSameManager() {
        App app = app(11L, 7L);
        when(appTargetLookup.requireTarget(11L)).thenReturn(app);
        when(devServerManager.startDevServer(app, 7L))
                .thenReturn(new DevServerStartResult(5180, true));
        when(devServerManager.getPort(11L)).thenReturn(5180);
        when(devServerManager.getRecentOutputLines(11L, 80)).thenReturn(List.of());

        tool.manageDevServer("restartDevServer", 11L);

        InOrder order = inOrder(devServerManager);
        order.verify(devServerManager).stopDevServer(11L);
        order.verify(devServerManager).startDevServer(app, 7L);
    }

    @Test
    void invalidApplicationIdMustFailBeforeAccessingDependencies() {
        String result = tool.manageDevServer("startDevServer", 0L);

        assertTrue(result.contains("应用 ID 必须大于 0"));
        verifyNoInteractions(appTargetLookup, devServerManager);
    }

    @Test
    void missingApplicationMustReturnNotFoundWithoutStartingServer() {
        when(appTargetLookup.requireTarget(11L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在"));

        String result = tool.manageDevServer("startDevServer", 11L);

        assertTrue(result.contains("应用不存在"));
        verifyNoInteractions(devServerManager);
    }

    @Test
    void invalidApplicationOwnerMustFailBeforeStartingServer() {
        when(appTargetLookup.requireTarget(11L)).thenReturn(app(11L, null));

        String result = tool.manageDevServer("startDevServer", 11L);

        assertTrue(result.contains("应用所有者信息无效"));
        verifyNoInteractions(devServerManager);
    }

    private App app(Long appId, Long userId) {
        App app = new App();
        app.setId(appId);
        app.setUserId(userId);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        return app;
    }
}
