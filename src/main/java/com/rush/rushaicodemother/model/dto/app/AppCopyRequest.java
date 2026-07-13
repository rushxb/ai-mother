package com.rush.rushaicodemother.model.dto.app;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 应用复制请求。 */
@Data
public class AppCopyRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 源应用 ID。 */
    @NotNull
    @Positive
    private Long sourceAppId;
}