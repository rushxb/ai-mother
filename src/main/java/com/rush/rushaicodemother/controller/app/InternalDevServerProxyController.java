package com.rush.rushaicodemother.controller.app;

import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.service.devserver.DevServerInternalRequestSigner;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewRoutingService;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewPaths;
import com.rush.rushaicodemother.service.devserver.DevServerProxyService;
import com.rush.rushaicodemother.service.devserver.VerifiedDevServerInternalRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

/** 签名的节点到节点跳；从不接受浏览器会话作为授权。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/dev-server")
public class InternalDevServerProxyController {

    private static final String PROXY_ROUTE_PREFIX = DevServerPreviewPaths.INTERNAL_PROXY_PREFIX;

    private final DevServerInternalRequestSigner requestSigner;
    private final DevServerPreviewRoutingService previewRoutingService;
    private final DevServerProxyService proxyService;

    /**
 * 处理代理。
 *
 * @param appId 应用编号
 * @param request 请求参数
 * @param response 响应对象
 */
    @RequestMapping("/proxy/{appId}/**")
    public void proxy(@PathVariable @Positive Long appId,
                      HttpServletRequest request,
                      HttpServletResponse response) {
        VerifiedDevServerInternalRequest verifiedRequest = requestSigner.verify(request);
        int localPort = previewRoutingService.requireLocalRunningPort(appId);
        String targetPath = extractTargetPath(appId, request);
        proxyService.proxyLocal(
                appId,
                localPort,
                targetPath,
                request.getQueryString(),
                request,
                response,
                verifiedRequest
        );
    }

    private String extractTargetPath(Long appId, HttpServletRequest request) {
        Object mappedPathAttribute = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        ThrowUtils.throwIf(!(mappedPathAttribute instanceof String),
                ErrorCode.PARAMS_ERROR, "Internal Preview path is invalid");
        String mappedPath = (String) mappedPathAttribute;
        String expectedPrefix = PROXY_ROUTE_PREFIX + appId;
        ThrowUtils.throwIf(!mappedPath.startsWith(expectedPrefix),
                ErrorCode.PARAMS_ERROR, "Internal Preview path is invalid");
        String targetPath = mappedPath.substring(expectedPrefix.length());
        return targetPath.isEmpty() ? "/" : targetPath;
    }
}
