package com.rush.rushaicodemother.model.dto.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 生成工具审批请求参数。
 */
@Data
public class GenerationToolApprovalRequest {
    /** 执行动作。 */
    @NotBlank
    @Pattern(regexp = "rollbackSnapshot|deleteSnapshot|deleteFile")
    private String action;

    /** 审批编号。 */
    @NotBlank
    @Pattern(regexp = "[a-f0-9]{64}")
    private String approvalId;

    @Pattern(regexp = "approve|reject")
    private String decision = "approve";
}
