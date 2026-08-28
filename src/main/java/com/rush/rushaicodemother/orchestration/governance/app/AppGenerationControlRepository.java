package com.rush.rushaicodemother.orchestration.governance.app;

import com.rush.rushaicodemother.model.entity.App;

/** 应用生成控制的读写持久化端口。 */
public interface AppGenerationControlRepository extends AppGenerationControlReader {

    App findActiveApplication(Long appId);

    App lockActiveApplication(Long appId);

    boolean insert(AppGenerationControlPolicy policy);

    boolean update(AppGenerationControlPolicy policy, long expectedVersion);
}
