package com.rush.rushaicodemother.model.dto.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 更新应用请求。 */
@Data
public class AppUpdateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 应用 ID。 */
    @NotNull
    @Positive
    private Long id;

    /** 应用名称。 */
    @NotBlank
    @Size(max = 50)
    private String appName;
}