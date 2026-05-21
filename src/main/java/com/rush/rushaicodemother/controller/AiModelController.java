package com.rush.rushaicodemother.controller;

import cn.hutool.core.bean.BeanUtil;
import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.DeleteRequest;
import com.rush.rushaicodemother.common.PageRequest;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.model.event.AiModelConfigChangedEvent;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.service.AiModelService;
import com.rush.rushaicodemother.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI 模型配置 控制层。
 */
@RestController
@RequestMapping("/ai-model")
public class AiModelController {

    @Resource
    private AiModelService aiModelService;

    @Resource
    private com.rush.rushaicodemother.service.impl.AiModelServiceImpl aiModelServiceImpl;

    @Resource
    private UserService userService;

    @Resource
    private ApplicationEventPublisher applicationEventPublisher;

    /**
     * 添加模型（仅管理员）
     */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> addModel(@RequestBody AiModelAddRequest addRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(addRequest == null, ErrorCode.PARAMS_ERROR);

        // 检查是否已存在相同的 provider + modelId
        AiModel existingModel = aiModelService.getByProviderAndModelId(addRequest.getProvider(), addRequest.getModelId());
        if (existingModel != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该模型已存在");
        }

        User loginUser = userService.getLoginUser(request);
        AiModel model = new AiModel();
        BeanUtil.copyProperties(addRequest, model);
        model.setUserId(loginUser.getId());
        boolean result = aiModelService.save(model);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        publishModelConfigChanged();
        return ResultUtils.success(model.getId());
    }

    /**
     * 更新模型（仅管理员）
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateModel(@RequestBody AiModelUpdateRequest updateRequest) {
        ThrowUtils.throwIf(updateRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(updateRequest.getId() == null || updateRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);

        AiModel existingModel = aiModelService.getById(updateRequest.getId());
        ThrowUtils.throwIf(existingModel == null, ErrorCode.NOT_FOUND_ERROR, "模型不存在");

        AiModel model = new AiModel();
        BeanUtil.copyProperties(updateRequest, model);
        boolean result = aiModelService.updateById(model);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        publishModelConfigChanged();
        return ResultUtils.success(true);
    }

    /**
     * 删除模型（仅管理员）
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteModel(@RequestBody DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        boolean result = aiModelService.removeById(deleteRequest.getId());
        if (result) {
            publishModelConfigChanged();
        }
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 获取模型
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AiModel> getModelById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        AiModel model = aiModelService.getById(id);
        ThrowUtils.throwIf(model == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(model);
    }

    /**
     * 获取所有启用的模型（所有用户可用）
     */
    @GetMapping("/list/enabled")
    public BaseResponse<List<AiModel>> listEnabledModels() {
        List<AiModel> models = aiModelService.listEnabledModels();
        return ResultUtils.success(models);
    }

    /**
     * 根据类型获取启用的模型（所有用户可用）
     */
    @GetMapping("/list/enabled/type")
    public BaseResponse<List<AiModel>> listEnabledModelsByType(@RequestParam String modelType) {
        ThrowUtils.throwIf(modelType == null || modelType.isBlank(), ErrorCode.PARAMS_ERROR);
        List<AiModel> models = aiModelService.listEnabledModelsByType(modelType);
        return ResultUtils.success(models);
    }

    /**
     * 分页获取模型列表（仅管理员）
     */
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<AiModel>> listModelsByPage(@RequestBody AiModelQueryRequest queryRequest) {
        ThrowUtils.throwIf(queryRequest == null, ErrorCode.PARAMS_ERROR);
        long pageNum = queryRequest.getPageNum();
        long pageSize = queryRequest.getPageSize();

        Page<AiModel> page = aiModelService.page(
                Page.of(pageNum, pageSize),
                aiModelServiceImpl.getQueryWrapper(queryRequest)
        );
        return ResultUtils.success(page);
    }

    /**
     * 切换模型启用状态（仅管理员）
     */
    @PostMapping("/toggle")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AiModel> toggleModelEnabled(@RequestBody ToggleRequest toggleRequest) {
        ThrowUtils.throwIf(toggleRequest == null || toggleRequest.getId() == null, ErrorCode.PARAMS_ERROR);
        AiModel model = aiModelService.toggleModelEnabled(toggleRequest.getId());
        ThrowUtils.throwIf(model == null, ErrorCode.NOT_FOUND_ERROR);
        publishModelConfigChanged();
        return ResultUtils.success(model);
    }

    /**
     * 测试模型连接（仅管理员）
     */
    @PostMapping("/test")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> testModelConnection(@RequestBody TestRequest testRequest) {
        ThrowUtils.throwIf(testRequest == null || testRequest.getId() == null, ErrorCode.PARAMS_ERROR);
        boolean success = aiModelService.testModelConnection(testRequest.getId());
        if (!success) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型连接测试失败，请检查配置");
        }
        return ResultUtils.success(true);
    }

    // ========== 内部请求类 ==========

    @lombok.Data
    public static class AiModelAddRequest {
        private String modelName;
        private String provider;
        private String modelId;
        private String description;
        private String baseUrl;
        private String apiKey;
        private Integer maxTokens;
        private Double temperature;
        private Integer isEnabled;
        private String modelType;
        private Integer supportsThinking;
        private Integer sortOrder;
        private String configJson;
    }

    @lombok.Data
    public static class AiModelUpdateRequest {
        private Long id;
        private String modelName;
        private String provider;
        private String modelId;
        private String description;
        private String baseUrl;
        private String apiKey;
        private Integer maxTokens;
        private Double temperature;
        private Integer isEnabled;
        private String modelType;
        private Integer supportsThinking;
        private Integer sortOrder;
        private String configJson;
    }

    @lombok.Data
    public static class AiModelQueryRequest extends PageRequest {
        private String provider;
        private String modelType;
        private Integer isEnabled;
        private String keyword;
    }

    @lombok.Data
    public static class ToggleRequest {
        private Long id;
    }

    @lombok.Data
    public static class TestRequest {
        private Long id;
    }

    private void publishModelConfigChanged() {
        applicationEventPublisher.publishEvent(new AiModelConfigChangedEvent());
    }
}
