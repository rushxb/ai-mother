package com.rush.rushaicodemother.model.dto.app;

import com.rush.rushaicodemother.common.PageRequest;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * 应用分页查询条件。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class AppQueryRequest extends PageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Positive
    private Long id;

    @Size(max = 256)
    private String appName;

    @Size(max = 1024)
    private String cover;

    @Size(max = 20_000)
    private String initPrompt;

    @Pattern(
            regexp = "html|multi_file|vue_project|backend_project|full_stack_project",
            message = "代码生成类型不合法"
    )
    private String codeGenType;

    @Size(max = 128)
    private String deployKey;

    private Integer priority;

    @Positive
    private Long userId;
}