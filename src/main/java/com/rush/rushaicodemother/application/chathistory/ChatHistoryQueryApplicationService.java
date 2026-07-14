package com.rush.rushaicodemother.application.chathistory;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.application.app.AppAccessPolicy;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.dto.chathistory.ChatHistoryQueryRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.ChatHistoryAdminVO;
import com.rush.rushaicodemother.model.vo.ChatHistoryCursorPageVO;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 对话历史查询应用模块。
 *
 * <p>集中编排应用存在性、访问授权和游标分页，避免对话历史持久化模块反向依赖
 * 应用持久化边界，从结构上消除循环依赖和延迟注入。</p>
 */
@Service
@RequiredArgsConstructor
public class ChatHistoryQueryApplicationService {

    private static final int MAX_PAGE_SIZE = 50;

    private final AppPersistenceService appPersistenceService;
    private final ChatHistoryService chatHistoryService;
    private final AppAccessPolicy appAccessPolicy;
    private final ChatHistoryViewAssembler chatHistoryViewAssembler;

    /**
     * 查询指定应用的对话历史。
     *
     * @param appId         应用 ID
     * @param pageSize      单页数量
     * @param lastCreateTime 上一页最后一条记录的创建时间
     * @param lastId        上一页最后一条记录的 ID
     * @param actor         当前登录用户
     * @return 对话历史游标切片
     */
    public ChatHistoryCursorPageVO listForApp(
            Long appId,
            int pageSize,
            LocalDateTime lastCreateTime,
            Long lastId,
            User actor
    ) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(pageSize <= 0 || pageSize > MAX_PAGE_SIZE,
                ErrorCode.PARAMS_ERROR, "页面大小必须在 1-50 之间");

        App app = appPersistenceService.findActiveById(appId);
        appAccessPolicy.requireOwnerOrAdmin(app, actor, "无权查看该应用的对话历史");

        return chatHistoryViewAssembler.toCursorPage(
                chatHistoryService.listForApp(appId, pageSize, lastCreateTime, lastId)
        );
    }

    /** 管理端分页查询，统一在应用边界转换为安全视图。 */
    public Page<ChatHistoryAdminVO> listForAdministration(ChatHistoryQueryRequest queryRequest) {
        ThrowUtils.throwIf(queryRequest == null, ErrorCode.PARAMS_ERROR, "查询条件不能为空");
        return chatHistoryViewAssembler.toAdminPage(
                chatHistoryService.pageForAdministration(queryRequest)
        );
    }
}
