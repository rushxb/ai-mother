package com.rush.rushaicodemother.service.user;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.model.dto.user.UserQueryRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.UserVO;
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

    @Override
    public UserVO findActiveUserView(Long userId) {
        return userViewConverter.toUserView(userPersistenceService.findActiveById(userId));
    }

    @Override
    public Map<Long, UserVO> findActiveUserViews(Collection<Long> userIds) {
        List<User> users = userPersistenceService.findActiveByIds(userIds);
        if (users.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserVO> userViews = new LinkedHashMap<>();
        for (User user : users) {
            if (user == null || user.getId() == null) {
                continue;
            }
            if (userViews.containsKey(user.getId())) {
                continue;
            }
            UserVO userView = userViewConverter.toUserView(user);
            if (userView != null) {
                userViews.put(user.getId(), userView);
            }
        }
        return Map.copyOf(userViews);
    }

    @Override
    public Page<UserVO> pageActiveUserViews(UserQueryRequest queryRequest) {
        Page<User> userPage = userPersistenceService.pageActiveUsers(queryRequest);
        Page<UserVO> resultPage = new Page<>(
                userPage.getPageNumber(),
                userPage.getPageSize(),
                userPage.getTotalRow()
        );
        resultPage.setRecords(userPage.getRecords().stream()
                .map(userViewConverter::toUserView)
                .toList());
        return resultPage;
    }
}
