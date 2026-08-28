package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.model.dto.generation.GenerationTerminalEffectReplayRequest;
import com.rush.rushaicodemother.model.vo.GenerationTerminalEffectBacklogVO;
import com.rush.rushaicodemother.model.vo.GenerationTerminalEffectItemVO;
import com.rush.rushaicodemother.model.vo.GenerationTerminalEffectReplayVO;
import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlAccess;
import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalEffectManagementService;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 终态副作用 outbox 的管理员运维接口。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/generation-terminal-effects/admin")
public class GenerationTerminalEffectController {

    private final GenerationTerminalEffectManagementService managementService;
    private final UserService userService;

    @GetMapping("/backlog")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<GenerationTerminalEffectBacklogVO> inspectBacklog() {
        GenerationTerminalEffectManagementService.Snapshot snapshot = managementService.inspect();
        return ResultUtils.success(GenerationTerminalEffectBacklogVO.from(
                snapshot.backlog(), snapshot.observedAt()));
    }

    @GetMapping("/items")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<GenerationTerminalEffectItemVO>> listOutstanding(
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ResultUtils.success(managementService.listOutstanding(limit).stream()
                .map(GenerationTerminalEffectItemVO::from)
                .toList());
    }

    @PostMapping("/replay")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @GenerationControlAccess(GenerationControlPermission.TERMINAL_EFFECT_REPLAY)
    public BaseResponse<GenerationTerminalEffectReplayVO> replayDeadLetter(
            @Valid @RequestBody GenerationTerminalEffectReplayRequest request,
            HttpServletRequest servletRequest) {
        long operatorUserId = userService.getLoginUserId(servletRequest);
        GenerationTerminalEffectManagementService.ReplayResult result =
                managementService.replayDeadLetter(
                        request.getTaskId(), request.getExecutionEpoch(), operatorUserId);
        return ResultUtils.success(new GenerationTerminalEffectReplayVO(
                result.taskId(), result.executionEpoch(), result.requestedAt()));
    }
}
