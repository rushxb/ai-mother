package com.rush.rushaicodemother.service.user;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.model.dto.user.UserQueryRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.AdminUserVO;
import com.rush.rushaicodemother.model.vo.PublicUserSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 默认用户只读目录实现。 */
@Service
@RequiredArgsConstructor
public class DefaultUserDirectoryService implements UserDirectoryService {

    private final UserPersistenceService userPersistenceService;
    private final UserViewConverter userViewConverter;

    /**
 * 查找匹配的活动用户视图。
 *
 * @param userId 用户编号
 * @return 活动用户视图
 */
    @Override
    public AdminUserVO findActiveAdminView(Long userId) {
        return userViewConverter.toAdminView(userPersistenceService.findActiveById(userId));
    }

    @Override
    public PublicUserSummaryVO findActivePublicSummary(Long userId) {
        return userViewConverter.toPublicSummary(userPersistenceService.findActiveById(userId));
    }

    /**
 * 查找匹配的活动用户{@code Views}。
 *
 * @param userIds 待处理的 {@code userIds} 集合
 * @return 活动用户{@code Views}集合
 */
    @Override
    public Map<Long, AdminUserVO> findActiveAdminViews(Collection<Long> userIds) {
        List<User> users = userPersistenceService.findActiveByIds(userIds);
        if (users.isEmpty()) {
            return Map.of();
        }
        Map<Long, AdminUserVO> userViews = new LinkedHashMap<>();
        for (User user : users) {
            if (user == null || user.getId() == null) {
                continue;
            }
            if (userViews.containsKey(user.getId())) {
                continue;
            }
            AdminUserVO userView = userViewConverter.toAdminView(user);
            if (userView != null) {
                userViews.put(user.getId(), userView);
            }
        }
        return Map.copyOf(userViews);
    }

    @Override
    public Map<Long, PublicUserSummaryVO> findActivePublicSummaries(Collection<Long> userIds) {
        List<User> users = userPersistenceService.findActiveByIds(userIds);
        if (users.isEmpty()) {
            return Map.of();
        }
        Map<Long, PublicUserSummaryVO> summaries = new LinkedHashMap<>();
        for (User user : users) {
            if (user == null || user.getId() == null || summaries.containsKey(user.getId())) {
                continue;
            }
            PublicUserSummaryVO summary = userViewConverter.toPublicSummary(user);
            if (summary != null) {
                summaries.put(user.getId(), summary);
            }
        }
        return Map.copyOf(summaries);
    }

    /**
 * 返回{@code page}活动用户{@code Views}。
 *
 * @param queryRequest 查询请求
 * @return 默认用户目录
 */
    @Override
    public Page<AdminUserVO> pageActiveAdminViews(UserQueryRequest queryRequest) {
        Page<User> userPage = userPersistenceService.pageActiveUsers(queryRequest);
        Page<AdminUserVO> resultPage = new Page<>(
                userPage.getPageNumber(),
                userPage.getPageSize(),
                userPage.getTotalRow()
        );
        resultPage.setRecords(userPage.getRecords().stream()
                .map(userViewConverter::toAdminView)
                .toList());
        return resultPage;
    }
}
