package com.rush.rushaicodemother.service.provisioning;

import com.rush.rushaicodemother.model.dto.app.AppAddRequest;
import com.rush.rushaicodemother.model.entity.User;

/** 应用创建与复制供给模块。 */
public interface AppProvisioningService {

    /** 创建一个尚未生成代码的新应用。 */
    Long create(AppAddRequest request, User actor);

    /** 复制应用记录、对话历史和可复用的生成源码，不复制部署与运行时状态。 */
    Long copy(Long sourceAppId, User actor);
}
