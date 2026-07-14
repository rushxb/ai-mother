package com.rush.rushaicodemother.service.user;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.model.dto.user.UserQueryRequest;
import com.rush.rushaicodemother.model.entity.User;

import java.util.Collection;
import java.util.List;

/**
 * 用户持久化边界。
 *
 * <p>仅暴露用户业务场景所需的查询和字段级写入，禁止上层依赖 MyBatis-Flex
 * 通用 CRUD，也禁止调用方传入任意实体决定更新字段。</p>
 */
public interface UserPersistenceService {

    /** 查询未删除用户。 */
    User findActiveById(Long userId);

    /** 按账号查询未删除用户。 */
    User findActiveByAccount(String userAccount);

    /** 批量查询未删除用户；空值和非法 ID 会在边界内统一过滤。 */
    List<User> findActiveByIds(Collection<Long> userIds);

    /** 按白名单条件分页查询未删除用户。 */
    Page<User> pageActiveUsers(UserQueryRequest queryRequest);

    /** 创建用户并返回用户 ID。 */
    long createUser(NewUser newUser);

    /** 更新管理员允许编辑的用户字段。 */
    void updateAdministrationFields(Long userId,
                                    String userName,
                                    String userAvatar,
                                    String userProfile,
                                    String userRole);

    /** 更新未删除用户的密码哈希。 */
    void updatePasswordHash(Long userId, String passwordHash);

    /** 逻辑删除未删除用户。 */
    void logicallyDelete(Long userId);

    /**
     * 受控的新用户写入模型。
     *
     * <p>该不可变模型只包含创建场景允许写入的字段，避免把完整持久化实体作为写入契约。</p>
     */
    record NewUser(String userAccount,
                   String passwordHash,
                   String userName,
                   String userAvatar,
                   String userProfile,
                   String userRole,
                   long creditBalance) {
    }
}
