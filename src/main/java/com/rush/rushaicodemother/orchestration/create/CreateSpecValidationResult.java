package com.rush.rushaicodemother.orchestration.create;

import java.util.List;

public record CreateSpecValidationResult(
        boolean valid,
        List<String> warnings,
        List<String> errors
) {
    public static CreateSpecValidationResult ok(List<String> warnings) {
        return new CreateSpecValidationResult(true, warnings == null ? List.of() : List.copyOf(warnings), List.of());
    }
}
