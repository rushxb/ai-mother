package com.rush.rushaicodemother.model.dto.prompt;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提示词发布发布请求参数。
 */
@Data
public class PromptReleasePublishRequest {

    /** 提示词唯一键。 */
    @NotBlank
    @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,63}")
    private String promptKey;

    /** 稳定版本。 */
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,31}")
    private String stableVersion;

    /** 灰度版本。 */
    @Size(max = 32)
    private String canaryVersion;

    /** 灰度发布比例。 */
    @NotNull
    @Min(0)
    @Max(100)
    private Integer canaryPercentage;

    /** 预期修订版本。 */
    @NotNull
    @PositiveOrZero
    private Long expectedRevision;

    /** 变更说明。 */
    @NotBlank
    @Size(max = 512)
    private String changeNote;

    /** 证据编号。 */
    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F-]{36}")
    private String evidenceId;
}
