package com.rush.rushaicodemother.model.dto.prompt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提示词发布回滚请求参数。
 */
@Data
public class PromptReleaseRollbackRequest {

    /** 提示词唯一键。 */
    @NotBlank
    @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,63}")
    private String promptKey;

    @NotNull
    @Positive
    private Long targetRevision;

    /** 预期修订版本。 */
    @NotNull
    @PositiveOrZero
    private Long expectedRevision;

    /** 变更说明。 */
    @NotBlank
    @Size(max = 512)
    private String changeNote;
}
