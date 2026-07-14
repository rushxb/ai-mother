package com.rush.rushaicodemother.mapper;

import com.mybatisflex.core.BaseMapper;
import com.rush.rushaicodemother.model.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 用户 映射层。
 *
 *
 */
public interface UserMapper extends BaseMapper<User> {

    @Select("""
            SELECT id, userAccount, userPassword, userName, userAvatar, userProfile,
                   userRole, creditBalance, editTime, createTime, updateTime, isDelete
            FROM `user`
            WHERE id = #{userId}
              AND isDelete = 0
            """)
    User selectActiveById(@Param("userId") Long userId);

    @Select("""
            SELECT id, userAccount, userPassword, userName, userAvatar, userProfile,
                   userRole, creditBalance, editTime, createTime, updateTime, isDelete
            FROM `user`
            WHERE userAccount = #{userAccount}
              AND isDelete = 0
            """)
    User selectActiveByAccount(@Param("userAccount") String userAccount);

    List<User> selectActiveByIds(@Param("userIds") Collection<Long> userIds);

    @Insert("""
            INSERT INTO `user` (
                userAccount, userPassword, userName, userAvatar, userProfile, userRole, creditBalance
            ) VALUES (
                #{userAccount}, #{userPassword}, #{userName}, #{userAvatar}, #{userProfile},
                #{userRole}, #{creditBalance}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertUser(User user);

    int updateActiveAdministrationFields(@Param("userId") Long userId,
                                         @Param("userName") String userName,
                                         @Param("userAvatar") String userAvatar,
                                         @Param("userProfile") String userProfile,
                                         @Param("userRole") String userRole);

    @Update("""
            UPDATE `user`
            SET userPassword = #{passwordHash},
                editTime = CURRENT_TIMESTAMP
            WHERE id = #{userId}
              AND isDelete = 0
            """)
    int updateActivePasswordHash(@Param("userId") Long userId,
                                 @Param("passwordHash") String passwordHash);

    @Update("""
            UPDATE `user`
            SET isDelete = 1,
                editTime = CURRENT_TIMESTAMP
            WHERE id = #{userId}
              AND isDelete = 0
            """)
    int logicallyDeleteActiveUser(@Param("userId") Long userId);

}
