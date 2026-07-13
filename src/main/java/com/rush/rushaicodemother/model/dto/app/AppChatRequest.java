package com.rush.rushaicodemother.model.dto.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 应用对话生成请求。 */
@Data
public class AppChatRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 应用 ID。 */
    @NotNull
    @Positive
    private Long appId;

    /** 用户消息。 */
    @NotBlank
    @Size(max = 20_000)
    private String message;
}