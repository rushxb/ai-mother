package com.rush.rushaicodemother.service.user;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.model.dto.user.UserQueryRequest;
import com.rush.rushaicodemother.model.vo.AdminUserVO;
import com.rush.rushaicodemother.model.vo.PublicUserSummaryVO;

import java.util.Collection;
import java.util.Map;

/** 用户只读目录，为控制器和其他业务模块提供脱敏用户视图。 */
public interface UserDirectoryService {

    /** 查询未删除用户视图；用户不存在时返回 {@code null}。 */
    AdminUserVO findActiveAdminView(Long userId);

    /** 查询公开身份摘要；不得返回账号、角色、积分等管理字段。 */
    PublicUserSummaryVO findActivePublicSummary(Long userId);

    /** 批量查询未删除用户视图，并按用户 ID 建立索引。 */
    Map<Long, AdminUserVO> findActiveAdminViews(Collection<Long> userIds);

    /** 批量查询公开身份摘要，并按用户 ID 建立索引。 */
    Map<Long, PublicUserSummaryVO> findActivePublicSummaries(Collection<Long> userIds);

    /** 分页查询未删除用户视图。 */
    Page<AdminUserVO> pageActiveAdminViews(UserQueryRequest queryRequest);
}
