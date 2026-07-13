package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.dto.app.AppCodeFileSaveRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.AppCodeFileContentVO;
import com.rush.rushaicodemother.model.vo.AppCodeFileTreeVO;
import com.rush.rushaicodemother.service.workspace.AppCodeWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AppServiceImplWorkspaceDelegationTest {

    private AppServiceImpl appService;
    private AppCodeWorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        AppServiceImplTestFixture fixture = new AppServiceImplTestFixture();
        appService = spy(fixture.createService());
        workspaceService = fixture.workspaceService();
    }

    @Test
    void shouldDelegateFileListingAfterOwnershipValidation() {
        App app = app(11L, 21L);
        User owner = user(21L);
        List<AppCodeFileTreeVO> expectedNodes = List.of(new AppCodeFileTreeVO());
        doReturn(app).when(appService).getById(11L);
        doReturn(expectedNodes).when(workspaceService).listFiles(app);

        List<AppCodeFileTreeVO> actualNodes = appService.listAppCodeFiles(11L, owner);

        assertSame(expectedNodes, actualNodes);
        verify(workspaceService).listFiles(same(app));
    }

    @Test
    void shouldDelegateFileReadAfterOwnershipValidation() {
        App app = app(12L, 22L);
        User owner = user(22L);
        AppCodeFileContentVO expectedContent = new AppCodeFileContentVO();
        doReturn(app).when(appService).getById(12L);
        doReturn(expectedContent).when(workspaceService).readFile(app, "src/App.vue");

        AppCodeFileContentVO actualContent = appService.getAppCodeFileContent(12L, "src/App.vue", owner);

        assertSame(expectedContent, actualContent);
        verify(workspaceService).readFile(same(app), org.mockito.ArgumentMatchers.eq("src/App.vue"));
    }

    @Test
    void shouldDelegateFileSaveAfterOwnershipValidation() {
        App app = app(13L, 23L);
        User owner = user(23L);
        AppCodeFileSaveRequest request = new AppCodeFileSaveRequest();
        request.setAppId(13L);
        request.setFilePath("src/App.vue");
        request.setContent("<template>new</template>");
        doReturn(app).when(appService).getById(13L);

        Boolean saved = appService.saveAppCodeFile(request, owner);

        assertTrue(saved);
        verify(workspaceService).saveFile(
                same(app),
                org.mockito.ArgumentMatchers.eq("src/App.vue"),
                org.mockito.ArgumentMatchers.eq("<template>new</template>")
        );
    }

    @Test
    void shouldRejectNonOwnerBeforeWorkspaceAccess() {
        App app = app(14L, 24L);
        User otherUser = user(25L);
        doReturn(app).when(appService).getById(14L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> appService.listAppCodeFiles(14L, otherUser));

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
        verifyNoInteractions(workspaceService);
    }

    @Test
    void shouldRejectNullSaveRequestBeforeWorkspaceAccess() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> appService.saveAppCodeFile(null, user(26L)));

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        verifyNoInteractions(workspaceService);
    }

    private App app(Long appId, Long userId) {
        App app = new App();
        app.setId(appId);
        app.setUserId(userId);
        return app;
    }

    private User user(Long userId) {
        User user = new User();
        user.setId(userId);
        return user;
    }
}
