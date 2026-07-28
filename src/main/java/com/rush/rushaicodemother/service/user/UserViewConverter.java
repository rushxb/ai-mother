package com.rush.rushaicodemother.service.user;

import cn.hutool.core.bean.BeanUtil;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.LoginUserVO;
import com.rush.rushaicodemother.model.vo.UserVO;
import org.springframework.stereotype.Component;

/** 用户实体脱敏视图转换器。 */
@Component
public class UserViewConverter {

    /**
 * 将当前对象转换为用户视图。
 *
 * @param user 用户
 * @return 用户视图
 */
    public UserVO toUserView(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtil.copyProperties(user, userVO);
        return userVO;
    }

    /**
 * 将当前对象转换为{@code Login}用户视图。
 *
 * @param user 用户
 * @return {@code Login}用户视图
 */
    public LoginUserVO toLoginUserView(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtil.copyProperties(user, loginUserVO);
        return loginUserVO;
    }
}
