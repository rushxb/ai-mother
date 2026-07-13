package com.rush.rushaicodemother.model.dto.app;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 停止应用生成请求。 */
@Data
public class AppStopRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 应用 ID。 */
    @NotNull
    @Positive
    private Long appId;
}