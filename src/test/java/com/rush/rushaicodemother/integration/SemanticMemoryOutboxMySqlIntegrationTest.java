package com.rush.rushaicodemother.integration;

import com.rush.rushaicodemother.infrastructure.persistence.memory.MyBatisGenerationMemoryOutboxRepository;
import com.rush.rushaicodemother.infrastructure.persistence.memory.MyBatisSemanticMemoryDeletionOutboxRepository;
import com.rush.rushaicodemother.mapper.GenerationMemoryOutboxMapper;
import com.rush.rushaicodemother.mapper.SemanticMemoryDeletionOutboxMapper;
import com.rush.rushaicodemother.memory.GenerationMemoryOutboxItem;
import com.rush.rushaicodemother.memory.SemanticMemoryContract;
import com.rush.rushaicodemother.memory.SemanticMemoryDeletionOutboxItem;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class SemanticMemoryOutboxMySqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-21T07:00:00Z");
    private static final String DATABASE = "ai_mother_memory_it";
    private static final String ADMIN_URL = requiredProperty("integration.mysql.admin-url");
    private static final String USERNAME = requiredProperty("integration.mysql.username");
    private static final String PASSWORD = requiredProperty("integration.mysql.password");
    private static final String JDBC_URL = databaseUrl(ADMIN_URL, DATABASE);

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
            statement.execute("CREATE DATABASE " + DATABASE
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        Flyway.configure()
                .dataSource(JDBC_URL, USERNAME, PASSWORD)
                .locations("filesystem:sql/migrations")
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO `user` (id, userAccount, userPassword, userName, creditBalance)
                    VALUES (7, 'memory-it', 'not-a-real-password', 'Memory IT', 1000)
                    """);
            statement.executeUpdate("""
                    INSERT INTO tenant
                        (id, tenantKey, tenantType, displayName, ownerUserId, status, isDelete)
                    VALUES
                        (3, 'organization:memory-it', 'organization', 'Memory IT', 7, 'active', 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO tenant_membership
                        (tenantId, userId, role, status, joinedAt, isDelete)
                    VALUES
                        (3, 7, 'owner', 'active', '2026-07-21 07:00:00.000000', 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO app (id, appName, userId, tenantId, isDelete)
                    VALUES (11, 'memory-it-app', 7, 3, 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO generation_task
                        (taskId, appId, userId, tenantId, status, submittedAt,
                         startTime, endTime, memorySummary, memoryIndexedAt,
                         memoryIndexContractVersion, memoryIndexAttempts, isDelete)
                    VALUES
                        ('task-v1', 11, 7, 3, 'success', '2026-07-21 06:00:00.000000',
                         '2026-07-21 06:00:00.000000', '2026-07-21 06:10:00.000000',
                         'legacy build passed', '2026-07-21 06:11:00.000000', 1, 10, 0)
                    """);
        }
    }

    @Test
    void generationOutboxMustReplayLegacyContractWithFreshAttemptsAndOwnerFencing()
            throws Exception {
        SqlSessionFactory factory = sqlSessionFactory();
        try (var workerASession = factory.openSession(false);
             var workerBSession = factory.openSession(false)) {
            MyBatisGenerationMemoryOutboxRepository workerA =
                    new MyBatisGenerationMemoryOutboxRepository(
                            workerASession.getMapper(GenerationMemoryOutboxMapper.class));
            MyBatisGenerationMemoryOutboxRepository workerB =
                    new MyBatisGenerationMemoryOutboxRepository(
                            workerBSession.getMapper(GenerationMemoryOutboxMapper.class));

            List<GenerationMemoryOutboxItem> firstClaim = workerA.claimBatch(
                    NOW, NOW.plusSeconds(120), "worker-a", 10, 10);
            assertEquals(1, firstClaim.size());
            assertEquals(1, firstClaim.getFirst().attempts());
            workerASession.commit();

            assertTrue(workerB.claimBatch(
                    NOW.plusSeconds(1), NOW.plusSeconds(121), "worker-b", 10, 10).isEmpty());
            assertFalse(workerB.markIndexed("task-v1", "worker-b", NOW.plusSeconds(2)));
            workerBSession.commit();

            assertTrue(workerA.markFailed(
                    "task-v1", "worker-a", "temporary", NOW.plusSeconds(3), NOW.plusSeconds(33)));
            workerASession.commit();

            assertTrue(workerB.claimBatch(
                    NOW.plusSeconds(32), NOW.plusSeconds(152), "worker-b", 10, 10).isEmpty());
            workerBSession.commit();
            List<GenerationMemoryOutboxItem> retryClaim = workerB.claimBatch(
                    NOW.plusSeconds(34), NOW.plusSeconds(154), "worker-b", 10, 10);
            assertEquals(1, retryClaim.size());
            assertEquals(2, retryClaim.getFirst().attempts());
            workerBSession.commit();

            assertFalse(workerA.markFailed(
                    "task-v1", "worker-a", "late", NOW.plusSeconds(35), NOW.plusSeconds(65)));
            workerASession.commit();
            assertTrue(workerB.markIndexed("task-v1", "worker-b", NOW.plusSeconds(36)));
            workerBSession.commit();

            assertEquals(0, workerB.inspectBacklog(NOW.plusSeconds(37), 10).pending());
        }

        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT memoryIndexContractVersion, memoryIndexAttempts, memoryIndexedAt
                     FROM generation_task WHERE taskId = 'task-v1'
                     """)) {
            assertTrue(result.next());
            assertEquals(SemanticMemoryContract.INDEX_VERSION, result.getInt(1));
            assertEquals(2, result.getInt(2));
            assertNotNull(result.getTimestamp(3));
        }
    }

    @Test
    void deletionOutboxMustBeIdempotentRetryableAndLeaseFenced() {
        SqlSessionFactory factory = sqlSessionFactory();
        try (var workerASession = factory.openSession(false);
             var workerBSession = factory.openSession(false)) {
            MyBatisSemanticMemoryDeletionOutboxRepository workerA =
                    new MyBatisSemanticMemoryDeletionOutboxRepository(
                            workerASession.getMapper(SemanticMemoryDeletionOutboxMapper.class));
            MyBatisSemanticMemoryDeletionOutboxRepository workerB =
                    new MyBatisSemanticMemoryDeletionOutboxRepository(
                            workerBSession.getMapper(SemanticMemoryDeletionOutboxMapper.class));

            workerA.enqueueApplicationDeletion(3L, 11L, 7L, NOW);
            workerASession.commit();
            List<SemanticMemoryDeletionOutboxItem> firstClaim = workerA.claimBatch(
                    NOW, NOW.plusSeconds(120), "delete-a", 10);
            assertEquals(1, firstClaim.size());
            assertEquals(1, firstClaim.getFirst().attempts());
            String operationId = firstClaim.getFirst().operationId();
            workerASession.commit();

            assertTrue(workerB.claimBatch(
                    NOW.plusSeconds(1), NOW.plusSeconds(121), "delete-b", 10).isEmpty());
            assertFalse(workerB.markCompleted(operationId, "delete-b", NOW.plusSeconds(2)));
            workerBSession.commit();

            assertTrue(workerA.markFailed(
                    operationId, "delete-a", "temporary", NOW.plusSeconds(3), NOW.plusSeconds(33)));
            workerASession.commit();
            assertTrue(workerB.claimBatch(
                    NOW.plusSeconds(32), NOW.plusSeconds(152), "delete-b", 10).isEmpty());
            workerBSession.commit();

            List<SemanticMemoryDeletionOutboxItem> retryClaim = workerB.claimBatch(
                    NOW.plusSeconds(34), NOW.plusSeconds(154), "delete-b", 10);
            assertEquals(1, retryClaim.size());
            assertEquals(2, retryClaim.getFirst().attempts());
            workerBSession.commit();
            assertTrue(workerB.markCompleted(operationId, "delete-b", NOW.plusSeconds(35)));
            workerBSession.commit();

            workerA.enqueueApplicationDeletion(3L, 11L, 7L, NOW.plusSeconds(36));
            workerASession.commit();
            assertEquals(0, workerA.inspectBacklog(NOW.plusSeconds(37)).pending());
        }
    }

    private SqlSessionFactory sqlSessionFactory() {
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", JDBC_URL, USERNAME, PASSWORD);
        Environment environment = new Environment(
                "test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(GenerationMemoryOutboxMapper.class);
        configuration.addMapper(SemanticMemoryDeletionOutboxMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
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
