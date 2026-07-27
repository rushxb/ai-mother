package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.model.dto.prompt.PromptReleasePublishRequest;
import com.rush.rushaicodemother.model.dto.prompt.PromptReleaseRollbackRequest;
import com.rush.rushaicodemother.model.vo.PromptCatalogAdminVO;
import com.rush.rushaicodemother.model.vo.PromptReleaseHistoryVO;
import com.rush.rushaicodemother.model.vo.PromptReleaseMutationVO;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.prompt.PromptReleaseManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提示词发布后端接口控制器。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ai-prompt")
public class PromptReleaseController {

    private final PromptReleaseManagementService managementService;
    private final UserService userService;

    @GetMapping("/releases")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<PromptCatalogAdminVO> getOverview() {
        return ResultUtils.success(managementService.getOverview());
    }

    @PostMapping("/releases/publish")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<PromptReleaseMutationVO> publish(
            @Valid @RequestBody PromptReleasePublishRequest request,
            HttpServletRequest servletRequest
    ) {
        long operatorUserId = userService.getLoginUserId(servletRequest);
        return ResultUtils.success(managementService.publish(
                new PromptReleaseManagementService.PublishCommand(
                        request.getPromptKey(),
                        request.getStableVersion(),
                        request.getCanaryVersion(),
                        request.getCanaryPercentage(),
                        request.getExpectedRevision(),
                        request.getChangeNote(),
                        request.getEvidenceId()
                ),
                operatorUserId
        ));
    }

    @PostMapping("/releases/rollback")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<PromptReleaseMutationVO> rollback(
            @Valid @RequestBody PromptReleaseRollbackRequest request,
            HttpServletRequest servletRequest
    ) {
        long operatorUserId = userService.getLoginUserId(servletRequest);
        return ResultUtils.success(managementService.rollback(
                new PromptReleaseManagementService.RollbackCommand(
                        request.getPromptKey(),
                        request.getTargetRevision(),
                        request.getExpectedRevision(),
                        request.getChangeNote()
                ),
                operatorUserId
        ));
    }

    @GetMapping("/releases/history")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<PromptReleaseHistoryVO>> listHistory(
            @RequestParam @NotBlank String promptKey,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return ResultUtils.success(managementService.listHistory(promptKey, limit));
    }
}
