package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.AppMapper;
import com.rush.rushaicodemother.model.entity.App;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 查询启动 Dev Server 所需的最小应用状态。
 *
 * <p>该组件只依赖数据访问层，避免 Dev Server 工具反向依赖包含生成编排能力的
 * 应用服务，从模块边界上阻断循环依赖。</p>
 */
@Component
@RequiredArgsConstructor
public class DevServerAppTargetLookup {

    private final AppMapper appMapper;

    /**
     * 返回未被逻辑删除的应用启动目标。
     *
     * @param appId 应用 ID
     * @return Dev Server 启动所需的应用状态
     */
    public App requireTarget(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 必须大于 0");
        }
        App app = appMapper.selectDevServerTarget(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        return app;
    }
}
