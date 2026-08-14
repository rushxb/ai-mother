package com.rush.rushaicodemother.orchestration.verification.runtime;

import com.rush.rushaicodemother.infrastructure.diagnostic.PublicDiagnosticSanitizer;
import com.rush.rushaicodemother.service.devserver.DevServerValidationResult;

import java.util.LinkedHashMap;
import java.util.Map;

/** 同一验证窗口内的后端健康、前端 Dev Server 与浏览器联合事实。 */
public record FullStackRuntimeValidationResult(
        BackendRuntimeValidationResult backend,
        DevServerValidationResult frontend,
        long durationMs
) {

    public FullStackRuntimeValidationResult {
        backend = backend == null
                ? BackendRuntimeValidationResult.failed(0, "backend_runtime_result_missing")
                : backend;
        durationMs = Math.max(0, durationMs);
    }

    public boolean passed() {
        return backend.passed()
                && frontend != null
                && frontend.isPassed()
                && frontend.browserValidation() != null
                && frontend.browserValidation().passed();
    }

    public String status() {
        return passed() ? "PASS" : "FAILED";
    }

    public String failureKind() {
        if (!backend.passed()) {
            return backend.failureKind();
        }
        if (frontend == null) {
            return "fullstack_frontend_skipped";
        }
        if (!frontend.isPassed()) {
            if (frontend.browserValidation() != null
                    && !frontend.browserValidation().passed()) {
                return frontend.browserValidation().failureKind();
            }
            return frontend.failureKind().name();
        }
        if (frontend.browserValidation() == null) {
            return "browser_validation_missing";
        }
        return frontend.browserValidation().failureKind();
    }

    public String summary() {
        return passed()
                ? "全栈后端健康与浏览器 console/network 验证通过"
                : "全栈运行时验证未通过: " + failureKind();
    }

    public int criticalErrorCount() {
        if (passed()) {
            return 0;
        }
        int frontendErrors = frontend == null ? 0 : frontend.criticalErrorCount();
        return Math.max(1, frontendErrors + (backend.passed() ? 0 : 1));
    }

    public int warningCount() {
        return frontend == null ? 0 : frontend.warningCount();
    }

    public Map<String, Object> evidenceDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runtimeKind", "fullstack_backend_browser");
        details.put("durationMs", durationMs);
        details.put("backend", backend.evidenceDetails());
        if (frontend != null) {
            details.put("frontend", frontend.toEventData());
        }
        return Map.copyOf(details);
    }

    public String toPublicRepairDiagnostic() {
        StringBuilder diagnostic = new StringBuilder();
        diagnostic.append("validationStage=runtime\n");
        diagnostic.append("runtimeKind=fullstack_backend_browser\n");
        diagnostic.append("status=").append(status()).append('\n');
        diagnostic.append("failureKind=").append(failureKind()).append('\n');
        diagnostic.append("backend:\n")
                .append(backend.toPublicRepairDiagnostic()).append('\n');
        if (frontend != null) {
            diagnostic.append("frontend:\n")
                    .append(frontend.toPublicRepairDiagnostic()).append('\n');
        }
        return PublicDiagnosticSanitizer.sanitizeForPublicOutput(
                diagnostic.toString().trim(), 12_000);
    }
}
