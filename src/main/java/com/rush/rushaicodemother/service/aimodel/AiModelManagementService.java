package com.rush.rushaicodemother.service.aimodel;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.model.vo.AiModelAdminVO;
import com.rush.rushaicodemother.model.vo.AiModelConnectionTestResultVO;
import com.rush.rushaicodemother.model.vo.AiModelPublicVO;
import com.rush.rushaicodemother.model.vo.SupportedAiModelVO;

import java.util.List;

/** AI 模型管理用例入口。所有输出均为不包含 API Key 的安全视图。 */
public interface AiModelManagementService {

    long createModel(CreateCommand command, long operatorUserId);

    void updateModel(UpdateCommand command);

    void deleteModel(long modelId);

    AiModelAdminVO toggleModelEnabled(long modelId, String evidenceId, long operatorUserId);

    AiModelAdminVO getModelById(long modelId);

    Page<AiModelAdminVO> pageModels(Query query);

    List<AiModelPublicVO> listEnabledModels();

    List<AiModelPublicVO> listEnabledModelsByType(String modelType);

    List<SupportedAiModelVO> listSupportedModels();

    AiModelConnectionTestResultVO testSavedModelConnection(long modelId);

    AiModelConnectionTestResultVO testConfiguration(CreateCommand command);

    record CreateCommand(String modelName,
                         String provider,
                         String modelId,
                         String description,
                         String baseUrl,
                         String apiKey,
                         Integer maxTokens,
                         Double temperature,
                         Integer isEnabled,
                         String modelType,
                         Integer supportsThinking,
                         Integer sortOrder,
                         String configJson,
                         String protocol) {

        @Override
        public String toString() {
            return "CreateCommand[apiKey=<redacted>]";
        }
    }

    record UpdateCommand(Long id,
                         String modelName,
                         String provider,
                         String modelId,
                         String description,
                         String baseUrl,
                         String apiKey,
                         Integer maxTokens,
                         Double temperature,
                         Integer isEnabled,
                         String modelType,
                         Integer supportsThinking,
                         Integer sortOrder,
                         String configJson,
                         String protocol) {

        @Override
        public String toString() {
            return "UpdateCommand[id=" + id + ", apiKey=<redacted>]";
        }
    }

    record Query(int pageNumber,
                 int pageSize,
                 String provider,
                 String modelType,
                 Integer isEnabled,
                 String keyword,
                 String sortField,
                 String sortOrder) {
    }
}
