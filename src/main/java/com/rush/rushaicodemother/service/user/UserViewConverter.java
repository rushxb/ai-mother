package com.rush.rushaicodemother.service.user;

import cn.hutool.core.bean.BeanUtil;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.AdminUserVO;
import com.rush.rushaicodemother.model.vo.LoginUserVO;
import com.rush.rushaicodemother.model.vo.PublicUserSummaryVO;
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
    public AdminUserVO toAdminView(User user) {
        if (user == null) {
            return null;
        }
        AdminUserVO userVO = new AdminUserVO();
        BeanUtil.copyProperties(user, userVO);
        return userVO;
    }

    /** 只复制公开身份字段，禁止使用反射式 Bean 拷贝扩大公开数据面。 */
    public PublicUserSummaryVO toPublicSummary(User user) {
        if (user == null) {
            return null;
        }
        PublicUserSummaryVO summary = new PublicUserSummaryVO();
        summary.setId(user.getId());
        summary.setUserName(user.getUserName());
        summary.setUserAvatar(user.getUserAvatar());
        return summary;
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
