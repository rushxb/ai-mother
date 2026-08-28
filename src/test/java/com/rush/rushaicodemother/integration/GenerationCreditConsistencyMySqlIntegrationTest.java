package com.rush.rushaicodemother.integration;

import com.rush.rushaicodemother.config.UserCreditProperties;
import com.rush.rushaicodemother.mapper.UserCreditMapper;
import com.rush.rushaicodemother.monitor.GenerationCreditMetricsCollector;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.credit.DefaultUserCreditPersistenceService;
import com.rush.rushaicodemother.service.credit.ProviderCostGenerationUserBillingPolicy;
import com.rush.rushaicodemother.service.credit.UserCreditCostCalculator;
import com.rush.rushaicodemother.service.impl.UserCreditServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 MySQL 下生成任务恢复与重试的账实一致验收。 */
@Tag("integration")
class GenerationCreditConsistencyMySqlIntegrationTest {

    private static final String DATABASE = "ai_mother_credit_consistency_it";
    private static final String ADMIN_URL = requiredProperty("integration.mysql.admin-url");
    private static final String USERNAME = requiredProperty("integration.mysql.username");
    private static final String PASSWORD = requiredProperty("integration.mysql.password");
    private static final String JDBC_URL = databaseUrl(ADMIN_URL, DATABASE);
    private static final List<String> TASK_IDS = List.of(
            "credit-task-recovery",
            "credit-provider-retry",
            "credit-cancelled",
            "credit-deadline",
            "credit-post-publication"
    );

    private static PooledDataSource dataSource;
    private static TransactionTemplate transactions;
    private static UserCreditService creditService;
    private static SimpleMeterRegistry meterRegistry;

    @BeforeAll
    static void migrateSeedAndConfigureService() throws Exception {
        recreateDatabase();
        Flyway.configure()
                .dataSource(JDBC_URL, USERNAME, PASSWORD)
                .locations("filesystem:sql/migrations")
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load()
                .migrate();
        seedBillingScenarios();

        dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", JDBC_URL, USERNAME, PASSWORD);
        TransactionFactory transactionFactory = new SpringManagedTransactionFactory();
        Environment environment = new Environment(
                "generation-credit-consistency-it", transactionFactory, dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(UserCreditMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(sessionFactory);
        DefaultUserCreditPersistenceService persistenceService =
                new DefaultUserCreditPersistenceService(
                        sessionTemplate.getMapper(UserCreditMapper.class));
        UserCreditProperties properties = new UserCreditProperties();
        properties.setTokensPerCredit(100_000L);
        meterRegistry = new SimpleMeterRegistry();
        creditService = new UserCreditServiceImpl(
                persistenceService,
                new UserCreditCostCalculator(properties),
                new GenerationCreditMetricsCollector(meterRegistry),
                new ProviderCostGenerationUserBillingPolicy()
        );
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void closeAndCleanDatabase() throws Exception {
        if (meterRegistry != null) {
            meterRegistry.close();
        }
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
        }
    }

    @Test
    void terminalRecoveryProviderRetryCancelDeadlineAndPostPublicationMustSettleExactlyOnce()
            throws Exception {
        // 已有结算流水但任务标记缺失时，只恢复任务字段，不再次修改余额。
        settle("credit-task-recovery");
        settle("credit-task-recovery");

        // 两个 Worker 同时重试同一终态，任务行锁必须把实际结算串行化。
        List<Boolean> concurrentOutcomes = raceSettlement("credit-provider-retry");
        assertEquals(List.of(true, true), concurrentOutcomes);
        settle("credit-provider-retry");

        // 无模型消耗取消、Deadline 和已发布后终态恢复均重复调用一次。
        settleTwice("credit-cancelled");
        settleTwice("credit-deadline");
        settleTwice("credit-post-publication");

        assertTaskSettlement("credit-task-recovery", 2L, 200_000L);
        assertTaskSettlement("credit-provider-retry", 2L, 200_000L);
        assertTaskSettlement("credit-cancelled", 0L, 0L);
        assertTaskSettlement("credit-deadline", 2L, 100_001L);
        assertTaskSettlement("credit-post-publication", 3L, 250_000L);

        assertAccountBalance(7L, 98L);
        assertAccountBalance(8L, 98L);
        assertAccountBalance(9L, 100L);
        assertAccountBalance(10L, 98L);
        assertAccountBalance(11L, 97L);

        for (String taskId : TASK_IDS) {
            assertEquals(1L, transactionCount("GENERATION_RESERVATION", taskId));
            assertEquals(1L, transactionCount("GENERATION_SETTLEMENT", taskId));
        }
        assertEquals(0L, transactionCountByType("GENERATION_CHARGE"));
        assertEquals(10L, transactionCountForTasks());
    }

    private static void settleTwice(String taskId) {
        settle(taskId);
        settle(taskId);
    }

    private static void settle(String taskId) {
        transactions.executeWithoutResult(status -> creditService.chargeGenerationTask(taskId));
    }

    private List<Boolean> raceSettlement(String taskId) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(settlementAttempt(taskId, ready, start));
            Future<Boolean> second = executor.submit(settlementAttempt(taskId, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Callable<Boolean> settlementAttempt(String taskId,
                                                CountDownLatch ready,
                                                CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发结算开始信号超时");
            }
            settle(taskId);
            return true;
        };
    }

    private void assertTaskSettlement(String taskId, long creditCost, long totalTokens)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT creditCharged, creditCost, totalTokens
                     FROM generation_task
                     WHERE taskId = ?
                     """)) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt("creditCharged"));
                assertEquals(creditCost, result.getLong("creditCost"));
                assertEquals(totalTokens, result.getLong("totalTokens"));
            }
        }
    }

    private void assertAccountBalance(long userId, long expectedBalance) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT creditBalance
                     FROM `user`
                     WHERE id = ?
                     """)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(expectedBalance, result.getLong("creditBalance"));
            }
        }
    }

    private long transactionCount(String type, String taskId) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM user_credit_transaction
                     WHERE type = ? AND bizId = ? AND isDelete = 0
                     """)) {
            statement.setString(1, type);
            statement.setString(2, taskId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private long transactionCountByType(String type) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM user_credit_transaction
                     WHERE type = ? AND isDelete = 0
                     """)) {
            statement.setString(1, type);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private long transactionCountForTasks() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM user_credit_transaction
                     WHERE bizId LIKE 'credit-%' AND isDelete = 0
                     """)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static void seedBillingScenarios() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO `user`
                        (id, userAccount, userPassword, userName, creditBalance)
                    VALUES
                        (7, 'credit-recovery', 'not-a-real-password', 'Recovery', 98),
                        (8, 'credit-provider-retry', 'not-a-real-password', 'Provider Retry', 95),
                        (9, 'credit-cancelled', 'not-a-real-password', 'Cancelled', 95),
                        (10, 'credit-deadline', 'not-a-real-password', 'Deadline', 95),
                        (11, 'credit-post-publication', 'not-a-real-password', 'Published', 95)
                    """);
            statement.executeUpdate("""
                    INSERT INTO tenant
                        (id, tenantKey, tenantType, displayName, ownerUserId, status, isDelete)
                    VALUES
                        (3, 'organization:credit-consistency-it', 'organization',
                         'Credit Consistency IT', 7, 'active', 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO app (id, appName, userId, tenantId, isDelete)
                    VALUES (11, 'credit-consistency-it-app', 7, 3, 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO generation_task
                        (taskId, appId, userId, tenantId, route, status, stage,
                         submittedAt, deadlineAt, startTime, endTime,
                         totalTokens, creditCost, creditCharged,
                         publicationStatus, publicationCodeGenType,
                         publicationExecutionEpoch, publicationPublishedAt,
                         publicationCommittedAt, isDelete)
                    VALUES
                        ('credit-task-recovery', 11, 7, 3, 'heavy_generation',
                         'failed', 'failed', '2026-08-20 10:00:00',
                         '2026-08-20 10:20:00', '2026-08-20 10:00:00',
                         '2026-08-20 10:15:00', 0, 0, 0,
                         NULL, NULL, NULL, NULL, NULL, 0),
                        ('credit-provider-retry', 11, 8, 3, 'heavy_generation',
                         'success', 'completed', '2026-08-21 10:00:00',
                         '2026-08-21 10:20:00', '2026-08-21 10:00:00',
                         '2026-08-21 10:15:00', 0, 0, 0,
                         NULL, NULL, NULL, NULL, NULL, 0),
                        ('credit-cancelled', 11, 9, 3, 'lightweight_edit',
                         'cancelled', 'cancelled', '2026-08-22 10:00:00',
                         '2026-08-22 10:20:00', '2026-08-22 10:00:00',
                         '2026-08-22 10:01:00', 0, 0, 0,
                         NULL, NULL, NULL, NULL, NULL, 0),
                        ('credit-deadline', 11, 10, 3, 'heavy_generation',
                         'deadline_exceeded', 'deadline_exceeded', '2026-08-23 10:00:00',
                         '2026-08-23 10:20:00', '2026-08-23 10:00:00',
                         '2026-08-23 10:21:00', 0, 0, 0,
                         NULL, NULL, NULL, NULL, NULL, 0),
                        ('credit-post-publication', 11, 11, 3, 'heavy_generation',
                         'success', 'completed', '2026-08-24 10:00:00',
                         '2026-08-24 10:20:00', '2026-08-24 10:00:00',
                         '2026-08-24 10:18:00', 0, 0, 0,
                         'committed', 'multi_file', 2,
                         '2026-08-24 10:15:00', '2026-08-24 10:16:00', 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO user_credit_transaction
                        (userId, tenantId, changeAmount, balanceAfter, type,
                         bizId, remark, tokenCount, createTime)
                    VALUES
                        (7, 3, -5, 95, 'GENERATION_RESERVATION',
                         'credit-task-recovery', 'reservation:test', NULL,
                         '2026-08-20 10:00:00'),
                        (7, 3, 3, 98, 'GENERATION_SETTLEMENT',
                         'credit-task-recovery', 'settlement:test', 200000,
                         '2026-08-20 10:16:00'),
                        (8, 3, -5, 95, 'GENERATION_RESERVATION',
                         'credit-provider-retry', 'reservation:test', NULL,
                         '2026-08-21 10:00:00'),
                        (9, 3, -5, 95, 'GENERATION_RESERVATION',
                         'credit-cancelled', 'reservation:test', NULL,
                         '2026-08-22 10:00:00'),
                        (10, 3, -5, 95, 'GENERATION_RESERVATION',
                         'credit-deadline', 'reservation:test', NULL,
                         '2026-08-23 10:00:00'),
                        (11, 3, -5, 95, 'GENERATION_RESERVATION',
                         'credit-post-publication', 'reservation:test', NULL,
                         '2026-08-24 10:00:00')
                    """);
            statement.executeUpdate("""
                    INSERT INTO generation_model_call
                        (callId, taskId, appId, userId, invocationPurpose, billingMode,
                         provider, model, callStatus, totalTokens, usageSource,
                         errorCategory, createTime)
                    VALUES
                        ('10000000-0000-0000-0000-000000000001',
                         'credit-provider-retry', 11, 8, 'GENERATION', 'BILLABLE',
                         'integration', 'integration-model', 'ERROR', 100000,
                         'OFFICIAL', 'model_timeout', '2026-08-21 10:05:00'),
                        ('10000000-0000-0000-0000-000000000002',
                         'credit-provider-retry', 11, 8, 'GENERATION', 'BILLABLE',
                         'integration', 'integration-model', 'SUCCESS', 200000,
                         'OFFICIAL', NULL, '2026-08-21 10:10:00'),
                        ('10000000-0000-0000-0000-000000000003',
                         'credit-deadline', 11, 10, 'GENERATION', 'BILLABLE',
                         'integration', 'integration-model', 'SUCCESS', 100001,
                         'OFFICIAL', NULL, '2026-08-23 10:18:00'),
                        ('10000000-0000-0000-0000-000000000004',
                         'credit-post-publication', 11, 11, 'GENERATION', 'BILLABLE',
                         'integration', 'integration-model', 'SUCCESS', 250000,
                         'OFFICIAL', NULL, '2026-08-24 10:12:00')
                    """);
        }
    }

    private static void recreateDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
            statement.execute("CREATE DATABASE " + DATABASE
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
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
