package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.service.deployment.AppDeploymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppServiceImplDeploymentDelegationTest {

    private AppServiceImpl appService;
    private AppDeploymentService deploymentService;

    @BeforeEach
    void setUp() {
        AppServiceImplTestFixture fixture = new AppServiceImplTestFixture();
        appService = spy(fixture.createService());
        deploymentService = fixture.deploymentService();
    }

    @Test
    void shouldDelegateDeploymentAfterOwnershipValidation() {
        App app = app(11L, 21L);
        User owner = user(21L);
        doReturn(app).when(appService).getById(11L);
        when(deploymentService.deploy(app)).thenReturn("https://deploy.example.com/key/");

        String deployUrl = appService.deployApp(11L, owner);

        assertEquals("https://deploy.example.com/key/", deployUrl);
        verify(deploymentService).deploy(same(app));
    }

    @Test
    void shouldDelegateSynchronizationAfterOwnershipValidation() {
        App app = app(12L, 22L);
        User owner = user(22L);
        doReturn(app).when(appService).getById(12L);
        when(deploymentService.synchronize(app)).thenReturn("https://deploy.example.com/key/");

        String deployUrl = appService.syncAppDeployment(12L, owner);

        assertEquals("https://deploy.example.com/key/", deployUrl);
        verify(deploymentService).synchronize(same(app));
    }

    @Test
    void shouldRejectNonOwnerBeforeDeploymentModuleAccess() {
        App app = app(13L, 23L);
        doReturn(app).when(appService).getById(13L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> appService.deployApp(13L, user(24L)));

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
        verifyNoInteractions(deploymentService);
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
