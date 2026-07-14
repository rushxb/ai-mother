package com.rush.rushaicodemother.controller;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.DeleteRequest;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.dto.user.UserAddRequest;
import com.rush.rushaicodemother.model.dto.user.UserCreditAdjustRequest;
import com.rush.rushaicodemother.model.dto.user.UserLoginRequest;
import com.rush.rushaicodemother.model.dto.user.UserQueryRequest;
import com.rush.rushaicodemother.model.dto.user.UserRegisterRequest;
import com.rush.rushaicodemother.model.dto.user.UserUpdateRequest;
import com.rush.rushaicodemother.model.vo.LoginUserVO;
import com.rush.rushaicodemother.model.vo.UserVO;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.credit.AdminCreditAdjustmentCommand;
import com.rush.rushaicodemother.service.user.UserDirectoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口控制器。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/user")
public class UserController {

    private final UserService userService;
    private final UserCreditService userCreditService;
    private final UserDirectoryService userDirectoryService;

    /** 用户注册。 */
    @PostMapping("/register")
    public BaseResponse<Long> userRegister(@Valid @RequestBody UserRegisterRequest userRegisterRequest) {
        long userId = userService.userRegister(
                userRegisterRequest.getUserAccount(),
                userRegisterRequest.getUserPassword(),
                userRegisterRequest.getCheckPassword()
        );
        return ResultUtils.success(userId);
    }

    /** 用户登录。 */
    @PostMapping("/login")
    public BaseResponse<LoginUserVO> userLogin(@Valid @RequestBody UserLoginRequest userLoginRequest,
                                                HttpServletRequest request) {
        LoginUserVO loginUserVO = userService.userLogin(
                userLoginRequest.getUserAccount(),
                userLoginRequest.getUserPassword(),
                request
        );
        return ResultUtils.success(loginUserVO);
    }

    /** 获取当前登录用户。 */
    @GetMapping("/get/login")
    public BaseResponse<LoginUserVO> getLoginUser(HttpServletRequest request) {
        return ResultUtils.success(userService.getLoginUserView(request));
    }

    /** 用户注销。 */
    @PostMapping("/logout")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request) {
        return ResultUtils.success(userService.userLogout(request));
    }

    /** 管理员创建用户，必须显式提供初始密码，禁止使用系统固定默认密码。 */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> addUser(@Valid @RequestBody UserAddRequest userAddRequest,
                                      HttpServletRequest request) {
        long adminUserId = userService.getLoginUserId(request);
        return ResultUtils.success(userService.createUser(userAddRequest, adminUserId));
    }

    /** 管理员根据 ID 获取脱敏用户信息。 */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<UserVO> getUserById(@RequestParam @Positive long id) {
        UserVO userView = userDirectoryService.findActiveUserView(id);
        ThrowUtils.throwIf(userView == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(userView);
    }

    /** 根据 ID 获取脱敏用户信息。 */
    @GetMapping("/get/vo")
    public BaseResponse<UserVO> getUserVOById(@RequestParam @Positive long id) {
        UserVO userView = userDirectoryService.findActiveUserView(id);
        ThrowUtils.throwIf(userView == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(userView);
    }

    /** 管理员删除用户。 */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteUser(@Valid @RequestBody DeleteRequest deleteRequest) {
        userService.deleteUser(deleteRequest.getId());
        return ResultUtils.success(true);
    }

    /** 管理员更新用户。 */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateUser(@Valid @RequestBody UserUpdateRequest userUpdateRequest) {
        userService.updateUser(userUpdateRequest);
        return ResultUtils.success(true);
    }

    /** 管理员调整用户积分。 */
    @PostMapping("/credit/adjust")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> adjustUserCredit(@Valid @RequestBody UserCreditAdjustRequest adjustRequest,
                                                   HttpServletRequest request) {
        long adminUserId = userService.getLoginUserId(request);
        userCreditService.adjustCreditByAdmin(new AdminCreditAdjustmentCommand(
                adjustRequest.getRequestId(),
                adjustRequest.getUserId(),
                adjustRequest.getChangeAmount(),
                adjustRequest.getRemark(),
                adminUserId
        ));
        return ResultUtils.success(true);
    }

    /** 管理员分页获取脱敏用户列表。 */
    @PostMapping("/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<UserVO>> listUserVOByPage(@Valid @RequestBody UserQueryRequest userQueryRequest) {
        Page<UserVO> resultPage = userDirectoryService.pageActiveUserViews(userQueryRequest);
        return ResultUtils.success(resultPage);
    }
}
