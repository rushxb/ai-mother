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

    @PostMapping("/deploy")
    public BaseResponse<String> deployApp(@Valid @RequestBody AppDeployRequest request,
                                          HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        return ResultUtils.success(appService.deployApp(request.getAppId(), loginUser));
    }

    @PostMapping("/deploy/sync")
    public BaseResponse<String> syncAppDeployment(@Valid @RequestBody AppDeployRequest request,
                                                  HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        return ResultUtils.success(appService.syncAppDeployment(request.getAppId(), loginUser));
    }
}