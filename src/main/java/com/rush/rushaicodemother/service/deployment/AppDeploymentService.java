package com.rush.rushaicodemother.service.deployment;

import com.rush.rushaicodemother.model.entity.App;

/**
 * 应用部署服务。
 *
 * <p>调用方负责身份和应用所有权校验，本服务负责构建、产物切换、部署状态持久化与截图触发。</p>
 */
public interface AppDeploymentService {

    /** 部署已完成访问授权的应用。 */
    String deploy(App app);

    /** 同步已有部署。 */
    String synchronize(App app);
}
