package com.rush.rushaicodemother.model.dto.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 应用代码文件保存请求。 */
@Data
public class AppCodeFileSaveRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 应用 ID。 */
    @NotNull
    @Positive
    private Long appId;

    /** 相对于应用代码根目录的文件路径。 */
    @NotBlank
    @Size(max = 1024)
    private String filePath;

    /** 文件内容，允许为空字符串以清空文件。 */
    @NotNull
    @Size(max = 1_048_576)
    private String content;
}