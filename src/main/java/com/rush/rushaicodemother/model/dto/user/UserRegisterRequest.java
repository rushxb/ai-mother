package com.rush.rushaicodemother.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户注册请求。
 */
@Data
public class UserRegisterRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    @Size(min = 4, max = 256)
    private String userAccount;

    @NotBlank
    @Size(min = 8, max = 72)
    private String userPassword;

    @NotBlank
    @Size(min = 8, max = 72)
    private String checkPassword;
}
