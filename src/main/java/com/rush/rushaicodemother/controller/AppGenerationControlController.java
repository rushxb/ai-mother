package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.model.dto.generation.AppGenerationControlUpdateRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.AppGenerationControlVO;
import com.rush.rushaicodemother.orchestration.governance.app.AppGenerationControlService;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 应用管理员生成控制接口。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/generation/apps")
public class AppGenerationControlController {

    private final UserService userService;
    private final AppGenerationControlService controlService;

    @GetMapping("/{appId}/control")
    public BaseResponse<AppGenerationControlVO> get(
            @PathVariable @Positive Long appId,
            HttpServletRequest request) {
        User actor = userService.getLoginUser(request);
        return ResultUtils.success(AppGenerationControlVO.from(controlService.get(appId, actor)));
    }

    @PutMapping("/{appId}/control")
    public BaseResponse<AppGenerationControlVO> update(
            @PathVariable @Positive Long appId,
            @RequestBody @Valid AppGenerationControlUpdateRequest updateRequest,
            HttpServletRequest request) {
        User actor = userService.getLoginUser(request);
        return ResultUtils.success(AppGenerationControlVO.from(
                controlService.update(appId, updateRequest.toCommand(), actor)));
    }
}
