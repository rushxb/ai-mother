package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.model.vo.GenerationPerformanceSummaryVO;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/generation-performance")
@RequiredArgsConstructor
public class GenerationPerformanceController {

    private final GenerationPerformanceMonitorService generationPerformanceMonitorService;

    @GetMapping("/admin/summary")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<GenerationPerformanceSummaryVO> getAdminSummary(@RequestParam(required = false) Integer limit) {
        return ResultUtils.success(generationPerformanceMonitorService.getSummary(limit));
    }
}
