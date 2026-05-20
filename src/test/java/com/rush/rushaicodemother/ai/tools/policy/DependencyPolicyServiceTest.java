package com.rush.rushaicodemother.ai.tools.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyPolicyServiceTest {

    private final DependencyPolicyService service = new DependencyPolicyService();

    @Test
    void validateAddOrUpdateShouldAllowScopedPackageWithReason() {
        DependencyPolicyService.PolicyDecision decision = service.validateAddOrUpdate(
                "@vueuse/core", "^10.0.0", "dependencies", "用于组合式工具函数"
        );

        assertTrue(decision.allowed());
    }

    @Test
    void validateAddOrUpdateShouldRejectIllegalPackageName() {
        DependencyPolicyService.PolicyDecision decision = service.validateAddOrUpdate(
                "https://example.com/pkg", "latest", "dependencies", "测试"
        );

        assertFalse(decision.allowed());
    }

    @Test
    void validateAddOrUpdateShouldRejectMissingReason() {
        DependencyPolicyService.PolicyDecision decision = service.validateAddOrUpdate(
                "marked", "^12.0.0", "dependencies", ""
        );

        assertFalse(decision.allowed());
    }

    @Test
    void validateAddOrUpdateShouldRejectInvalidDependencyType() {
        DependencyPolicyService.PolicyDecision decision = service.validateAddOrUpdate(
                "marked", "^12.0.0", "peerDependencies", "渲染 markdown"
        );

        assertFalse(decision.allowed());
    }

    @Test
    void validateScriptShouldRejectLifecycleScript() {
        DependencyPolicyService.PolicyDecision decision = service.validateScript("postinstall", "node setup.js");

        assertFalse(decision.allowed());
    }

    @Test
    void validateScriptShouldRejectDangerousCommand() {
        DependencyPolicyService.PolicyDecision decision = service.validateScript("clean", "rm -rf /");

        assertFalse(decision.allowed());
    }

    @Test
    void validateScriptShouldAllowViteBuild() {
        DependencyPolicyService.PolicyDecision decision = service.validateScript("build", "vite build");

        assertTrue(decision.allowed());
    }
}
