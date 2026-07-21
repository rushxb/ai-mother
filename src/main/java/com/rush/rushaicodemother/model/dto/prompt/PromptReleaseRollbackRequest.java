package com.rush.rushaicodemother.model.dto.prompt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PromptReleaseRollbackRequest {

    @NotBlank
    @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,63}")
    private String promptKey;

    @NotNull
    @Positive
    private Long targetRevision;

    @NotNull
    @PositiveOrZero
    private Long expectedRevision;

    @NotBlank
    @Size(max = 512)
    private String changeNote;
}
