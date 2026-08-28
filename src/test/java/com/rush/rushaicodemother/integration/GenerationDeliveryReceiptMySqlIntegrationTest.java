package com.rush.rushaicodemother.integration;

import com.rush.rushaicodemother.infrastructure.persistence.task.MyBatisDurableGenerationTaskRepository;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceipt;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceiptFactory;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommand;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommandCodec;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class GenerationDeliveryReceiptMySqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-28T04:00:00Z");
    private static final String TASK_ID = "task-delivery-receipt-it";
    private static final String DATABASE = "ai_mother_delivery_receipt_it";
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
                    VALUES (7, 'delivery-receipt-it', 'not-a-real-password', 'Delivery Receipt IT', 1000)
                    """);
            statement.executeUpdate("""
                    INSERT INTO tenant
                        (id, tenantKey, tenantType, displayName, ownerUserId, status, isDelete)
                    VALUES
                        (3, 'organization:delivery-receipt-it', 'organization',
                         'Delivery Receipt IT', 7, 'active', 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO tenant_membership
                        (tenantId, userId, role, status, joinedAt, isDelete)
                    VALUES
                        (3, 7, 'owner', 'active', '2026-08-28 12:00:00.000000', 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO app (id, appName, userId, tenantId, isDelete)
                    VALUES (11, 'delivery-receipt-it-app', 7, 3, 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO generation_task
                        (taskId, appId, userId, tenantId, route, status, stage,
                         submittedAt, leaseOwner, leaseUntil, heartbeatAt,
                         executionEpoch, attempt, version, startTime,
                         totalTokens, creditCost, creditCharged, isDelete)
                    VALUES
                        ('task-delivery-receipt-it', 11, 7, 3, 'heavy_generation',
                         'running', 'generating', '2026-08-28 12:00:00.000000',
                         'delivery-receipt-worker', '2099-01-01 00:00:00.000000',
                         '2026-08-28 12:00:00.000000', 3, 1, 0,
                         '2026-08-28 12:00:00.000000', 0, 0, 0, 0)
                    """);
        }
    }

    @AfterAll
    static void cleanDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
        }
    }

    @Test
    void versionTwoReceiptMustPersistAndRestoreThroughRealMapper() throws Exception {
        GenerationOutcomeQuality quality = GenerationOutcomeQuality.ofFailure(
                "model_timeout", 2, 1, 1_500L);
        GenerationDeliveryReceipt frozenReceipt = GenerationDeliveryReceiptFactory.fromTerminal(
                "heavy_generation", GenerationTaskStatus.FAILED,
                GenerationCompletionEvidenceSet.empty(), quality);
        GenerationFinalizationCommand command = GenerationFinalizationCommand.of(
                TASK_ID,
                11L,
                new GenerationExecutionFence(
                        TASK_ID, "delivery-receipt-worker", 3L),
                GenerationTaskStatus.FAILED,
                "模型调用超时",
                "生成失败，允许安全重试",
                quality,
                frozenReceipt);

        SqlSessionFactory factory = sqlSessionFactory();
        try (var session = factory.openSession(false)) {
            MyBatisDurableGenerationTaskRepository repository =
                    new MyBatisDurableGenerationTaskRepository(
                            session.getMapper(GenerationTaskRuntimeMapper.class));
            repository.prepareFinalizationIntent(command, NOW);
            session.commit();
        }

        markTerminalAndSettled();

        try (var session = factory.openSession(true)) {
            MyBatisDurableGenerationTaskRepository repository =
                    new MyBatisDurableGenerationTaskRepository(
                            session.getMapper(GenerationTaskRuntimeMapper.class));
            DurableGenerationTaskRecord restored = repository.findByTaskId(TASK_ID).orElseThrow();

            assertEquals(GenerationTaskStatus.FAILED, restored.status());
            assertEquals("heavy_generation", restored.deliveryReceipt().actualRoute());
            assertEquals("model_timeout", restored.deliveryReceipt().failureCategory());
            assertTrue(restored.deliveryReceipt().retryable());
            assertEquals("retry", restored.deliveryReceipt().recoveryAction());
            assertEquals(
                    frozenReceipt.validationSummary(),
                    restored.deliveryReceipt().validationSummary());
            assertEquals("settled", restored.deliveryReceipt().costSummary().settlementStatus());
            assertEquals(12_345L, restored.deliveryReceipt().costSummary().totalTokens());
            assertEquals(13L, restored.deliveryReceipt().costSummary().creditCost());
        }

        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT terminalIntentSchemaVersion
                     FROM generation_task
                     WHERE taskId = 'task-delivery-receipt-it'
                     """)) {
            assertTrue(result.next());
            assertEquals(GenerationFinalizationCommandCodec.CURRENT_SCHEMA_VERSION, result.getInt(1));
        }
    }

    private void markTerminalAndSettled() throws Exception {
        LocalDateTime finalizedAt = NOW.plusSeconds(10)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE generation_task
                     SET status = 'failed',
                         stage = 'failed',
                         endTime = ?,
                         changedFileCount = 2,
                         firstBuildPassed = 0,
                         repairRounds = 1,
                         firstPreviewMillis = 1500,
                         failureCategory = 'model_timeout',
                         totalTokens = 12345,
                         creditCost = 13,
                         creditCharged = 1,
                         terminalIntentFinalizedAt = ?
                     WHERE taskId = ?
                     """)) {
            statement.setObject(1, finalizedAt);
            statement.setObject(2, finalizedAt);
            statement.setString(3, TASK_ID);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private SqlSessionFactory sqlSessionFactory() {
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", JDBC_URL, USERNAME, PASSWORD);
        Environment environment = new Environment(
                "test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(GenerationTaskRuntimeMapper.class);
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
