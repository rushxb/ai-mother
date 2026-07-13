package com.rush.rushaicodemother.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户登录请求。
 */
@Data
public class UserLoginRequest implements Serializable {

    private static final long serialVersionUID = 3191241716373120793L;

    @NotBlank
    @Size(min = 4, max = 256)
    private String userAccount;

    @NotBlank
    @Size(min = 8, max = 72)
    private String userPassword;
}
