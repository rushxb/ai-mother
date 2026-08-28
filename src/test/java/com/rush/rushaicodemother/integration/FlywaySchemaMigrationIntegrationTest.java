package com.rush.rushaicodemother.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class FlywaySchemaMigrationIntegrationTest {

    private static final String DATABASE = "ai_mother_flyway_it";
    private static final String ADMIN_URL = requiredProperty("integration.mysql.admin-url");
    private static final String USERNAME = requiredProperty("integration.mysql.username");
    private static final String PASSWORD = requiredProperty("integration.mysql.password");
    private static final String JDBC_URL = databaseUrl(ADMIN_URL, DATABASE);

    @BeforeAll
    static void recreateDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
            statement.execute("CREATE DATABASE " + DATABASE
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    @Test
    void emptyDatabaseMustReachCurrentSchemaThroughBaselineAndVersionedMigrations()
            throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(JDBC_URL, USERNAME, PASSWORD)
                .locations("filesystem:sql/migrations")
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load();

        flyway.migrate();

        assertTrue(flyway.validateWithResult().validationSuccessful);
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD)) {
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'generation_tool_approval'
                      AND column_name = 'executionResult'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.table_constraints
                    WHERE constraint_schema = DATABASE()
                      AND table_name = 'generation_tool_approval'
                      AND constraint_name = 'chk_generation_tool_approval_state'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260716.6' AND success = 1
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name = 'dev_server_session'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(DISTINCT index_name)
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name = 'dev_server_session'
                      AND index_name = 'idx_dev_server_session_state_lease'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260717.4' AND success = 1
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'generation_model_call'
                      AND column_name = 'promptTemplateHash'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260717.5' AND success = 1
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name = 'generation_model_prompt_selection'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260828.6' AND success = 1
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name = 'ai_prompt_release'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM ai_prompt_release_bundle
                    WHERE id = 1 AND revision = 0
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260717.6' AND success = 1
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name = 'generation_benchmark_evidence'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'ai_prompt_release_history'
                      AND column_name = 'evidenceId'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name = 'ai_release_audit'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260718.2' AND success = 1
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260718.3' AND success = 1
                    """));
            assertEquals(2, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'generation_benchmark_evidence'
                      AND column_name IN (
                          'signatureVersion', 'candidatePhysicalRequestCount')
                      AND is_nullable = 'NO'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260723.1' AND success = 1
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'generation_task'
                      AND column_name = 'executionEpoch'
                      AND is_nullable = 'NO'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'app'
                      AND column_name = 'generationExecutionEpoch'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'generation_orchestration_checkpoint'
                      AND column_name = 'executionEpoch'
                      AND is_nullable = 'NO'
                    """));
            assertEquals(3, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.table_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name IN (
                          'chk_generation_task_execution_epoch',
                          'chk_app_generation_execution_epoch',
                          'chk_generation_orchestration_execution_epoch'
                      )
                      AND constraint_type = 'CHECK'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260718.6' AND success = 1
                    """));
            assertEquals(2, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'generation_task'
                      AND column_name IN ('idempotencyKeyHash', 'requestFingerprint')
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(DISTINCT index_name)
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name = 'generation_task'
                      AND index_name = 'uk_generation_task_submission_idempotency'
                      AND non_unique = 0
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.table_constraints
                    WHERE constraint_schema = DATABASE()
                      AND table_name = 'generation_task'
                      AND constraint_name = 'chk_generation_task_idempotency_pair'
                      AND constraint_type = 'CHECK'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260718.7' AND success = 1
                    """));
            assertEquals(3, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'ai_model'
                      AND column_name IN ('secretRef', 'secretFingerprint', 'secretKeyId')
                    """));
            assertEquals(0, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'ai_model'
                      AND column_name = 'apiKey'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260720.2' AND success = 1
                    """));
            assertEquals(3, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'generation_task'
                      AND column_name IN (
                          'memoryIndexNextAttemptAt',
                          'memoryIndexLeaseOwner',
                          'memoryIndexLeaseUntil'
                      )
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name = 'semantic_memory_deletion_outbox'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260721.1' AND success = 1
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'generation_task'
                      AND column_name = 'memoryIndexContractVersion'
                      AND is_nullable = 'NO'
                      AND column_default = '0'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(DISTINCT index_name)
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name = 'generation_task'
                      AND index_name = 'idx_memory_outbox_contract_claim'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.table_constraints
                    WHERE constraint_schema = DATABASE()
                      AND table_name = 'generation_task'
                      AND constraint_name = 'chk_generation_task_memory_contract_version'
                      AND constraint_type = 'CHECK'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260721.2' AND success = 1
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name = 'ai_release_coordination_lock'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM ai_release_coordination_lock
                    WHERE lockName = 'global'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260721.3' AND success = 1
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name = 'generation_terminal_effect_replay_audit'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(DISTINCT index_name)
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name = 'generation_terminal_effect_replay_audit'
                      AND index_name = 'idx_generation_terminal_effect_replay_task'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260813.1' AND success = 1
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'generation_task'
                      AND column_name = 'terminalEffectsCompletedMask'
                      AND is_nullable = 'NO'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260813.2' AND success = 1
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'generation_task'
                      AND column_name = 'routeDecisionVersion'
                      AND character_maximum_length = 64
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260828.3' AND success = 1
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'user_credit_transaction'
                      AND column_name = 'appId'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name = 'app_generation_control'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(DISTINCT index_name)
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name = 'user_credit_transaction'
                      AND index_name = 'idx_app_generation_budget'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '20260828.4' AND success = 1
                    """));
        }
    }

    private int scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required integration property is missing: " + name);
        }
        return value.trim();
    }

    private static String databaseUrl(String adminUrl, String database) {
        int queryIndex = adminUrl.indexOf('?');
        String base = queryIndex < 0 ? adminUrl : adminUrl.substring(0, queryIndex);
        String query = queryIndex < 0 ? "" : adminUrl.substring(queryIndex);
        if (!base.endsWith("/")) {
            base += "/";
        }
        return base + database + query;
    }
}
