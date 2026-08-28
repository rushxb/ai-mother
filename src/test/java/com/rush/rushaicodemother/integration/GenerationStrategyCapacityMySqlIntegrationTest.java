package com.rush.rushaicodemother.integration;

import com.rush.rushaicodemother.infrastructure.persistence.task.MyBatisGenerationScenarioAttributionRepository;
import com.rush.rushaicodemother.mapper.GenerationScenarioAttributionMapper;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioBucketSummary;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
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
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 真实 MySQL 下策略容量、成本与质量同桶聚合的验收。 */
@Tag("integration")
class GenerationStrategyCapacityMySqlIntegrationTest {

    private static final String DATABASE = "ai_mother_strategy_capacity_it";
    private static final String ADMIN_URL = requiredProperty("integration.mysql.admin-url");
    private static final String USERNAME = requiredProperty("integration.mysql.username");
    private static final String PASSWORD = requiredProperty("integration.mysql.password");
    private static final String JDBC_URL = databaseUrl(ADMIN_URL, DATABASE);
    private static final String INTENT_SIGNATURE = "a".repeat(64);
    private static final String RELEASE_IDENTITY = "b".repeat(64);

    private static PooledDataSource dataSource;
    private static SqlSession sqlSession;
    private static MyBatisGenerationScenarioAttributionRepository repository;

    @BeforeAll
    static void migrateSeedAndConfigureRepository() throws Exception {
        recreateDatabase();
        Flyway.configure()
                .dataSource(JDBC_URL, USERNAME, PASSWORD)
                .locations("filesystem:sql/migrations")
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load()
                .migrate();
        seedScenario();

        dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", JDBC_URL, USERNAME, PASSWORD);
        Environment environment = new Environment(
                "generation-strategy-capacity-it", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(GenerationScenarioAttributionMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        sqlSession = sessionFactory.openSession(true);
        repository = new MyBatisGenerationScenarioAttributionRepository(
                sqlSession.getMapper(GenerationScenarioAttributionMapper.class));
    }

    @AfterAll
    static void closeAndCleanDatabase() throws Exception {
        if (sqlSession != null) {
            sqlSession.close();
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
    void physicalCallsMustAggregateByGenerationPurposeAndExposeIncompleteLedger() {
        List<GenerationScenarioBucketSummary> summaries = repository.summarize(
                INTENT_SIGNATURE,
                Instant.parse("2026-08-27T16:00:00Z"),
                Instant.parse("2026-08-29T16:00:00Z"),
                10);

        assertEquals(1, summaries.size());
        GenerationScenarioBucketSummary summary = summaries.getFirst();
        assertEquals(3, summary.quality().taskCount());
        assertEquals(2, summary.quality().successCount());

        // STARTED 账本尚未收敛，因此该任务不能计入容量完整观测。
        assertEquals(2, summary.capacity().observedTaskCount());
        assertEquals(4, summary.capacity().totalPhysicalModelCalls());
        assertEquals(2, summary.capacity().maximumPhysicalModelCallsPerTask());
        assertEquals(1, summary.capacity().capacityFailureCount());

        // 非 GENERATION 调用不进入 Provider 成本，未收敛调用也不能伪装成已观测成本。
        assertEquals(2, summary.cost().providerCostObservedCount());
        assertEquals(600, summary.cost().totalProviderTokens());
        assertEquals(3, summary.cost().creditCostObservedCount());
        assertEquals(5, summary.cost().totalCreditCost());
    }

    private static void seedScenario() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO `user` (id, userAccount, userPassword, userName, creditBalance)
                    VALUES (7, 'strategy-capacity-it', 'not-a-real-password',
                            'Strategy Capacity IT', 1000)
                    """);
            statement.executeUpdate("""
                    INSERT INTO tenant
                        (id, tenantKey, tenantType, displayName, ownerUserId, status, isDelete)
                    VALUES
                        (3, 'organization:strategy-capacity-it', 'organization',
                         'Strategy Capacity IT', 7, 'active', 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO app (id, appName, userId, tenantId, isDelete)
                    VALUES (11, 'strategy-capacity-it-app', 7, 3, 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO generation_task
                        (taskId, appId, userId, tenantId, route, status, stage,
                         intentSignature, intentProfileVersion, routeDecisionVersion,
                         routeReleaseIdentity, requiresBuildValidation, firstBuildPassed,
                         repairRounds, firstPreviewMillis, failureCategory,
                         submittedAt, startTime, endTime, durationMs,
                         creditCost, creditCharged, isDelete)
                    VALUES
                        ('capacity-success-retry', 11, 7, 3, 'agent_edit',
                         'success', 'completed', REPEAT('a', 64), 'intent-profile-v1',
                         'routing-policy-v1', REPEAT('b', 64), 1, 1, 0, 800, NULL,
                         '2026-08-28 10:00:00', '2026-08-28 10:00:00',
                         '2026-08-28 10:00:02', 2000, 2, 1, 0),
                        ('capacity-success-started', 11, 7, 3, 'agent_edit',
                         'success', 'completed', REPEAT('a', 64), 'intent-profile-v1',
                         'routing-policy-v1', REPEAT('b', 64), 1, 1, 0, 900, NULL,
                         '2026-08-28 11:00:00', '2026-08-28 11:00:00',
                         '2026-08-28 11:00:03', 3000, 3, 1, 0),
                        ('capacity-rate-limited', 11, 7, 3, 'agent_edit',
                         'failed', 'failed', REPEAT('a', 64), 'intent-profile-v1',
                         'routing-policy-v1', REPEAT('b', 64), 0, NULL, NULL, NULL,
                         'model_rate_limit', '2026-08-28 12:00:00',
                         '2026-08-28 12:00:00', '2026-08-28 12:00:01',
                         1000, 0, 0, 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO generation_model_call
                        (callId, taskId, appId, userId, invocationPurpose, billingMode,
                         billingExemptionReason, provider, model, callStatus,
                         totalTokens, usageSource, errorCategory, createTime)
                    VALUES
                        ('20000000-0000-0000-0000-000000000001',
                         'capacity-success-retry', 11, 7, 'GENERATION', 'BILLABLE', NULL,
                         'integration-a', 'integration-model', 'ERROR', 100,
                         'OFFICIAL', 'model_timeout', '2026-08-28 10:00:01'),
                        ('20000000-0000-0000-0000-000000000002',
                         'capacity-success-retry', 11, 7, 'GENERATION', 'BILLABLE', NULL,
                         'integration-b', 'integration-model', 'SUCCESS', 200,
                         'OFFICIAL', NULL, '2026-08-28 10:00:02'),
                        ('20000000-0000-0000-0000-000000000003',
                         'capacity-success-started', 11, 7, 'GENERATION', 'BILLABLE', NULL,
                         'integration-a', 'integration-model', 'SUCCESS', 300,
                         'OFFICIAL', NULL, '2026-08-28 11:00:02'),
                        ('20000000-0000-0000-0000-000000000004',
                         'capacity-success-started', 11, 7, 'GENERATION', 'BILLABLE', NULL,
                         'integration-b', 'integration-model', 'STARTED', NULL,
                         'OFFICIAL', NULL, '2026-08-28 11:00:03'),
                        ('20000000-0000-0000-0000-000000000005',
                         'capacity-success-retry', 11, 7, 'PROMPT_OPTIMIZATION', 'EXEMPT',
                         'interactive_free_tier', 'integration-a', 'integration-model',
                         'SUCCESS', 999, 'OFFICIAL', NULL, '2026-08-28 10:00:03')
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
