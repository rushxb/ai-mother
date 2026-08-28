package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.TenantGenerationControlPlaneVO;
import com.rush.rushaicodemother.orchestration.governance.TenantGenerationControlPlaneService;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 租户管理员生成控制面，只提供低敏只读聚合。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/generation/tenants")
public class TenantGenerationControlPlaneController {

    private final UserService userService;
    private final TenantGenerationControlPlaneService controlPlaneService;

    /**
     * 获取租户月预算、排队、场景单位成功成本和当前准入阻断项。
     *
     * @param tenantId 租户编号
     * @param request 当前 HTTP 请求
     * @return 版本化只读控制面响应
     */
    @GetMapping("/{tenantId}/control-plane")
    public BaseResponse<TenantGenerationControlPlaneVO> get(
            @PathVariable @Positive Long tenantId,
            HttpServletRequest request) {
        User actor = userService.getLoginUser(request);
        return ResultUtils.success(TenantGenerationControlPlaneVO.from(
                controlPlaneService.get(tenantId, actor)));
    }
}
