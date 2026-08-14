package com.rush.rushaicodemother.application.app;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.dto.app.AppQueryRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.OwnerAppVO;
import com.rush.rushaicodemother.model.vo.PublicAppVO;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 应用查询模块。
 *
 * <p>负责查询作用域、分页和视图转换，控制层不再直接接触 ORM 查询对象或持久化实体列表。</p>
 */
@Service
@RequiredArgsConstructor
public class AppQueryApplicationService {

    private static final int USER_PAGE_SIZE_LIMIT = 20;

    private final AppPersistenceService appPersistenceService;
    private final AppViewAssembler appViewAssembler;
    private final AppAccessPolicy appAccessPolicy;

    /** 返回登录用户有权查看的敏感详情。 */
    public OwnerAppVO getAuthorizedDetail(long appId, User actor) {
        App app = requireExistingApp(appId);
        appAccessPolicy.requireViewerOrAdmin(app, actor, "无权查看该应用详情");
        return appViewAssembler.toSensitiveView(app);
    }

    /** 管理端详情由控制层的管理员门禁保护。 */
    public OwnerAppVO getForAdministration(long appId) {
        return appViewAssembler.toSensitiveView(requireExistingApp(appId));
    }

    /**
 * 列出符合条件的{@code Mine}。
 *
 * @param sourceRequest 来源请求
 * @param userId 用户编号
 * @return {@code Mine}
 */
    public Page<OwnerAppVO> listMine(AppQueryRequest sourceRequest, Long userId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        AppQueryRequest scopedRequest = copyOf(sourceRequest);
        scopedRequest.setUserId(userId);
        enforcePageSize(scopedRequest, USER_PAGE_SIZE_LIMIT);
        return querySensitivePage(scopedRequest);
    }

    /**
 * 列出符合条件的{@code Featured}。
 *
 * @param sourceRequest 来源请求
 * @return {@code Featured}
 */
    public Page<PublicAppVO> listFeatured(AppQueryRequest sourceRequest) {
        AppQueryRequest scopedRequest = copyOf(sourceRequest);
        scopedRequest.setPriority(AppConstant.GOOD_APP_PRIORITY);
        enforcePageSize(scopedRequest, USER_PAGE_SIZE_LIMIT);
        return queryPublicPage(scopedRequest);
    }

    /**
 * 列出符合条件的{@code For}{@code Administration}。
 *
 * @param sourceRequest 来源请求
 * @return {@code For}{@code Administration}
 */
    public Page<OwnerAppVO> listForAdministration(AppQueryRequest sourceRequest) {
        return querySensitivePage(copyOf(sourceRequest));
    }

    private Page<OwnerAppVO> querySensitivePage(AppQueryRequest queryRequest) {
        long pageNum = queryRequest.getPageNum();
        long pageSize = queryRequest.getPageSize();
        Page<App> appPage = appPersistenceService.pageActiveApps(queryRequest);
        Page<OwnerAppVO> result = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<OwnerAppVO> records = appViewAssembler.toSensitiveViewList(appPage.getRecords());
        result.setRecords(records);
        return result;
    }

    private Page<PublicAppVO> queryPublicPage(AppQueryRequest queryRequest) {
        long pageNum = queryRequest.getPageNum();
        long pageSize = queryRequest.getPageSize();
        Page<App> appPage = appPersistenceService.pageActiveApps(queryRequest);
        Page<PublicAppVO> result = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        result.setRecords(appViewAssembler.toPublicViewList(appPage.getRecords()));
        return result;
    }

    private App requireExistingApp(long appId) {
        ThrowUtils.throwIf(appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        App app = appPersistenceService.findActiveById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        return app;
    }

    private void enforcePageSize(AppQueryRequest request, int limit) {
        ThrowUtils.throwIf(request.getPageSize() > limit,
                ErrorCode.PARAMS_ERROR, "每页最多查询 " + limit + " 个应用");
    }

    /** 复制{@code Of}。 */
    private AppQueryRequest copyOf(AppQueryRequest source) {
        ThrowUtils.throwIf(source == null, ErrorCode.PARAMS_ERROR, "查询条件不能为空");
        AppQueryRequest target = new AppQueryRequest();
        target.setPageNum(source.getPageNum());
        target.setPageSize(source.getPageSize());
        target.setSortField(source.getSortField());
        target.setSortOrder(source.getSortOrder());
        target.setId(source.getId());
        target.setAppName(source.getAppName());
        target.setCover(source.getCover());
        target.setInitPrompt(source.getInitPrompt());
        target.setCodeGenType(source.getCodeGenType());
        target.setDeployKey(source.getDeployKey());
        target.setPriority(source.getPriority());
        target.setUserId(source.getUserId());
        return target;
    }
}
