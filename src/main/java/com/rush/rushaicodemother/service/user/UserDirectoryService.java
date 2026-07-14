package com.rush.rushaicodemother.service.user;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.model.dto.user.UserQueryRequest;
import com.rush.rushaicodemother.model.vo.UserVO;

import java.util.Collection;
import java.util.Map;

/** 用户只读目录，为控制器和其他业务模块提供脱敏用户视图。 */
public interface UserDirectoryService {

    /** 查询未删除用户视图；用户不存在时返回 {@code null}。 */
    UserVO findActiveUserView(Long userId);

    /** 批量查询未删除用户视图，并按用户 ID 建立索引。 */
    Map<Long, UserVO> findActiveUserViews(Collection<Long> userIds);

    /** 分页查询未删除用户视图。 */
    Page<UserVO> pageActiveUserViews(UserQueryRequest queryRequest);
}
