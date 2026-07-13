package com.rush.rushaicodemother.controller.app;

import com.rush.rushaicodemother.application.app.AppDevServerApplicationService;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.devserver.DevServerProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppDevServerControllerSecurityTest {

    private AppDevServerApplicationService applicationService;
    private DevServerProxyService proxyService;
    private UserService userService;
    private AppDevServerController controller;

    @BeforeEach
    void setUp() {
        applicationService = mock(AppDevServerApplicationService.class);
        proxyService = mock(DevServerProxyService.class);
        userService = mock(UserService.class);
        controller = new AppDevServerController(applicationService, proxyService, userService);
    }

    @Test
    void mustNotProxyWhenApplicationServiceRejectsAccess() {
        MockHttpServletRequest request = requestForApp(21L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        User loginUser = User.builder().id(1L).build();
        when(userService.getLoginUser(request)).thenReturn(loginUser);
        when(applicationService.requireProxyPort(21L, loginUser))
                .thenThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR));

        assertThrows(BusinessException.class, () -> controller.proxyDevServer(21L, request, response));

        verify(proxyService, never()).proxy(
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void authorizedRequestMustUseRestrictedProxyService() {
        MockHttpServletRequest request = requestForApp(21L);
        request.setQueryString("mode=edit");
        MockHttpServletResponse response = new MockHttpServletResponse();
        User loginUser = User.builder().id(1L).build();
        when(userService.getLoginUser(request)).thenReturn(loginUser);
        when(applicationService.requireProxyPort(21L, loginUser)).thenReturn(5173);

        controller.proxyDevServer(21L, request, response);

        verify(proxyService).proxy(5173, "/src/main.ts", "mode=edit", request, response);
    }

    private MockHttpServletRequest requestForApp(Long appId) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/app/dev-server/proxy/" + appId + "/src/main.ts"
        );
        request.setAttribute(
                HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE,
                "/app/dev-server/proxy/" + appId + "/src/main.ts"
        );
        return request;
    }
}