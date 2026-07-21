package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerSessionPersistenceBoundaryArchitectureTest {

    @Test
    void serviceLayerMustDependOnTheRegistryPortInsteadOfMyBatisDetails() throws Exception {
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/rush/rushaicodemother/service/devserver/"
                        + "DurableDevServerSessionLeaseCoordinator.java"));
        String recovery = Files.readString(Path.of(
                "src/main/java/com/rush/rushaicodemother/service/devserver/"
                        + "DevServerSessionRecoveryService.java"));

        assertTrue(coordinator.contains("DevServerSessionRegistry"));
        assertTrue(recovery.contains("DevServerSessionRegistry"));
        assertFalse(coordinator.contains("DevServerSessionMapper"));
        assertFalse(recovery.contains("DevServerSessionMapper"));
    }

    @Test
    void migrationMustPersistFencingAndSandboxRecoveryMetadata() throws Exception {
        String migration = Files.readString(Path.of(
                "sql/migrations/V20260717_4__dev_server_session.sql"));

        for (String contract : new String[]{
                "create table if not exists dev_server_session",
                "nodeId",
                "leaseOwner",
                "cleanupResourceIds",
                "leaseUntil",
                "version",
                "idx_dev_server_session_user_state_lease",
                "idx_dev_server_session_state_lease"
        }) {
            assertTrue(migration.contains(contract), "migration missing contract: " + contract);
        }
    }
}
