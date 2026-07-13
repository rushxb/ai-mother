package com.rush.rushaicodemother.application.app;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.AppDatabaseResource;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.vo.AppDatabaseResourceVO;
import com.rush.rushaicodemother.model.vo.AppVO;
import com.rush.rushaicodemother.model.vo.UserVO;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import com.rush.rushaicodemother.service.UserService;
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

    private UserService userService;
    private AppDatabaseResourceService appDatabaseResourceService;
    private AppViewAssembler assembler;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        appDatabaseResourceService = mock(AppDatabaseResourceService.class);
        assembler = new AppViewAssembler(userService, appDatabaseResourceService);
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
        verify(appDatabaseResourceService).getByAppId(1L);
        verifyNoInteractions(userService);
    }

    @Test
    void singleViewAssemblyShouldSupportTransientAppWithoutIdentifiers() {
        App transientApp = new App();

        AppVO result = assembler.toView(transientApp);

        assertNull(result.getId());
        assertNull(result.getUser());
        assertNull(result.getDatabaseResource());
        verifyNoInteractions(userService, appDatabaseResourceService);
    }

    @Test
    void listViewAssemblyShouldBatchLoadUsersAndDatabaseResources() {
        App firstApp = app(1L, 10L);
        App secondApp = app(2L, 20L);
        User firstUser = user(10L);
        User secondUser = user(20L);
        UserVO firstUserVO = userVO(10L);
        UserVO secondUserVO = userVO(20L);
        AppDatabaseResource firstResource = databaseResource(101L, 1L);
        AppDatabaseResource secondResource = databaseResource(102L, 2L);
        AppDatabaseResourceVO firstResourceVO = databaseResourceVO(101L, 1L);
        AppDatabaseResourceVO secondResourceVO = databaseResourceVO(102L, 2L);

        when(userService.listByIds(anyCollection())).thenReturn(List.of(firstUser, secondUser));
        when(userService.getUserVO(firstUser)).thenReturn(firstUserVO);
        when(userService.getUserVO(secondUser)).thenReturn(secondUserVO);
        when(appDatabaseResourceService.getActiveResourceMapByAppIds(anyCollection()))
                .thenReturn(Map.of(1L, firstResource, 2L, secondResource));
        when(appDatabaseResourceService.getResourceVO(firstResource)).thenReturn(firstResourceVO);
        when(appDatabaseResourceService.getResourceVO(secondResource)).thenReturn(secondResourceVO);

        List<AppVO> results = assembler.toViewList(List.of(firstApp, secondApp));

        assertEquals(2, results.size());
        assertSame(firstUserVO, results.get(0).getUser());
        assertSame(secondUserVO, results.get(1).getUser());
        assertSame(firstResourceVO, results.get(0).getDatabaseResource());
        assertSame(secondResourceVO, results.get(1).getDatabaseResource());
        verify(userService, times(1)).listByIds(argThat(ids -> ids.containsAll(List.of(10L, 20L))));
        verify(userService, never()).getById(any());
        verify(appDatabaseResourceService, times(1))
                .getActiveResourceMapByAppIds(argThat(ids -> ids.containsAll(List.of(1L, 2L))));
        verify(appDatabaseResourceService, never()).getByAppId(any());
    }

    @Test
    void listViewAssemblyShouldSupportAppWithoutUserAssociation() {
        App appWithoutUser = app(1L, null);
        when(appDatabaseResourceService.getActiveResourceMapByAppIds(anyCollection()))
                .thenReturn(Map.of());

        List<AppVO> results = assembler.toViewList(List.of(appWithoutUser));

        assertEquals(1, results.size());
        assertNull(results.getFirst().getUser());
        assertNull(results.getFirst().getDatabaseResource());
        verifyNoInteractions(userService);
    }

    @Test
    void listViewAssemblyShouldSupportTransientAppWithoutIdentifiers() {
        App transientApp = app(null, null);
        when(appDatabaseResourceService.getActiveResourceMapByAppIds(anyCollection()))
                .thenReturn(Map.of());

        List<AppVO> results = assembler.toViewList(List.of(transientApp));

        assertEquals(1, results.size());
        assertNull(results.getFirst().getId());
        assertNull(results.getFirst().getUser());
        assertNull(results.getFirst().getDatabaseResource());
        verifyNoInteractions(userService);
    }

    private App app(Long id, Long userId) {
        App app = new App();
        app.setId(id);
        app.setUserId(userId);
        app.setAppName("app-" + id);
        return app;
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private UserVO userVO(Long id) {
        UserVO userVO = new UserVO();
        userVO.setId(id);
        return userVO;
    }

    private AppDatabaseResource databaseResource(Long id, Long appId) {
        AppDatabaseResource resource = new AppDatabaseResource();
        resource.setId(id);
        resource.setAppId(appId);
        return resource;
    }

    private AppDatabaseResourceVO databaseResourceVO(Long id, Long appId) {
        AppDatabaseResourceVO resourceVO = new AppDatabaseResourceVO();
        resourceVO.setId(id);
        resourceVO.setAppId(appId);
        return resourceVO;
    }
}
