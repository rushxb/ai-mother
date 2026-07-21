package com.rush.rushaicodemother.model.dto.prompt;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PromptReleasePublishRequest {

    @NotBlank
    @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,63}")
    private String promptKey;

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,31}")
    private String stableVersion;

    @Size(max = 32)
    private String canaryVersion;

    @NotNull
    @Min(0)
    @Max(100)
    private Integer canaryPercentage;

    @NotNull
    @PositiveOrZero
    private Long expectedRevision;

    @NotBlank
    @Size(max = 512)
    private String changeNote;

    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F-]{36}")
    private String evidenceId;
}
