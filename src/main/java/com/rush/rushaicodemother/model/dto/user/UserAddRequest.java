package com.rush.rushaicodemother.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理员创建用户请求。
 */
@Data
public class UserAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Size(max = 256)
    private String userName;

    @NotBlank
    @Size(min = 4, max = 256)
    private String userAccount;

    @NotBlank
    @Size(min = 8, max = 72)
    private String userPassword;

    @Size(max = 1024)
    private String userAvatar;

    @Size(max = 512)
    private String userProfile;

    @Pattern(regexp = "user|admin", message = "用户角色只能是 user 或 admin")
    private String userRole;

    @PositiveOrZero
    private Long creditBalance;
}
