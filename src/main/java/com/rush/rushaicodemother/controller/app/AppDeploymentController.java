package com.rush.rushaicodemother.controller.app;

import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.model.dto.app.AppDeployRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.service.AppService;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 应用部署控制器。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app")
public class AppDeploymentController {

    private final AppService appService;
    private final UserService userService;

    /**
 * 返回部署应用。
 *
 * @param request 请求参数
 * @param servletRequest 当前 HTTP 请求
 * @return 统一封装的接口响应
 */
    @PostMapping("/deploy")
    public BaseResponse<String> deployApp(@Valid @RequestBody AppDeployRequest request,
                                          HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        return ResultUtils.success(appService.deployApp(request.getAppId(), loginUser));
    }

    /**
 * 同步并返回应用部署。
 *
 * @param request 请求参数
 * @param servletRequest 当前 HTTP 请求
 * @return 统一封装的接口响应
 */
    @PostMapping("/deploy/sync")
    public BaseResponse<String> syncAppDeployment(@Valid @RequestBody AppDeployRequest request,
                                                  HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        return ResultUtils.success(appService.syncAppDeployment(request.getAppId(), loginUser));
    }
}