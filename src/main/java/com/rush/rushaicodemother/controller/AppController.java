package com.rush.rushaicodemother.controller;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.application.app.AppManagementApplicationService;
import com.rush.rushaicodemother.application.app.AppQueryApplicationService;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.DeleteRequest;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.model.dto.app.AppAddRequest;
import com.rush.rushaicodemother.model.dto.app.AppAdminUpdateRequest;
import com.rush.rushaicodemother.model.dto.app.AppCopyRequest;
import com.rush.rushaicodemother.model.dto.app.AppQueryRequest;
import com.rush.rushaicodemother.model.dto.app.AppUpdateRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.OwnerAppVO;
import com.rush.rushaicodemother.model.vo.PublicAppVO;
import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlAccess;
import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditResource;
import com.rush.rushaicodemother.ratelimiter.annotation.RateLimit;
import com.rush.rushaicodemother.ratelimiter.enums.RateLimitType;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用基础管理与查询控制器。
 *
 * <p>生成、部署、代码文件和 Dev Server 能力由对应的专用控制器承载，
 * 本控制器仅负责 HTTP 参数适配和统一响应封装。</p>
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app")
public class AppController {

    private static final String APP_CREATION_RATE_LIMIT_KEY = "app:create";
    private static final int APP_CREATION_RATE_LIMIT = 10;
    private static final int APP_CREATION_RATE_INTERVAL_SECONDS = 3600;

    private final AppManagementApplicationService appManagementApplicationService;
    private final AppQueryApplicationService appQueryApplicationService;
    private final UserService userService;

    /** 创建应用。 */
    @PostMapping("/add")
    @RateLimit(
            key = APP_CREATION_RATE_LIMIT_KEY,
            limitType = RateLimitType.USER,
            rate = APP_CREATION_RATE_LIMIT,
            rateInterval = APP_CREATION_RATE_INTERVAL_SECONDS,
            message = "应用创建过于频繁，请稍后再试"
    )
    public BaseResponse<Long> addApp(@Valid @RequestBody AppAddRequest requestBody,
                                     HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        return ResultUtils.success(appManagementApplicationService.create(requestBody, loginUser));
    }

    /** 将已有应用复制为当前用户的应用。 */
    @PostMapping("/copy")
    public BaseResponse<Long> copyApp(@Valid @RequestBody AppCopyRequest requestBody,
                                      HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        return ResultUtils.success(
                appManagementApplicationService.copy(requestBody.getSourceAppId(), loginUser)
        );
    }

    /** 更新当前用户所拥有应用的名称。 */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateApp(@Valid @RequestBody AppUpdateRequest requestBody,
                                           HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        appManagementApplicationService.updateName(requestBody, loginUser);
        return ResultUtils.success(true);
    }

    /** 删除当前用户拥有的应用；管理员也可通过该入口删除。 */
    @PostMapping("/delete")
    @GenerationControlAccess(
            value = GenerationControlPermission.APP_DELETE,
            auditResource = GenerationControlAuditResource.APP,
            auditResourceId = "#p0.id")
    public BaseResponse<Boolean> deleteApp(@Valid @RequestBody DeleteRequest requestBody,
                                           HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        appManagementApplicationService.delete(requestBody.getId(), loginUser);
        return ResultUtils.success(true);
    }

    /** 根据 ID 获取应用详情。 */
    @GetMapping("/get/vo")
    public BaseResponse<OwnerAppVO> getAppVOById(@RequestParam @Positive long id,
                                                 HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        return ResultUtils.success(appQueryApplicationService.getAuthorizedDetail(id, loginUser));
    }

    /** 分页获取当前用户创建的应用。 */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<OwnerAppVO>> listMyAppVOByPage(
            @Valid @RequestBody AppQueryRequest requestBody,
            HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        return ResultUtils.success(
                appQueryApplicationService.listMine(requestBody, loginUser.getId())
        );
    }

    /** 分页获取精选应用。 */
    @PostMapping("/good/list/page/vo")
    public BaseResponse<Page<PublicAppVO>> listGoodAppVOByPage(
            @Valid @RequestBody AppQueryRequest requestBody) {
        return ResultUtils.success(appQueryApplicationService.listFeatured(requestBody));
    }

    /** 管理员删除应用。 */
    @PostMapping("/admin/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @GenerationControlAccess(
            value = GenerationControlPermission.APP_DELETE,
            auditResource = GenerationControlAuditResource.APP,
            auditResourceId = "#p0.id")
    public BaseResponse<Boolean> deleteAppByAdmin(@Valid @RequestBody DeleteRequest requestBody) {
        appManagementApplicationService.deleteAsAdministrator(requestBody.getId());
        return ResultUtils.success(true);
    }

    /** 管理员更新应用的可管理字段。 */
    @PostMapping("/admin/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateAppByAdmin(@Valid @RequestBody AppAdminUpdateRequest requestBody) {
        appManagementApplicationService.updateAsAdministrator(requestBody);
        return ResultUtils.success(true);
    }

    /** 管理员分页获取应用。 */
    @PostMapping("/admin/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<OwnerAppVO>> listAppVOByPageByAdmin(
            @Valid @RequestBody AppQueryRequest requestBody) {
        return ResultUtils.success(appQueryApplicationService.listForAdministration(requestBody));
    }

    /** 管理员根据 ID 获取应用详情。 */
    @GetMapping("/admin/get/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<OwnerAppVO> getAppVOByIdByAdmin(@RequestParam @Positive long id) {
        return ResultUtils.success(appQueryApplicationService.getForAdministration(id));
    }
}
