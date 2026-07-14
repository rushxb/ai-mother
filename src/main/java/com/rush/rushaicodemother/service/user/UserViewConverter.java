package com.rush.rushaicodemother.service.user;

import cn.hutool.core.bean.BeanUtil;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.LoginUserVO;
import com.rush.rushaicodemother.model.vo.UserVO;
import org.springframework.stereotype.Component;

/** 用户实体脱敏视图转换器。 */
@Component
public class UserViewConverter {

    public UserVO toUserView(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtil.copyProperties(user, userVO);
        return userVO;
    }

    public LoginUserVO toLoginUserView(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtil.copyProperties(user, loginUserVO);
        return loginUserVO;
    }
}
