package com.rush.rushaicodemother.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.DeleteRequest;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.converter.AiModelViewConverter;
import com.rush.rushaicodemother.model.dto.aimodel.AiModelAddRequest;
import com.rush.rushaicodemother.model.dto.aimodel.AiModelConnectionTestRequest;
import com.rush.rushaicodemother.model.dto.aimodel.AiModelQueryRequest;
import com.rush.rushaicodemother.model.dto.aimodel.AiModelToggleRequest;
import com.rush.rushaicodemother.model.dto.aimodel.AiModelUpdateRequest;
import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.event.AiModelConfigChangedEvent;
import com.rush.rushaicodemother.model.vo.AiModelAdminVO;
import com.rush.rushaicodemother.model.vo.AiModelConnectionTestResultVO;
import com.rush.rushaicodemother.model.vo.AiModelPublicVO;
import com.rush.rushaicodemother.model.vo.SupportedAiModelVO;
import com.rush.rushaicodemother.service.AiModelCatalogService;
import com.rush.rushaicodemother.service.AiModelService;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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

    private final AiModelService aiModelService;
    private final AiModelCatalogService aiModelCatalogService;
    private final UserService userService;
    private final AiModelViewConverter aiModelViewConverter;
    private final ApplicationEventPublisher applicationEventPublisher;

    /** 添加模型（仅管理员）。 */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> addModel(@Valid @RequestBody AiModelAddRequest addRequest,
                                       HttpServletRequest request) {
        AiModel existingModel = aiModelService.getByProviderAndModelId(addRequest.getProvider(), addRequest.getModelId());
        if (existingModel != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该模型已存在");
        }

        User loginUser = userService.getLoginUser(request);
        AiModel model = new AiModel();
        BeanUtil.copyProperties(addRequest, model);
        applyProtocolConfig(model, addRequest.getProtocol());
        model.setUserId(loginUser.getId());
        boolean result = aiModelService.saveModel(model);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        publishModelConfigChanged();
        return ResultUtils.success(model.getId());
    }

    /** 更新模型（仅管理员）。API Key 为空时由服务层保留原密钥。 */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateModel(@Valid @RequestBody AiModelUpdateRequest updateRequest) {
        AiModel model = new AiModel();
        BeanUtil.copyProperties(updateRequest, model);
        applyProtocolConfig(model, updateRequest.getProtocol());
        boolean result = aiModelService.updateModel(model);
        ThrowUtils.throwIf(!result, ErrorCode.NOT_FOUND_ERROR, "模型不存在或更新失败");
        publishModelConfigChanged();
        return ResultUtils.success(true);
    }

    /** 删除模型（仅管理员）。 */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteModel(@RequestBody DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        boolean result = aiModelService.deleteModel(deleteRequest.getId());
        if (result) {
            publishModelConfigChanged();
        }
        return ResultUtils.success(result);
    }

    /** 根据 ID 获取管理端模型视图。 */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AiModelAdminVO> getModelById(@RequestParam @Positive long id) {
        AiModel model = aiModelService.getById(id);
        ThrowUtils.throwIf(model == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(aiModelViewConverter.toAdminVO(model));
    }

    /** 获取所有启用模型的公开信息。 */
    @GetMapping("/list/enabled")
    public BaseResponse<List<AiModelPublicVO>> listEnabledModels() {
        List<AiModelPublicVO> models = aiModelService.listEnabledModels().stream()
                .map(aiModelViewConverter::toPublicVO)
                .toList();
        return ResultUtils.success(models);
    }

    /** 根据类型获取启用模型的公开信息。 */
    @GetMapping("/list/enabled/type")
    public BaseResponse<List<AiModelPublicVO>> listEnabledModelsByType(
            @RequestParam @NotBlank String modelType) {
        List<AiModelPublicVO> models = aiModelService.listEnabledModelsByType(modelType).stream()
                .map(aiModelViewConverter::toPublicVO)
                .toList();
        return ResultUtils.success(models);
    }

    /** 获取支持的模型目录（仅管理员）。 */
    @GetMapping("/catalog")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<SupportedAiModelVO>> listSupportedModels() {
        return ResultUtils.success(aiModelCatalogService.listSupportedModels());
    }

    /** 分页获取模型管理视图（仅管理员）。 */
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<AiModelAdminVO>> listModelsByPage(
            @Valid @RequestBody AiModelQueryRequest queryRequest) {
        long pageNum = queryRequest.getPageNum();
        long pageSize = queryRequest.getPageSize();
        Page<AiModel> entityPage = aiModelService.page(
                Page.of(pageNum, pageSize),
                aiModelService.getQueryWrapper(queryRequest)
        );
        Page<AiModelAdminVO> resultPage = new Page<>(pageNum, pageSize, entityPage.getTotalRow());
        resultPage.setRecords(entityPage.getRecords().stream()
                .map(aiModelViewConverter::toAdminVO)
                .toList());
        return ResultUtils.success(resultPage);
    }

    /** 切换模型启用状态（仅管理员）。 */
    @PostMapping("/toggle")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AiModelAdminVO> toggleModelEnabled(
            @Valid @RequestBody AiModelToggleRequest toggleRequest) {
        AiModel model = aiModelService.toggleModelEnabled(toggleRequest.getId());
        ThrowUtils.throwIf(model == null, ErrorCode.NOT_FOUND_ERROR);
        publishModelConfigChanged();
        return ResultUtils.success(aiModelViewConverter.toAdminVO(model));
    }

    /** 测试已保存模型的连接（仅管理员）。 */
    @PostMapping("/test")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> testModelConnection(
            @Valid @RequestBody AiModelConnectionTestRequest testRequest) {
        boolean success = aiModelService.testModelConnection(testRequest.getId());
        if (!success) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型连接测试失败，请检查配置");
        }
        return ResultUtils.success(true);
    }

    /** 使用当前表单配置测试模型连接（仅管理员）。 */
    @PostMapping("/test/config")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AiModelConnectionTestResultVO> testModelConnectionByConfig(
            @Valid @RequestBody AiModelAddRequest testRequest) {
        AiModel model = new AiModel();
        BeanUtil.copyProperties(testRequest, model);
        applyProtocolConfig(model, testRequest.getProtocol());
        AiModelConnectionTestResultVO result = aiModelService.testModelConnection(model);
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, result.getMessage());
        }
        return ResultUtils.success(result);
    }

    private void publishModelConfigChanged() {
        applicationEventPublisher.publishEvent(new AiModelConfigChangedEvent());
    }

    private void applyProtocolConfig(AiModel model, String protocol) {
        if (model == null || StrUtil.isBlank(protocol)) {
            return;
        }
        try {
            JSONObject config = StrUtil.isBlank(model.getConfigJson())
                    ? new JSONObject()
                    : JSONUtil.parseObj(model.getConfigJson());
            config.set("protocol", protocol);
            model.setConfigJson(JSONUtil.toJsonStr(config));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型扩展配置 JSON 格式错误");
        }
    }
}