package com.rush.rushaicodemother.controller;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.application.chathistory.ChatHistoryQueryApplicationService;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.model.dto.chathistory.ChatHistoryQueryRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.ChatHistoryAdminVO;
import com.rush.rushaicodemother.model.vo.ChatHistoryCursorPageVO;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 对话历史 控制层。
 *
 *
 */
@Validated
@RestController
@RequestMapping("/chatHistory")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatHistoryQueryApplicationService chatHistoryQueryApplicationService;
    private final UserService userService;

    /**
     * 分页查询某个应用的对话历史（游标查询）
     *
     * @param appId          应用ID
     * @param pageSize       页面大小
     * @param lastCreateTime 最后一条记录的创建时间
     * @param lastId         最后一条记录的 ID
     * @param request        请求
     * @return 对话历史游标切片
     */
    @GetMapping("/app/{appId}")
    public BaseResponse<ChatHistoryCursorPageVO> listAppChatHistory(
            @Positive @PathVariable Long appId,
            @Min(1) @Max(50) @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) LocalDateTime lastCreateTime,
            @Positive @RequestParam(required = false) Long lastId,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        ChatHistoryCursorPageVO result = chatHistoryQueryApplicationService.listForApp(
                appId,
                pageSize,
                lastCreateTime,
                lastId,
                loginUser
        );
        return ResultUtils.success(result);
    }

    /**
     * 管理员分页查询所有对话历史
     *
     * @param chatHistoryQueryRequest 查询请求
     * @return 对话历史分页
     */
    @PostMapping("/admin/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<ChatHistoryAdminVO>> listAllChatHistoryByPageForAdmin(
            @Valid @RequestBody ChatHistoryQueryRequest chatHistoryQueryRequest) {
        return ResultUtils.success(
                chatHistoryQueryApplicationService.listForAdministration(chatHistoryQueryRequest)
        );
    }
}
