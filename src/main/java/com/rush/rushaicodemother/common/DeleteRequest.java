package com.rush.rushaicodemother.common;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 通用删除请求。 */
@Data
public class DeleteRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 待删除资源的 ID。 */
    @NotNull
    @Positive
    private Long id;
}