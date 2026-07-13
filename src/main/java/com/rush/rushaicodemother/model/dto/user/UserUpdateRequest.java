package com.rush.rushaicodemother.model.dto.user;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理员更新用户请求。
 */
@Data
public class UserUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull
    @Positive
    private Long id;

    @Size(max = 256)
    private String userName;

    @Size(max = 1024)
    private String userAvatar;

    @Size(max = 512)
    private String userProfile;

    @Pattern(regexp = "user|admin", message = "用户角色只能是 user 或 admin")
    private String userRole;

    @PositiveOrZero
    private Long creditBalance;
}
