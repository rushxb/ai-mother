package com.rush.rushaicodemother.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.rush.rushaicodemother.common.query.SortFieldWhitelist;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.UserMapper;
import com.rush.rushaicodemother.model.dto.user.UserQueryRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.UserRoleEnum;
import com.rush.rushaicodemother.model.vo.LoginUserVO;
import com.rush.rushaicodemother.model.vo.UserVO;
import com.rush.rushaicodemother.security.password.PasswordHashService;
import com.rush.rushaicodemother.security.password.PasswordVerificationResult;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.rush.rushaicodemother.constant.UserConstant.USER_LOGIN_STATE;

/**
 * 用户服务实现。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final int MIN_ACCOUNT_LENGTH = 4;
    private static final int MAX_ACCOUNT_LENGTH = 256;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_BCRYPT_PASSWORD_BYTES = 72;
    private static final SortFieldWhitelist SORT_FIELDS = SortFieldWhitelist.of("createTime", Map.of(
            "id", "id",
            "userAccount", "userAccount",
            "userName", "userName",
            "userRole", "userRole",
            "creditBalance", "creditBalance",
            "createTime", "createTime",
            "updateTime", "updateTime"
    ));

    private final PasswordHashService passwordHashService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        validateRegistration(userAccount, userPassword, checkPassword);

        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("userAccount", userAccount)
                .eq("isDelete", 0);
        long count = this.mapper.selectCountByQuery(queryWrapper);
        if (count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
        }

        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(hashPassword(userPassword));
        user.setUserName("神秘用户");
        user.setUserRole(UserRoleEnum.USER.getValue());
        boolean saved = this.save(user);
        if (!saved) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "注册失败，请稍后重试");
        }
        return user.getId();
    }

    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtil.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        validateLogin(userAccount, userPassword);

        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("userAccount", userAccount)
                .eq("isDelete", 0);
        User user = this.mapper.selectOneByQuery(queryWrapper);
        if (user == null) {
            throw invalidCredentialsException();
        }

        PasswordVerificationResult verification = passwordHashService.verify(
                userPassword,
                user.getUserPassword()
        );
        if (!verification.matched()) {
            throw invalidCredentialsException();
        }
        if (verification.upgradeRequired()) {
            upgradePasswordHash(user.getId(), userPassword);
        }

        // Session 只保存用户 ID，避免把密码哈希等完整用户数据写入 Redis Session。
        request.getSession(true).setAttribute(USER_LOGIN_STATE, user.getId());
        return getLoginUserVO(user);
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Long userId = resolveSessionUserId(session);
        if (userId == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }

        User currentUser = this.getById(userId);
        if (currentUser == null) {
            session.removeAttribute(USER_LOGIN_STATE);
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtil.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if (CollUtil.isEmpty(userList)) {
            return new ArrayList<>();
        }
        return userList.stream()
                .map(this::getUserVO)
                .toList();
    }

    @Override
    public boolean userLogout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(USER_LOGIN_STATE) == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "用户未登录");
        }
        session.removeAttribute(USER_LOGIN_STATE);
        return true;
    }

    @Override
    public QueryWrapper getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String userAccount = userQueryRequest.getUserAccount();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = SORT_FIELDS.resolve(userQueryRequest.getSortField());
        boolean ascending = "ascend".equals(userQueryRequest.getSortOrder());

        return QueryWrapper.create()
                .eq("id", id, id != null)
                .eq("userRole", userRole, StrUtil.isNotBlank(userRole))
                .like("userAccount", userAccount, StrUtil.isNotBlank(userAccount))
                .like("userName", userName, StrUtil.isNotBlank(userName))
                .like("userProfile", userProfile, StrUtil.isNotBlank(userProfile))
                .orderBy(sortField, ascending);
    }

    @Override
    public String hashPassword(String userPassword) {
        validatePasswordLength(userPassword);
        try {
            return passwordHashService.hash(userPassword);
        } catch (IllegalArgumentException exception) {
            log.error("Password hashing failed after application-level validation", exception);
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "密码安全处理失败，请稍后重试",
                    exception
            );
        }
    }

    @Override
    public void ensureHasCredit(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        Long creditBalance = user.getCreditBalance();
        if (creditBalance == null || creditBalance <= 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "积分不足，请联系管理员充值");
        }
    }

    private void validateRegistration(String userAccount, String userPassword, String checkPassword) {
        if (StrUtil.hasBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        validateAccount(userAccount);
        validatePasswordLength(userPassword);
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }
    }

    private void validateLogin(String userAccount, String userPassword) {
        if (StrUtil.hasBlank(userAccount, userPassword)) {
            throw invalidCredentialsException();
        }
        if (userAccount.length() < MIN_ACCOUNT_LENGTH || userAccount.length() > MAX_ACCOUNT_LENGTH) {
            throw invalidCredentialsException();
        }
        validatePasswordLength(userPassword);
    }

    private void validateAccount(String userAccount) {
        if (userAccount.length() < MIN_ACCOUNT_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号长度不能少于 4 位");
        }
        if (userAccount.length() > MAX_ACCOUNT_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号长度不能超过 256 位");
        }
    }

    private void validatePasswordLength(String userPassword) {
        if (userPassword == null || userPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码长度不能少于 8 位");
        }
        if (userPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_PASSWORD_BYTES) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码长度不能超过 72 个 UTF-8 字节");
        }
    }

    private void upgradePasswordHash(Long userId, String rawPassword) {
        User passwordUpdate = new User();
        passwordUpdate.setId(userId);
        passwordUpdate.setUserPassword(hashPassword(rawPassword));
        if (!this.updateById(passwordUpdate)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "登录凭据升级失败，请稍后重试");
        }
    }

    private Long resolveSessionUserId(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object loginState = session.getAttribute(USER_LOGIN_STATE);
        if (loginState instanceof Long userId && userId > 0) {
            return userId;
        }
        // 兼容旧版本保存完整 User 对象的 Session，并在读取后立即迁移成用户 ID。
        if (loginState instanceof User legacyUser && legacyUser.getId() != null && legacyUser.getId() > 0) {
            session.setAttribute(USER_LOGIN_STATE, legacyUser.getId());
            return legacyUser.getId();
        }
        return null;
    }

    private BusinessException invalidCredentialsException() {
        return new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
    }
}
