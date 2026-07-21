package com.rush.rushaicodemother.controller.app;

import com.rush.rushaicodemother.application.app.AppDevServerApplicationService;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.DevServerStatusVO;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.devserver.DevServerProxyService;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewPaths;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

/** 应用 Dev Server 生命周期和受限代理控制器。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app")
public class AppDevServerController {

    private static final String PROXY_ROUTE_PREFIX = DevServerPreviewPaths.PUBLIC_PROXY_PREFIX;

    private final AppDevServerApplicationService devServerApplicationService;
    private final DevServerProxyService devServerProxyService;
    private final UserService userService;

    @PostMapping("/dev-server/start")
    public BaseResponse<DevServerStatusVO> startDevServer(@RequestParam @Positive Long appId,
                                                          HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        return ResultUtils.success(devServerApplicationService.start(appId, loginUser));
    }

    @PostMapping("/dev-server/stop")
    public BaseResponse<Boolean> stopDevServer(@RequestParam @Positive Long appId,
                                               HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        devServerApplicationService.stop(appId, loginUser);
        return ResultUtils.success(true);
    }

    @GetMapping("/dev-server/status")
    public BaseResponse<DevServerStatusVO> getDevServerStatus(@RequestParam @Positive Long appId,
                                                              HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        return ResultUtils.success(devServerApplicationService.getStatus(appId, loginUser));
    }

    @RequestMapping("/dev-server/proxy/{appId}/**")
    public void proxyDevServer(@PathVariable @Positive Long appId,
                               HttpServletRequest request,
                               HttpServletResponse response) {
        User loginUser = userService.getLoginUser(request);
        DevServerPreviewRoute route = devServerApplicationService.requireProxyRoute(appId, loginUser);
        String targetPath = extractTargetPath(appId, request);
        devServerProxyService.proxy(route, targetPath, request.getQueryString(), request, response);
    }

    private String extractTargetPath(Long appId, HttpServletRequest request) {
        Object mappedPathAttribute = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        ThrowUtils.throwIf(!(mappedPathAttribute instanceof String),
                ErrorCode.PARAMS_ERROR, "代理路径非法");
        String mappedPath = (String) mappedPathAttribute;
        String expectedPrefix = PROXY_ROUTE_PREFIX + appId;
        ThrowUtils.throwIf(!mappedPath.startsWith(expectedPrefix),
                ErrorCode.PARAMS_ERROR, "代理路径非法");
        String targetPath = mappedPath.substring(expectedPrefix.length());
        return targetPath.isEmpty() ? "/" : targetPath;
    }
}
