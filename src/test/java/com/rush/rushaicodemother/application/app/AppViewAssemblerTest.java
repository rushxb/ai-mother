package com.rush.rushaicodemother.application.app;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.vo.AppDatabaseResourceVO;
import com.rush.rushaicodemother.model.vo.AppVO;
import com.rush.rushaicodemother.model.vo.UserVO;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import com.rush.rushaicodemother.service.user.UserDirectoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppViewAssemblerTest {

    private UserDirectoryService userDirectoryService;
    private AppDatabaseResourceService appDatabaseResourceService;
    private AppViewAssembler assembler;

    @BeforeEach
    void setUp() {
        userDirectoryService = mock(UserDirectoryService.class);
        appDatabaseResourceService = mock(AppDatabaseResourceService.class);
        assembler = new AppViewAssembler(userDirectoryService, appDatabaseResourceService);
    }

    @Test
    void singleViewAssemblyShouldNotMutateMissingDevServerPort() {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());

        AppVO result = assembler.toView(app);

        assertEquals(1L, result.getId());
        assertNull(result.getDevServerPort());
        assertNull(app.getDevServerPort());
        verify(appDatabaseResourceService).findActiveResourceView(1L);
        verifyNoInteractions(userDirectoryService);
    }

    @Test
    void singleViewAssemblyShouldSupportTransientAppWithoutIdentifiers() {
        App transientApp = new App();

        AppVO result = assembler.toView(transientApp);

        assertNull(result.getId());
        assertNull(result.getUser());
        assertNull(result.getDatabaseResource());
        verifyNoInteractions(userDirectoryService, appDatabaseResourceService);
    }

    @Test
    void listViewAssemblyShouldBatchLoadUsersAndDatabaseResourceViews() {
        App firstApp = app(1L, 10L);
        App secondApp = app(2L, 20L);
        UserVO firstUserVO = userVO(10L);
        UserVO secondUserVO = userVO(20L);
        AppDatabaseResourceVO firstResourceVO = databaseResourceVO(101L, 1L);
        AppDatabaseResourceVO secondResourceVO = databaseResourceVO(102L, 2L);

        when(userDirectoryService.findActiveUserViews(anyCollection()))
                .thenReturn(Map.of(10L, firstUserVO, 20L, secondUserVO));
        when(appDatabaseResourceService.findActiveResourceViews(anyCollection()))
                .thenReturn(Map.of(1L, firstResourceVO, 2L, secondResourceVO));

        List<AppVO> results = assembler.toViewList(List.of(firstApp, secondApp));

        assertEquals(2, results.size());
        assertSame(firstUserVO, results.get(0).getUser());
        assertSame(secondUserVO, results.get(1).getUser());
        assertSame(firstResourceVO, results.get(0).getDatabaseResource());
        assertSame(secondResourceVO, results.get(1).getDatabaseResource());
        verify(userDirectoryService, times(1))
                .findActiveUserViews(argThat(ids -> ids.containsAll(List.of(10L, 20L))));
        verify(userDirectoryService, never()).findActiveUserView(any());
        verify(appDatabaseResourceService, times(1))
                .findActiveResourceViews(argThat(ids -> ids.containsAll(List.of(1L, 2L))));
        verify(appDatabaseResourceService, never()).findActiveResourceView(any());
    }

    @Test
    void listViewAssemblyShouldSupportAppWithoutUserAssociation() {
        App appWithoutUser = app(1L, null);
        when(appDatabaseResourceService.findActiveResourceViews(anyCollection())).thenReturn(Map.of());

        List<AppVO> results = assembler.toViewList(List.of(appWithoutUser));

        assertEquals(1, results.size());
        assertNull(results.getFirst().getUser());
        assertNull(results.getFirst().getDatabaseResource());
        verifyNoInteractions(userDirectoryService);
    }

    @Test
    void listViewAssemblyShouldSupportTransientAppWithoutIdentifiers() {
        App transientApp = app(null, null);
        when(appDatabaseResourceService.findActiveResourceViews(anyCollection())).thenReturn(Map.of());

        List<AppVO> results = assembler.toViewList(List.of(transientApp));

        assertEquals(1, results.size());
        assertNull(results.getFirst().getId());
        assertNull(results.getFirst().getUser());
        assertNull(results.getFirst().getDatabaseResource());
        verifyNoInteractions(userDirectoryService);
    }

    private App app(Long id, Long userId) {
        App app = new App();
        app.setId(id);
        app.setUserId(userId);
        app.setAppName("app-" + id);
        return app;
    }

    private UserVO userVO(Long id) {
        UserVO userVO = new UserVO();
        userVO.setId(id);
        return userVO;
    }

    private AppDatabaseResourceVO databaseResourceVO(Long id, Long appId) {
        AppDatabaseResourceVO resourceVO = new AppDatabaseResourceVO();
        resourceVO.setId(id);
        resourceVO.setAppId(appId);
        return resourceVO;
    }
}
