package com.rush.rushaicodemother.service.app;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.rush.rushaicodemother.common.query.SortFieldWhitelist;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.mapper.AppMapper;
import com.rush.rushaicodemother.model.dto.app.AppQueryRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/** MyBatis-Flex 应用元数据持久化实现。 */
@Service
@RequiredArgsConstructor
public class DefaultAppPersistenceService implements AppPersistenceService {

    private static final SortFieldWhitelist SORT_FIELDS = SortFieldWhitelist.of("createTime", Map.of(
            "id", "id",
            "appName", "appName",
            "priority", "priority",
            "userId", "userId",
            "tenantId", "tenantId",
            "editTime", "editTime",
            "createTime", "createTime",
            "updateTime", "updateTime"
    ));

    private final AppMapper appMapper;

    /**
 * 创建{@code Prepared}。
 *
 * @param newApp {@code newApp} 对应的调用参数
 * @return 计算或处理后的数值结果
 */
    @Override
    public long createPrepared(NewApp newApp) {
        ThrowUtils.throwIf(newApp == null, ErrorCode.PARAMS_ERROR, "应用创建参数不能为空");
        ThrowUtils.throwIf(newApp.userId() == null || newApp.userId() <= 0,
                ErrorCode.PARAMS_ERROR, "应用所有者 ID 不合法");
        ThrowUtils.throwIf(newApp.tenantId() == null || newApp.tenantId() <= 0,
                ErrorCode.PARAMS_ERROR, "应用租户 ID 不合法");
        ThrowUtils.throwIf(newApp.codeGenType() == null || newApp.codeGenType().isBlank(),
                ErrorCode.PARAMS_ERROR, "代码生成类型不能为空");
        ThrowUtils.throwIf(CodeGenTypeEnum.getEnumByValue(newApp.codeGenType()) == null,
                ErrorCode.PARAMS_ERROR, "代码生成类型不受支持");
        ThrowUtils.throwIf(newApp.initPrompt() == null || newApp.initPrompt().isBlank(),
                ErrorCode.PARAMS_ERROR, "初始化提示词不能为空");
        App entity = App.builder()
                .appName(newApp.appName())
                .initPrompt(newApp.initPrompt())
                .codeGenType(newApp.codeGenType())
                .priority(newApp.priority())
                .userId(newApp.userId())
                .tenantId(newApp.tenantId())
                .build();
        if (appMapper.insertPreparedApp(entity) != 1 || entity.getId() == null || entity.getId() <= 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "创建应用失败");
        }
        return entity.getId();
    }

    /**
 * 查找匹配的活动按编号。
 *
 * @param appId 应用编号
 * @return 活动按编号
 */
    @Override
    public App findActiveById(Long appId) {
        validateAppId(appId);
        return appMapper.selectActiveById(appId);
    }

    /**
 * 返回{@code page}活动{@code Apps}。
 *
 * @param queryRequest 查询请求
 * @return 默认应用持久化
 */
    @Override
    public Page<App> pageActiveApps(AppQueryRequest queryRequest) {
        ThrowUtils.throwIf(queryRequest == null, ErrorCode.PARAMS_ERROR, "查询条件不能为空");
        ThrowUtils.throwIf(queryRequest.getPageNum() <= 0 || queryRequest.getPageSize() <= 0,
                ErrorCode.PARAMS_ERROR, "分页参数必须大于 0");
        return appMapper.paginate(
                Page.of(queryRequest.getPageNum(), queryRequest.getPageSize()),
                buildQueryWrapper(queryRequest)
        );
    }

    /**
 * 更新名称。
 *
 * @param appId 应用编号
 * @param appName 应用名称
 * @param editTime 编辑时间
 */
    @Override
    public void updateName(Long appId, String appName, LocalDateTime editTime) {
        validateAppId(appId);
        ThrowUtils.throwIf(appName == null, ErrorCode.PARAMS_ERROR, "应用名称不能为空");
        updateExactlyOne(
                appMapper.updateActiveName(appId, appName, requireEditTime(editTime)),
                "更新应用名称失败"
        );
    }

    /**
 * 更新{@code Administration}{@code Fields}。
 *
 * @param appId 应用编号
 * @param appName 应用名称
 * @param cover {@code cover} 对应的调用参数
 * @param priority {@code priority} 对应的调用参数
 * @param editTime 编辑时间
 */
    @Override
    public void updateAdministrationFields(Long appId,
                                           String appName,
                                           String cover,
                                           Integer priority,
                                           LocalDateTime editTime) {
        validateAppId(appId);
        ThrowUtils.throwIf(appName == null && cover == null && priority == null,
                ErrorCode.PARAMS_ERROR, "至少提供一个待更新字段");
        updateExactlyOne(
                appMapper.updateActiveAdministrationFields(
                        appId,
                        appName,
                        cover,
                        priority,
                        requireEditTime(editTime)
                ),
                "管理员更新应用失败"
        );
    }

    /**
 * 更新开发服务器端口。
 *
 * @param appId 应用编号
 * @param port 端口
 */
    @Override
    public void updateDevServerPort(Long appId, int port) {
        validateAppId(appId);
        ThrowUtils.throwIf(port < 1 || port > 65535, ErrorCode.PARAMS_ERROR, "Dev Server 端口不合法");
        updateExactlyOne(
                appMapper.updateActiveDevServerPort(appId, port),
                "保存 Dev Server 端口失败"
        );
    }

    /** 构建并返回查询{@code Wrapper}。 */
    private QueryWrapper buildQueryWrapper(AppQueryRequest queryRequest) {
        String sortField = SORT_FIELDS.resolve(queryRequest.getSortField());
        boolean ascending = "ascend".equals(queryRequest.getSortOrder());
        return QueryWrapper.create()
                .eq("id", queryRequest.getId())
                .like("appName", queryRequest.getAppName())
                .like("cover", queryRequest.getCover())
                .like("initPrompt", queryRequest.getInitPrompt())
                .eq("codeGenType", queryRequest.getCodeGenType())
                .eq("deployKey", queryRequest.getDeployKey())
                .eq("priority", queryRequest.getPriority())
                .eq("userId", queryRequest.getUserId())
                .eq("isDelete", 0)
                .orderBy(sortField, ascending);
    }

    private void updateExactlyOne(int affectedRows, String failureMessage) {
        if (affectedRows != 1) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, failureMessage);
        }
    }

    private LocalDateTime requireEditTime(LocalDateTime editTime) {
        ThrowUtils.throwIf(editTime == null, ErrorCode.PARAMS_ERROR, "编辑时间不能为空");
        return editTime;
    }

    private void validateAppId(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
    }
}
