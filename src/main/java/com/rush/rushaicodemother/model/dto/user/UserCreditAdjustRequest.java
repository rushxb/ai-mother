package com.rush.rushaicodemother.model.dto.user;

import com.rush.rushaicodemother.common.validation.NonZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 管理员调整用户积分请求。 */
@Data
public class UserCreditAdjustRequest implements Serializable {

    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
                    + "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank
    @Pattern(regexp = UUID_PATTERN)
    private String requestId;

    @NotNull
    @Positive
    private Long userId;

    @NotNull
    @NonZero
    private Long changeAmount;

    @NotBlank
    @Size(max = 512)
    private String remark;
}
