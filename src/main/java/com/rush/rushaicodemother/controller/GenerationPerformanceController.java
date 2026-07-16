package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.model.vo.GenerationDurationProfileVO;
import com.rush.rushaicodemother.model.vo.GenerationPerformanceSummaryVO;
import com.rush.rushaicodemother.model.vo.GenerationTaskSpanVO;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanQueryService;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationDurationProfileService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/generation-performance")
@RequiredArgsConstructor
public class GenerationPerformanceController {

    private static final String ROUTE_PATTERN = "[a-zA-Z0-9_-]{1,64}";

    private final GenerationPerformanceMonitorService generationPerformanceMonitorService;
    private final GenerationSpanQueryService generationSpanQueryService;
    private final GenerationDurationProfileService generationDurationProfileService;

    @GetMapping("/admin/summary")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<GenerationPerformanceSummaryVO> getAdminSummary(@RequestParam(required = false) Integer limit) {
        return ResultUtils.success(generationPerformanceMonitorService.getSummary(limit));
    }

    @GetMapping("/admin/tasks/{taskId}/spans")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<GenerationTaskSpanVO>> getTaskSpans(
            @PathVariable String taskId,
            @RequestParam(required = false) Integer limit
    ) {
        List<GenerationTaskSpanVO> spans = generationSpanQueryService.findByTaskId(taskId, limit).stream()
                .map(span -> GenerationTaskSpanVO.builder()
                        .spanId(span.spanId())
                        .taskId(span.taskId())
                        .stage(span.stage())
                        .category(span.category())
                        .status(span.status())
                        .startedAt(span.startedAt())
                        .endedAt(span.endedAt())
                        .durationMs(span.durationMs())
                        .detail(span.detail())
                        .build())
                .toList();
        return ResultUtils.success(spans);
    }
    @GetMapping("/admin/routes/{route}/duration-profile")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<GenerationDurationProfileVO> getRouteDurationProfile(
            @PathVariable @Pattern(regexp = ROUTE_PATTERN) String route
    ) {
        return ResultUtils.success(GenerationDurationProfileVO.from(
                generationDurationProfileService.getProfile(route)));
    }

}
