package com.rush.rushaicodemother.controller;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.DeleteRequest;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.dto.aimodel.AiModelAddRequest;
import com.rush.rushaicodemother.model.dto.aimodel.AiModelConnectionTestRequest;
import com.rush.rushaicodemother.model.dto.aimodel.AiModelQueryRequest;
import com.rush.rushaicodemother.model.dto.aimodel.AiModelToggleRequest;
import com.rush.rushaicodemother.model.dto.aimodel.AiModelUpdateRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.AiModelAdminVO;
import com.rush.rushaicodemother.model.vo.AiModelConnectionTestResultVO;
import com.rush.rushaicodemother.model.vo.AiModelPublicVO;
import com.rush.rushaicodemother.model.vo.SupportedAiModelVO;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.aimodel.AiModelManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 模型配置控制器。
 *
 * <p>所有响应统一通过安全视图对象输出，禁止直接序列化包含 API Key 的持久化实体。</p>
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ai-model")
public class AiModelController {

    private final AiModelManagementService aiModelManagementService;
    private final UserService userService;

    /** 添加模型（仅管理员）。 */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> addModel(@Valid @RequestBody AiModelAddRequest addRequest,
                                       HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        long modelId = aiModelManagementService.createModel(toCreateCommand(addRequest), loginUser.getId());
        return ResultUtils.success(modelId);
    }

    /** 更新模型（仅管理员）。API Key 为空时由服务层保留原密钥。 */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateModel(@Valid @RequestBody AiModelUpdateRequest updateRequest) {
        aiModelManagementService.updateModel(toUpdateCommand(updateRequest));
        return ResultUtils.success(true);
    }

    /** 删除模型（仅管理员）。 */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteModel(@Valid @RequestBody DeleteRequest deleteRequest) {
        aiModelManagementService.deleteModel(deleteRequest.getId());
        return ResultUtils.success(true);
    }

    /** 根据 ID 获取管理端模型视图。 */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AiModelAdminVO> getModelById(@RequestParam @Positive long id) {
        return ResultUtils.success(aiModelManagementService.getModelById(id));
    }

    /** 获取所有启用模型的公开信息。 */
    @GetMapping("/list/enabled")
    public BaseResponse<List<AiModelPublicVO>> listEnabledModels() {
        return ResultUtils.success(aiModelManagementService.listEnabledModels());
    }

    /** 根据类型获取启用模型的公开信息。 */
    @GetMapping("/list/enabled/type")
    public BaseResponse<List<AiModelPublicVO>> listEnabledModelsByType(
            @RequestParam @NotBlank String modelType) {
        return ResultUtils.success(aiModelManagementService.listEnabledModelsByType(modelType));
    }

    /** 获取支持的模型目录（仅管理员）。 */
    @GetMapping("/catalog")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<SupportedAiModelVO>> listSupportedModels() {
        return ResultUtils.success(aiModelManagementService.listSupportedModels());
    }

    /** 分页获取模型管理视图（仅管理员）。 */
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<AiModelAdminVO>> listModelsByPage(
            @Valid @RequestBody AiModelQueryRequest queryRequest) {
        return ResultUtils.success(aiModelManagementService.pageModels(toQuery(queryRequest)));
    }

    /** 切换模型启用状态（仅管理员）。 */
    @PostMapping("/toggle")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AiModelAdminVO> toggleModelEnabled(
            @Valid @RequestBody AiModelToggleRequest toggleRequest) {
        return ResultUtils.success(aiModelManagementService.toggleModelEnabled(toggleRequest.getId()));
    }

    /** 测试已保存模型的连接（仅管理员）。 */
    @PostMapping("/test")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> testModelConnection(
            @Valid @RequestBody AiModelConnectionTestRequest testRequest) {
        AiModelConnectionTestResultVO result =
                aiModelManagementService.testSavedModelConnection(testRequest.getId());
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, result.getMessage());
        }
        return ResultUtils.success(true);
    }

    /** 使用当前表单配置测试模型连接（仅管理员）。 */
    @PostMapping("/test/config")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AiModelConnectionTestResultVO> testModelConnectionByConfig(
            @Valid @RequestBody AiModelAddRequest testRequest) {
        AiModelConnectionTestResultVO result =
                aiModelManagementService.testConfiguration(toCreateCommand(testRequest));
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, result.getMessage());
        }
        return ResultUtils.success(result);
    }

    private AiModelManagementService.CreateCommand toCreateCommand(AiModelAddRequest request) {
        return new AiModelManagementService.CreateCommand(
                request.getModelName(),
                request.getProvider(),
                request.getModelId(),
                request.getDescription(),
                request.getBaseUrl(),
                request.getApiKey(),
                request.getMaxTokens(),
                request.getTemperature(),
                request.getIsEnabled(),
                request.getModelType(),
                request.getSupportsThinking(),
                request.getSortOrder(),
                request.getConfigJson(),
                request.getProtocol()
        );
    }

    private AiModelManagementService.UpdateCommand toUpdateCommand(AiModelUpdateRequest request) {
        return new AiModelManagementService.UpdateCommand(
                request.getId(),
                request.getModelName(),
                request.getProvider(),
                request.getModelId(),
                request.getDescription(),
                request.getBaseUrl(),
                request.getApiKey(),
                request.getMaxTokens(),
                request.getTemperature(),
                request.getIsEnabled(),
                request.getModelType(),
                request.getSupportsThinking(),
                request.getSortOrder(),
                request.getConfigJson(),
                request.getProtocol()
        );
    }

    private AiModelManagementService.Query toQuery(AiModelQueryRequest request) {
        return new AiModelManagementService.Query(
                request.getPageNum(),
                request.getPageSize(),
                request.getProvider(),
                request.getModelType(),
                request.getIsEnabled(),
                request.getKeyword(),
                request.getSortField(),
                request.getSortOrder()
        );
    }

}
