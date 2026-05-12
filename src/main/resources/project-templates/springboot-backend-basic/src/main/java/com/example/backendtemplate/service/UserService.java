package com.example.backendtemplate.service;

import com.example.backendtemplate.model.dto.user.UserQueryRequest;
import com.example.backendtemplate.model.entity.User;
import com.example.backendtemplate.model.vo.LoginUserVO;
import com.example.backendtemplate.model.vo.UserVO;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.IService;
import jakarta.servlet.http.HttpServletRequest;

public interface UserService extends IService<User> {

    long userRegister(String userAccount, String userPassword, String checkPassword);

    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    User getLoginUser(HttpServletRequest request);

    boolean userLogout(HttpServletRequest request);

    QueryWrapper getQueryWrapper(UserQueryRequest userQueryRequest);

    UserVO getUserVO(User user);

    LoginUserVO getLoginUserVO(User user);
}
