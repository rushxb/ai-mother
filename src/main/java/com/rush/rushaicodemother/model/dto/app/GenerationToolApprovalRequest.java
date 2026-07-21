package com.rush.rushaicodemother.model.dto.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class GenerationToolApprovalRequest {
    @NotBlank
    @Pattern(regexp = "rollbackSnapshot|deleteSnapshot|deleteFile")
    private String action;

    @NotBlank
    @Pattern(regexp = "[a-f0-9]{64}")
    private String approvalId;

    @Pattern(regexp = "approve|reject")
    private String decision = "approve";
}
