package com.rush.rushaicodemother.controller.app;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.service.devserver.DevServerInternalRequestSigner;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewRoutingService;
import com.rush.rushaicodemother.service.devserver.DevServerProxyService;
import com.rush.rushaicodemother.service.devserver.VerifiedDevServerInternalRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalDevServerProxyControllerSecurityTest {

    private DevServerInternalRequestSigner signer;
    private DevServerPreviewRoutingService routingService;
    private DevServerProxyService proxyService;
    private InternalDevServerProxyController controller;

    @BeforeEach
    void setUp() {
        signer = mock(DevServerInternalRequestSigner.class);
        routingService = mock(DevServerPreviewRoutingService.class);
        proxyService = mock(DevServerProxyService.class);
        controller = new InternalDevServerProxyController(signer, routingService, proxyService);
    }

    @Test
    void invalidSignatureMustBeRejectedBeforeOwnerOrProcessLookup() {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(signer.verify(request)).thenThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR));

        assertThrows(BusinessException.class, () -> controller.proxy(21L, request, response));

        verify(routingService, never()).requireLocalRunningPort(21L);
        verify(proxyService, never()).proxyLocal(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void signedRequestMustStillPassDurableOwnerFencing() {
        MockHttpServletRequest request = request();
        request.setQueryString("mode=edit");
        MockHttpServletResponse response = new MockHttpServletResponse();
        VerifiedDevServerInternalRequest verified = new VerifiedDevServerInternalRequest(
                "preview-node-a",
                "nonce-00000001",
                "0".repeat(64)
        );
        when(signer.verify(request)).thenReturn(verified);
        when(routingService.requireLocalRunningPort(21L)).thenReturn(5180);

        controller.proxy(21L, request, response);

        var order = inOrder(signer, routingService, proxyService);
        order.verify(signer).verify(request);
        order.verify(routingService).requireLocalRunningPort(21L);
        order.verify(proxyService).proxyLocal(
                21L,
                5180,
                "/src/main.ts",
                "mode=edit",
                request,
                response,
                verified
        );
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/internal/dev-server/proxy/21/src/main.ts"
        );
        request.setAttribute(
                HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE,
                "/internal/dev-server/proxy/21/src/main.ts"
        );
        return request;
    }
}
