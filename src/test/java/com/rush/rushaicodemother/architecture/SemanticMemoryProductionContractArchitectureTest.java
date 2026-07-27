package com.rush.rushaicodemother.architecture;

import com.rush.rushaicodemother.memory.SemanticMemoryContract;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticMemoryProductionContractArchitectureTest {

    @Test
    void migrationsMustProvideLeasedDeletionAndVersionedReplayContracts() throws Exception {
        String outboxMigration = normalized(Path.of(
                "sql/migrations/V20260721_1__semantic_memory_production_contract.sql"));
        String replayMigration = normalized(Path.of(
                "sql/migrations/V20260721_2__semantic_memory_v2_reindex_contract.sql"));
        String bootstrap = normalized(Path.of("sql/create_table.sql"));

        assertTrue(outboxMigration.contains("semantic_memory_deletion_outbox"));
        assertTrue(outboxMigration.contains("memoryindexleaseowner"));
        assertTrue(outboxMigration.contains("idx_memory_outbox_claim"));
        assertTrue(replayMigration.contains("memoryindexcontractversion"));
        assertTrue(replayMigration.contains("idx_memory_outbox_contract_claim"));
        assertTrue(replayMigration.contains("chk_generation_task_memory_contract_version"));
        assertFalse(replayMigration.contains("set memoryindexedat = null"),
                "contract upgrades must not require a blocking bulk rewrite of task history");
        assertTrue(bootstrap.contains("memoryindexcontractversion int"));
        assertTrue(bootstrap.contains("semantic_memory_deletion_outbox"));
    }

    @Test
    void productionProfileMustFailClosedOnInsecureOrUnverifiedMilvus() throws Exception {
        String production = normalized(Path.of("src/main/resources/application-prod.yml"));
        String defaults = normalized(Path.of("src/main/resources/application.yml"));

        assertTrue(production.contains("authentication-required: ${milvus_authentication_required:true}"));
        assertTrue(production.contains("tls-required: ${milvus_tls_required:true}"));
        assertTrue(production.contains("verify-on-startup: ${milvus_verify_on_startup:true}"));
        assertTrue(production.contains("enabled: ${generation_memory_outbox_enabled:true}"));
        assertTrue(defaults.contains("collection-name: ${milvus_memory_collection:generation_memory_v2}"));
        assertEquals(3, SemanticMemoryContract.INDEX_VERSION);
    }

    private String normalized(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
