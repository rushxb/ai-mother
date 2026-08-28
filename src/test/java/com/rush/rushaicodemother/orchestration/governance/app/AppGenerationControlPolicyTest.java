package com.rush.rushaicodemother.orchestration.governance.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppGenerationControlPolicyTest {

    @Test
    void absentPolicyMustResolveToSafePlatformDefaults() {
        AppGenerationControlPolicy policy = AppGenerationControlPolicy.defaults(11L);

        assertEquals(11L, policy.appId());
        assertEquals(0L, policy.version());
        assertFalse(policy.generationPaused());
        assertFalse(policy.emergencyStopped());
        assertEquals(1, policy.maxConcurrentTasks());
        assertEquals(AppGenerationControlPolicy.ModelPolicy.PLATFORM_DEFAULT, policy.modelPolicy());
        assertEquals(AppGenerationControlPolicy.DependencyMutationPolicy.ALLOW, policy.dependencyMutationPolicy());
        assertEquals(AppGenerationControlPolicy.DependencyNetworkPolicy.TRUSTED_REGISTRY_ONLY,
                policy.dependencyNetworkPolicy());
        assertEquals(AppGenerationControlPolicy.DangerousToolPolicy.REQUIRE_APPROVAL,
                policy.dangerousToolPolicy());
        assertNull(policy.monthlyCreditLimit());
    }

    @Test
    void unsafeConcurrencyAndMalformedBudgetMustFailClosed() {
        AppGenerationControlPolicy defaults = AppGenerationControlPolicy.defaults(11L);

        assertThrows(IllegalArgumentException.class, () -> new AppGenerationControlPolicy(
                11L, 1L, false, false, 2, defaults.modelPolicy(),
                defaults.dependencyMutationPolicy(), defaults.dependencyNetworkPolicy(),
                defaults.dangerousToolPolicy(), null, 7L, null));
        assertThrows(IllegalArgumentException.class, () -> new AppGenerationControlPolicy(
                11L, 1L, false, false, 1, defaults.modelPolicy(),
                defaults.dependencyMutationPolicy(), defaults.dependencyNetworkPolicy(),
                defaults.dangerousToolPolicy(), -1L, 7L, null));
    }
}
