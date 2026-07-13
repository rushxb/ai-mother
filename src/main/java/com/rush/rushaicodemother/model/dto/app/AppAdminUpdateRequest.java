package com.rush.rushaicodemother.model.dto.app;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 管理员更新应用请求。 */
@Data
public class AppAdminUpdateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 应用 ID。 */
    @NotNull
    @Positive
    private Long id;

    /** 应用名称；未提交时保持原值。 */
    @Size(max = 50)
    private String appName;

    /** 应用封面地址；未提交时保持原值。 */
    @Size(max = 1024)
    private String cover;

    /** 精选优先级；未提交时保持原值。 */
    @Min(0)
    @Max(9999)
    private Integer priority;
}