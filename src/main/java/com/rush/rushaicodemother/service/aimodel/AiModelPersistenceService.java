package com.rush.rushaicodemother.service.aimodel;

import com.mybatisflex.core.paginate.Page;

import java.util.List;

/** AI 模型持久化边界。业务用例不得直接访问 Mapper 或 QueryWrapper。 */
public interface AiModelPersistenceService {

    AiModelConfiguration findActiveById(long modelId);

    AiModelConfiguration lockActiveById(long modelId);

    List<AiModelConfiguration> findEnabled(String modelType);

    Page<AiModelConfiguration> pageActive(QueryCriteria criteria);

    boolean existsActiveIdentity(String provider, String modelId);

    long insert(AiModelConfiguration configuration);

    void update(AiModelConfiguration configuration);

    void logicallyDelete(long modelId);

    record QueryCriteria(int pageNumber,
                         int pageSize,
                         String provider,
                         String modelType,
                         Integer isEnabled,
                         String keyword,
                         String sortField,
                         String sortOrder) {
    }
}
