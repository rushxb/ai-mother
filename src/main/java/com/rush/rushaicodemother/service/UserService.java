package com.rush.rushaicodemother.service;

import com.rush.rushaicodemother.model.dto.user.UserAddRequest;
import com.rush.rushaicodemother.model.dto.user.UserUpdateRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.LoginUserVO;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户 服务层。
 *
 *
 */
public interface UserService {

    /**
     * 管理员创建用户，并在同一事务中完成初始积分入账。
     *
     * @param request     创建请求
     * @param adminUserId 操作管理员 ID
     * @return 新用户 ID
     */
    long createUser(UserAddRequest request, Long adminUserId);

    /**
     * 管理员更新用户可编辑资料。
     */
    void updateUser(UserUpdateRequest request);

    /**
     * 管理员逻辑删除用户。
     */
    void deleteUser(Long userId);

    /**
     * 用户注册
     *
     * @param userAccount   用户账户
     * @param userPassword  用户密码
     * @param checkPassword 校验密码
     * @return 新用户 id
     */
    long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 用户登录
     *
     * @param userAccount  用户账户
     * @param userPassword 用户密码
     * @param request
     * @return 脱敏后的用户信息
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 获取当前登录用户的脱敏视图。
     */
    LoginUserVO getLoginUserView(HttpServletRequest request);

    /**
     * 获取当前登录用户 ID。
     */
    long getLoginUserId(HttpServletRequest request);

    /**
     * 用户注销
     *
     * @param request
     * @return 退出登录是否成功
     */
    boolean userLogout(HttpServletRequest request);

}
