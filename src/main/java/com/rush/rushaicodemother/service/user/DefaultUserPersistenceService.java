package com.rush.rushaicodemother.service.user;

import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.rush.rushaicodemother.common.query.SortFieldWhitelist;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.mapper.UserMapper;
import com.rush.rushaicodemother.model.dto.user.UserQueryRequest;
import com.rush.rushaicodemother.model.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** MyBatis-Flex 用户持久化实现。 */
@Service
@RequiredArgsConstructor
public class DefaultUserPersistenceService implements UserPersistenceService {

    private static final SortFieldWhitelist SORT_FIELDS = SortFieldWhitelist.of("createTime", Map.of(
            "id", "id",
            "userAccount", "userAccount",
            "userName", "userName",
            "userRole", "userRole",
            "creditBalance", "creditBalance",
            "createTime", "createTime",
            "updateTime", "updateTime"
    ));

    private final UserMapper userMapper;

    /**
 * 查找匹配的活动按编号。
 *
 * @param userId 用户编号
 * @return 活动按编号
 */
    @Override
    public User findActiveById(Long userId) {
        validateUserId(userId);
        return userMapper.selectActiveById(userId);
    }

    /**
 * 查找匹配的活动按{@code Account}。
 *
 * @param userAccount {@code userAccount} 对应的调用参数
 * @return 活动按{@code Account}
 */
    @Override
    public User findActiveByAccount(String userAccount) {
        ThrowUtils.throwIf(StrUtil.isBlank(userAccount), ErrorCode.PARAMS_ERROR, "用户账号不能为空");
        return userMapper.selectActiveByAccount(userAccount);
    }

    /**
 * 查找匹配的活动按{@code Ids}。
 *
 * @param userIds 待处理的 {@code userIds} 集合
 * @return 活动按{@code Ids}集合
 */
    @Override
    public List<User> findActiveByIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<Long> validUserIds = userIds.stream()
                .filter(userId -> userId != null && userId > 0)
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf
                ));
        if (validUserIds.isEmpty()) {
            return List.of();
        }
        return userMapper.selectActiveByIds(validUserIds);
    }

    /**
 * 返回{@code page}活动{@code Users}。
 *
 * @param queryRequest 查询请求
 * @return 默认用户持久化
 */
    @Override
    public Page<User> pageActiveUsers(UserQueryRequest queryRequest) {
        ThrowUtils.throwIf(queryRequest == null, ErrorCode.PARAMS_ERROR, "查询条件不能为空");
        ThrowUtils.throwIf(queryRequest.getPageNum() <= 0 || queryRequest.getPageSize() <= 0,
                ErrorCode.PARAMS_ERROR, "分页参数必须大于 0");
        return userMapper.paginate(
                Page.of(queryRequest.getPageNum(), queryRequest.getPageSize()),
                buildQueryWrapper(queryRequest)
        );
    }

    /**
 * 创建用户。
 *
 * @param newUser {@code newUser} 对应的调用参数
 * @return 计算或处理后的数值结果
 */
    @Override
    public long createUser(NewUser newUser) {
        validateNewUser(newUser);
        User user = User.builder()
                .userAccount(newUser.userAccount())
                .userPassword(newUser.passwordHash())
                .userName(newUser.userName())
                .userAvatar(newUser.userAvatar())
                .userProfile(newUser.userProfile())
                .userRole(newUser.userRole())
                .creditBalance(newUser.creditBalance())
                .build();
        try {
            requireInserted(userMapper.insertUser(user));
            if (user.getId() == null || user.getId() <= 0) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "用户创建失败，未返回用户 ID");
            }
            return user.getId();
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复", exception);
        }
    }

    /**
 * 更新{@code Administration}{@code Fields}。
 *
 * @param userId 用户编号
 * @param userName 用户名称
 * @param userAvatar {@code userAvatar} 对应的调用参数
 * @param userProfile 用户配置档
 * @param userRole 用户角色
 */
    @Override
    public void updateAdministrationFields(Long userId,
                                           String userName,
                                           String userAvatar,
                                           String userProfile,
                                           String userRole) {
        validateUserId(userId);
        ThrowUtils.throwIf(userName == null && userAvatar == null && userProfile == null && userRole == null,
                ErrorCode.PARAMS_ERROR, "至少提供一个待更新字段");
        requireUpdatedActiveUser(userMapper.updateActiveAdministrationFields(
                userId,
                userName,
                userAvatar,
                userProfile,
                userRole
        ));
    }

    /**
 * 更新密码哈希。
 *
 * @param userId 用户编号
 * @param passwordHash 密码哈希
 */
    @Override
    public void updatePasswordHash(Long userId, String passwordHash) {
        validateUserId(userId);
        ThrowUtils.throwIf(StrUtil.isBlank(passwordHash), ErrorCode.PARAMS_ERROR, "密码哈希不能为空");
        requireUpdatedActiveUser(userMapper.updateActivePasswordHash(userId, passwordHash));
    }

    /**
 * 处理{@code logically}删除。
 *
 * @param userId 用户编号
 */
    @Override
    public void logicallyDelete(Long userId) {
        validateUserId(userId);
        requireUpdatedActiveUser(userMapper.logicallyDeleteActiveUser(userId));
    }

    /** 构建并返回查询{@code Wrapper}。 */
    private QueryWrapper buildQueryWrapper(UserQueryRequest queryRequest) {
        String sortField = SORT_FIELDS.resolve(queryRequest.getSortField());
        boolean ascending = "ascend".equals(queryRequest.getSortOrder());
        Long id = queryRequest.getId();
        return QueryWrapper.create()
                .eq("id", id, id != null)
                .eq("userRole", queryRequest.getUserRole(), StrUtil.isNotBlank(queryRequest.getUserRole()))
                .like("userAccount", queryRequest.getUserAccount(), StrUtil.isNotBlank(queryRequest.getUserAccount()))
                .like("userName", queryRequest.getUserName(), StrUtil.isNotBlank(queryRequest.getUserName()))
                .like("userProfile", queryRequest.getUserProfile(), StrUtil.isNotBlank(queryRequest.getUserProfile()))
                .eq("isDelete", 0)
                .orderBy(sortField, ascending);
    }

    private void validateNewUser(NewUser newUser) {
        ThrowUtils.throwIf(newUser == null, ErrorCode.PARAMS_ERROR, "用户创建参数不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(newUser.userAccount()), ErrorCode.PARAMS_ERROR, "用户账号不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(newUser.passwordHash()), ErrorCode.PARAMS_ERROR, "密码哈希不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(newUser.userRole()), ErrorCode.PARAMS_ERROR, "用户角色不能为空");
        ThrowUtils.throwIf(newUser.creditBalance() < 0, ErrorCode.PARAMS_ERROR, "初始积分不能小于 0");
    }

    private void requireInserted(int affectedRows) {
        if (affectedRows != 1) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "用户创建失败，请稍后重试");
        }
    }

    /** 校验并返回有效的{@code Updated}活动用户。 */
    private void requireUpdatedActiveUser(int affectedRows) {
        if (affectedRows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在或已删除");
        }
        if (affectedRows != 1) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "用户数据更新异常");
        }
    }

    private void validateUserId(Long userId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户 ID 不合法");
    }
}
