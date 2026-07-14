package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/health")
public class HealthController {

    /**
     * 兼容历史调用方的进程存活检查。
     *
     * <p>该接口只证明 Web 进程可以处理请求，不代表 MySQL、Redis 等外部依赖已就绪。
     * 部署系统应分别使用 Actuator 的 liveness 和 readiness 探针。</p>
     */
    @GetMapping({"", "/"})
    public BaseResponse<String> healthCheck() {
        return ResultUtils.success("ok");
    }
}
