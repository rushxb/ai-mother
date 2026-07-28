package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.dto.user.UserAddRequest;
import com.rush.rushaicodemother.model.dto.user.UserUpdateRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.UserRoleEnum;
import com.rush.rushaicodemother.model.vo.LoginUserVO;
import com.rush.rushaicodemother.security.password.PasswordHashService;
import com.rush.rushaicodemother.security.password.PasswordVerificationResult;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.user.UserPersistenceService;
import com.rush.rushaicodemother.service.user.UserViewConverter;
import com.rush.rushaicodemother.service.tenant.TenantProvisioningService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static com.rush.rushaicodemother.constant.UserConstant.USER_LOGIN_STATE;

/**
 * 用户服务实现。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private static final int MIN_ACCOUNT_LENGTH = 4;
    private static final int MAX_ACCOUNT_LENGTH = 256;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_BCRYPT_PASSWORD_BYTES = 72;
    private final PasswordHashService passwordHashService;
    private final UserCreditService userCreditService;
    private final UserPersistenceService userPersistenceService;
    private final UserViewConverter userViewConverter;
    private final TenantProvisioningService tenantProvisioningService;

    /**
 * 创建用户。
 *
 * @param request 请求参数
 * @param adminUserId 管理端用户编号
 * @return 计算或处理后的数值结果
 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public long createUser(UserAddRequest request, Long adminUserId) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (adminUserId == null || adminUserId <= 0) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "缺少有效的管理员身份");
        }
        if (request == null || StrUtil.isBlank(request.getUserAccount())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户创建参数不完整");
        }
        validateAccount(request.getUserAccount());
        validateRole(request.getUserRole());
        long initialCredit = normalizeInitialCredit(request.getCreditBalance());

        long userId = userPersistenceService.createUser(new UserPersistenceService.NewUser(
                request.getUserAccount(),
                hashPassword(request.getUserPassword()),
                request.getUserName(),
                request.getUserAvatar(),
                request.getUserProfile(),
                StrUtil.blankToDefault(request.getUserRole(), UserRoleEnum.USER.getValue()),
                0L
        ));
        tenantProvisioningService.ensurePersonalTenant(
                userId,
                StrUtil.blankToDefault(request.getUserName(), request.getUserAccount())
        );
        if (initialCredit > 0) {
            // 先以零余额创建账户，再通过积分服务写入初始余额和流水，保证账实一致。
            userCreditService.initializeCredit(userId, initialCredit, adminUserId);
        }
        return userId;
    }

    /**
 * 更新用户。
 *
 * @param request 请求参数
 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUser(UserUpdateRequest request) {
        if (request == null || request.getId() == null || request.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户 ID 不合法");
        }
        validateRole(request.getUserRole());
        if (!hasEditableUserField(request)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "至少提供一个可更新字段");
        }

        userPersistenceService.updateAdministrationFields(
                request.getId(),
                request.getUserName(),
                request.getUserAvatar(),
                request.getUserProfile(),
                request.getUserRole()
        );
    }

    /**
 * 删除用户。
 *
 * @param userId 用户编号
 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户 ID 不合法");
        }
        userPersistenceService.logicallyDelete(userId);
    }

    /**
 * 返回用户{@code Register}。
 *
 * @param userAccount {@code userAccount} 对应的调用参数
 * @param userPassword 用户密码
 * @param checkPassword {@code checkPassword} 对应的调用参数
 * @return 计算或处理后的数值结果
 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        validateRegistration(userAccount, userPassword, checkPassword);

        long userId = userPersistenceService.createUser(new UserPersistenceService.NewUser(
                userAccount,
                hashPassword(userPassword),
                "神秘用户",
                null,
                null,
                UserRoleEnum.USER.getValue(),
                0L
        ));
        tenantProvisioningService.ensurePersonalTenant(userId, userAccount);
        return userId;
    }

    /**
 * 返回用户{@code Login}。
 *
 * @param userAccount {@code userAccount} 对应的调用参数
 * @param userPassword 用户密码
 * @param request 请求参数
 * @return 用户服务{@code Impl}
 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        validateLogin(userAccount, userPassword);

        User user = userPersistenceService.findActiveByAccount(userAccount);
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
        return userViewConverter.toLoginUserView(user);
    }

    /**
 * 获取并返回{@code Login}用户。
 *
 * @param request 请求参数
 * @return 用户服务{@code Impl}
 */
    @Override
    public User getLoginUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Long userId = resolveSessionUserId(session);
        if (userId == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }

        User currentUser = userPersistenceService.findActiveById(userId);
        if (currentUser == null) {
            session.removeAttribute(USER_LOGIN_STATE);
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }

    @Override
    public LoginUserVO getLoginUserView(HttpServletRequest request) {
        return userViewConverter.toLoginUserView(getLoginUser(request));
    }

    @Override
    public long getLoginUserId(HttpServletRequest request) {
        return getLoginUser(request).getId();
    }

    /**
 * 返回用户{@code Logout}。
 *
 * @param request 请求参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean userLogout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(USER_LOGIN_STATE) == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "用户未登录");
        }
        session.removeAttribute(USER_LOGIN_STATE);
        return true;
    }

    /** 判断是否存在{@code h}密码。 */
    private String hashPassword(String userPassword) {
        validatePasswordLength(userPassword);
        try {
            return passwordHashService.hash(userPassword);
        } catch (IllegalArgumentException exception) {
            log.error("Password hashing failed after application-level validation",
                    LogExceptionSanitizer.sanitize(exception));
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "密码安全处理失败，请稍后重试",
                    exception
            );
        }
    }

    /** 校验{@code ate}{@code Registration}是否有效。 */
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

    /** 校验{@code ate}{@code Login}是否有效。 */
    private void validateLogin(String userAccount, String userPassword) {
        if (StrUtil.hasBlank(userAccount, userPassword)) {
            throw invalidCredentialsException();
        }
        if (userAccount.length() < MIN_ACCOUNT_LENGTH || userAccount.length() > MAX_ACCOUNT_LENGTH) {
            throw invalidCredentialsException();
        }
        validatePasswordLength(userPassword);
    }

    /** 校验{@code ate}{@code Account}是否有效。 */
    private void validateAccount(String userAccount) {
        if (userAccount.length() < MIN_ACCOUNT_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号长度不能少于 4 位");
        }
        if (userAccount.length() > MAX_ACCOUNT_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号长度不能超过 256 位");
        }
    }

    private void validateRole(String userRole) {
        if (StrUtil.isNotBlank(userRole) && UserRoleEnum.getEnumByValue(userRole) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户角色只能是 user 或 admin");
        }
    }

    /** 规范化{@code Initial}额度。 */
    private long normalizeInitialCredit(Long creditBalance) {
        if (creditBalance == null) {
            return 0L;
        }
        if (creditBalance < 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "初始积分不能小于 0");
        }
        return creditBalance;
    }

    private boolean hasEditableUserField(UserUpdateRequest request) {
        return request.getUserName() != null
                || request.getUserAvatar() != null
                || request.getUserProfile() != null
                || request.getUserRole() != null;
    }

    /** 校验{@code ate}密码{@code Length}是否有效。 */
    private void validatePasswordLength(String userPassword) {
        if (userPassword == null || userPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码长度不能少于 8 位");
        }
        if (userPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_PASSWORD_BYTES) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码长度不能超过 72 个 UTF-8 字节");
        }
    }

    private void upgradePasswordHash(Long userId, String rawPassword) {
        userPersistenceService.updatePasswordHash(userId, hashPassword(rawPassword));
    }

    /** 根据当前上下文解析会话用户编号。 */
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
