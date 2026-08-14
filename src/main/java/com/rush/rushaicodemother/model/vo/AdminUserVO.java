package com.rush.rushaicodemother.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 仅管理员接口可返回的用户管理视图。 */
@Data
public class AdminUserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String userAccount;

    private String userName;

    private String userAvatar;

    private String userProfile;

    private String userRole;

    private Long creditBalance;

    private LocalDateTime createTime;
}
