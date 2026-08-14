package com.rush.rushaicodemother.service.browser;

import com.rush.rushaicodemother.infrastructure.diagnostic.PublicDiagnosticSanitizer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从真实浏览器观测映射出的运行时、网络和视觉结论。 */
public record BrowserRuntimeValidationResult(
        long durationMs,
        boolean visualEvidenceRequired,
        List<String> runtimeViolations,
        List<String> visualViolations,
        List<String> diagnostics,
        Map<String, Object> evidenceDetails
) {

    public BrowserRuntimeValidationResult {
        durationMs = Math.max(0, durationMs);
        runtimeViolations = normalize(runtimeViolations);
        visualViolations = normalize(visualViolations);
        diagnostics = normalize(diagnostics).stream().limit(20).toList();
        evidenceDetails = evidenceDetails == null ? Map.of() : Map.copyOf(evidenceDetails);
    }

    public static BrowserRuntimeValidationResult failed(long durationMs, String violation) {
        return failed(durationMs, violation, false);
    }

    public static BrowserRuntimeValidationResult failed(
            long durationMs,
            String violation,
            boolean visualEvidenceRequired
    ) {
        return new BrowserRuntimeValidationResult(
                durationMs,
                visualEvidenceRequired,
                List.of(violation),
                visualEvidenceRequired
                        ? List.of("browser_visual_evidence_missing")
                        : List.of(),
                List.of(),
                Map.of("runtimeKind", "browser_console_network")
        );
    }

    public boolean runtimePassed() {
        return runtimeViolations.isEmpty();
    }

    public boolean visualPassed() {
        return visualViolations.isEmpty();
    }

    public boolean passed() {
        return runtimePassed() && (!visualEvidenceRequired || visualPassed());
    }

    public int blockingViolationCount() {
        return runtimeViolations.size()
                + (visualEvidenceRequired ? visualViolations.size() : 0);
    }

    public String failureKind() {
        if (!runtimeViolations.isEmpty()) {
            return runtimeViolations.getFirst();
        }
        if (visualEvidenceRequired && !visualViolations.isEmpty()) {
            return visualViolations.getFirst();
        }
        return "NONE";
    }

    public String summary() {
        return passed()
                ? "浏览器 console/network 运行时验证通过"
                : "浏览器运行时验证未通过: " + failureKind();
    }

    public Map<String, Object> toEventData() {
        Map<String, Object> data = new LinkedHashMap<>(evidenceDetails);
        data.put("status", passed() ? "PASS" : "FAILED");
        data.put("failureKind", failureKind());
        data.put("durationMs", durationMs);
        data.put("runtimeViolations", runtimeViolations);
        data.put("visualViolations", visualViolations);
        data.put("visualEvidenceRequired", visualEvidenceRequired);
        return Map.copyOf(data);
    }

    public String toPublicRepairDiagnostic() {
        StringBuilder diagnostic = new StringBuilder();
        diagnostic.append("browserValidationStatus=")
                .append(passed() ? "PASS" : "FAILED").append('\n');
        diagnostic.append("browserFailureKind=").append(failureKind()).append('\n');
        diagnostic.append("browserRuntimeViolations=").append(runtimeViolations).append('\n');
        diagnostic.append("browserVisualViolations=").append(visualViolations).append('\n');
        diagnostics.forEach(value -> diagnostic.append("- ").append(value).append('\n'));
        return PublicDiagnosticSanitizer.sanitizeForPublicOutput(
                diagnostic.toString().trim(), 8_000);
    }

    private static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
