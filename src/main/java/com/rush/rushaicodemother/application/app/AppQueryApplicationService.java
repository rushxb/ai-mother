package com.rush.rushaicodemother.application.app;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.dto.app.AppQueryRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.vo.AppVO;
import com.rush.rushaicodemother.service.AppService;
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

    private final AppService appService;
    private final AppViewAssembler appViewAssembler;

    public AppVO getById(long appId) {
        App app = requireExistingApp(appId);
        return appViewAssembler.toView(app);
    }

    public Page<AppVO> listMine(AppQueryRequest sourceRequest, Long userId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        AppQueryRequest scopedRequest = copyOf(sourceRequest);
        scopedRequest.setUserId(userId);
        enforcePageSize(scopedRequest, USER_PAGE_SIZE_LIMIT);
        return queryPage(scopedRequest);
    }

    public Page<AppVO> listFeatured(AppQueryRequest sourceRequest) {
        AppQueryRequest scopedRequest = copyOf(sourceRequest);
        scopedRequest.setPriority(AppConstant.GOOD_APP_PRIORITY);
        enforcePageSize(scopedRequest, USER_PAGE_SIZE_LIMIT);
        return queryPage(scopedRequest);
    }

    public Page<AppVO> listForAdministration(AppQueryRequest sourceRequest) {
        return queryPage(copyOf(sourceRequest));
    }

    private Page<AppVO> queryPage(AppQueryRequest queryRequest) {
        long pageNum = queryRequest.getPageNum();
        long pageSize = queryRequest.getPageSize();
        Page<App> appPage = appService.page(
                Page.of(pageNum, pageSize),
                appService.getQueryWrapper(queryRequest)
        );
        Page<AppVO> result = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> records = appViewAssembler.toViewList(appPage.getRecords());
        result.setRecords(records);
        return result;
    }

    private App requireExistingApp(long appId) {
        ThrowUtils.throwIf(appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        return app;
    }

    private void enforcePageSize(AppQueryRequest request, int limit) {
        ThrowUtils.throwIf(request.getPageSize() > limit,
                ErrorCode.PARAMS_ERROR, "每页最多查询 " + limit + " 个应用");
    }

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