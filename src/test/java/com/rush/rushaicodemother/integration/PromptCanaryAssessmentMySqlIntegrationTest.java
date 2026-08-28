package com.rush.rushaicodemother.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties;
import com.rush.rushaicodemother.infrastructure.persistence.prompt.MyBatisPromptCanaryAssessmentStore;
import com.rush.rushaicodemother.infrastructure.persistence.prompt.MyBatisPromptCanaryObservationRepository;
import com.rush.rushaicodemother.mapper.PromptCanaryAssessmentMapper;
import com.rush.rushaicodemother.mapper.PromptCanaryObservationMapper;
import com.rush.rushaicodemother.orchestration.learning.GenerationStrategyPromotionGate;
import com.rush.rushaicodemother.service.prompt.canary.PromptCanaryAssessment;
import com.rush.rushaicodemother.service.prompt.canary.PromptCanaryAssessmentService;
import com.rush.rushaicodemother.service.prompt.canary.PromptCanaryDecision;
import com.rush.rushaicodemother.service.prompt.canary.PromptCanaryEvaluationRequest;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class PromptCanaryAssessmentMySqlIntegrationTest {

    private static final String DATABASE = "ai_mother_prompt_canary_assessment_it";
    private static final String ADMIN_URL = requiredProperty("integration.mysql.admin-url");
    private static final String USERNAME = requiredProperty("integration.mysql.username");
    private static final String PASSWORD = requiredProperty("integration.mysql.password");
    private static final String JDBC_URL = databaseUrl(ADMIN_URL, DATABASE);
    private static final String PROMPT_KEY = "codegen-vue-project";
    private static final String STABLE_HASH = "a".repeat(64);
    private static final String CANARY_HASH = "b".repeat(64);
    private static final String BUNDLE_ID = "c".repeat(64);

    private static PooledDataSource dataSource;
    private static PromptCanaryAssessmentService service;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        recreateDatabase();
        Flyway.configure()
                .dataSource(JDBC_URL, USERNAME, PASSWORD)
                .locations("filesystem:sql/migrations")
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load()
                .migrate();

        dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", JDBC_URL, USERNAME, PASSWORD);
        Environment environment = new Environment(
                "prompt-canary-assessment-it",
                new SpringManagedTransactionFactory(),
                dataSource
        );
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(PromptCanaryObservationMapper.class);
        configuration.addMapper(PromptCanaryAssessmentMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(sessionFactory);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        GenerationBenchmarkReleaseProperties properties = new GenerationBenchmarkReleaseProperties();
        properties.setMinimumTaskCount(5);
        service = new PromptCanaryAssessmentService(
                new MyBatisPromptCanaryObservationRepository(
                        sessionTemplate.getMapper(PromptCanaryObservationMapper.class)),
                new MyBatisPromptCanaryAssessmentStore(
                        sessionTemplate.getMapper(PromptCanaryAssessmentMapper.class), objectMapper),
                new GenerationStrategyPromotionGate(properties),
                objectMapper
        );
        seedProductionFacts();
    }

    @AfterAll
    static void closePoolAndDropDatabase() throws Exception {
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
        }
    }

    @Test
    void actualPromptSelectionsMustDriveComparableAssessmentAndImmutableEvidence()
            throws Exception {
        PromptCanaryAssessment assessment = service.assessAndPersist(
                new PromptCanaryEvaluationRequest(
                        PROMPT_KEY, 7L, 9L, BUNDLE_ID,
                        "v1", STABLE_HASH, "v2", CANARY_HASH,
                        Instant.parse("2026-08-28T01:00:00Z"),
                        Instant.parse("2026-08-28T04:00:00Z")
                ));

        assertEquals(PromptCanaryDecision.ROLLBACK_REQUIRED, assessment.decision());
        assertEquals(5, assessment.stableTaskCount());
        assertEquals(5, assessment.canaryTaskCount());
        assertEquals(1, assessment.ambiguousTaskCount());
        assertEquals(0, assessment.invalidAttributionTaskCount());
        assertTrue(assessment.violations().contains("success_rate_regressed"));
        assertTrue(assessment.violations().contains("delivered_p95_regressed"));

        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT decision, stableTaskCount, canaryTaskCount, ambiguousTaskCount,
                            invalidAttributionTaskCount, evidenceHash, evidenceJson
                     FROM ai_prompt_canary_assessment
                     WHERE assessmentId = ?
                     """)) {
            statement.setString(1, assessment.assessmentId());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("ROLLBACK_REQUIRED", result.getString("decision"));
                assertEquals(5, result.getLong("stableTaskCount"));
                assertEquals(5, result.getLong("canaryTaskCount"));
                assertEquals(1, result.getLong("ambiguousTaskCount"));
                assertEquals(0, result.getLong("invalidAttributionTaskCount"));
                assertEquals(64, result.getString("evidenceHash").length());
                assertTrue(result.getString("evidenceJson").contains(BUNDLE_ID));
            }
        }
    }

    private static void seedProductionFacts() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO `user` (id, userAccount, userPassword, userName, creditBalance)
                        VALUES (7, 'prompt-canary-it', 'not-a-real-password',
                                'Prompt Canary IT', 1000)
                        """);
                statement.executeUpdate("""
                        INSERT INTO tenant
                            (id, tenantKey, tenantType, displayName, ownerUserId, status, isDelete)
                        VALUES (3, 'organization:prompt-canary-it', 'organization',
                                'Prompt Canary IT', 7, 'active', 0)
                        """);
                statement.executeUpdate("""
                        INSERT INTO app (id, appName, userId, tenantId, isDelete)
                        VALUES (11, 'prompt-canary-it-app', 7, 3, 0)
                        """);
            }
            for (int index = 1; index <= 5; index++) {
                insertTaskFacts(connection, "stable-" + index, "stable", true, 1_000, 0, 5, 100, 1);
            }
            for (int index = 1; index <= 5; index++) {
                boolean success = index <= 3;
                insertTaskFacts(connection, "canary-" + index, "canary", success,
                        2_000, success ? 0 : 2, success ? 5 : 2, 200, 2);
            }
            insertTask(connection, "ambiguous-1", true, 1_500, 0, 1);
            insertFeedback(connection, "ambiguous-1", 5);
            insertCallAndSelection(connection, "ambiguous-1", "stable", "v1", STABLE_HASH, 100);
            insertCallAndSelection(connection, "ambiguous-1", "canary", "v2", CANARY_HASH, 100);
            connection.commit();
        }
    }

    private static void insertTaskFacts(Connection connection,
                                        String taskId,
                                        String channel,
                                        boolean success,
                                        long durationMs,
                                        int repairRounds,
                                        int rating,
                                        int totalTokens,
                                        int creditCost) throws Exception {
        insertTask(connection, taskId, success, durationMs, repairRounds, creditCost);
        insertFeedback(connection, taskId, rating);
        insertCallAndSelection(
                connection,
                taskId,
                channel,
                channel.equals("stable") ? "v1" : "v2",
                channel.equals("stable") ? STABLE_HASH : CANARY_HASH,
                totalTokens
        );
    }

    private static void insertTask(Connection connection,
                                   String taskId,
                                   boolean success,
                                   long durationMs,
                                   int repairRounds,
                                   int creditCost) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO generation_task
                    (taskId, appId, userId, tenantId, route, status, stage,
                     requiresBuildValidation, firstBuildPassed, repairRounds,
                     firstPreviewMillis, submittedAt, startTime, endTime, durationMs,
                     creditCost, creditCharged, isDelete)
                VALUES (?, 11, 7, 3, 'agent_edit', ?, ?, 1, ?, ?, 500,
                        '2026-08-28 10:00:00', '2026-08-28 10:00:00',
                        '2026-08-28 10:30:00', ?, ?, 1, 0)
                """)) {
            statement.setString(1, taskId);
            statement.setString(2, success ? "success" : "failed");
            statement.setString(3, success ? "completed" : "failed");
            statement.setInt(4, success ? 1 : 0);
            statement.setInt(5, repairRounds);
            statement.setLong(6, durationMs);
            statement.setInt(7, creditCost);
            statement.executeUpdate();
        }
    }

    private static void insertFeedback(Connection connection, String taskId, int rating)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO generation_feedback
                    (taskId, appId, userId, rating, outcome, comment, isDelete)
                VALUES (?, 11, 7, ?, 'observed', '', 0)
                """)) {
            statement.setString(1, taskId);
            statement.setInt(2, rating);
            statement.executeUpdate();
        }
    }

    private static void insertCallAndSelection(Connection connection,
                                               String taskId,
                                               String channel,
                                               String version,
                                               String contentHash,
                                               int totalTokens) throws Exception {
        String callId = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO generation_model_call
                    (callId, taskId, appId, userId, invocationPurpose, billingMode,
                     provider, model, callStatus, totalTokens, usageSource, createTime, isDelete)
                VALUES (?, ?, 11, 7, 'GENERATION', 'BILLABLE', 'integration',
                        'integration-model', 'SUCCESS', ?, 'OFFICIAL',
                        '2026-08-28 10:10:00', 0)
                """)) {
            statement.setString(1, callId);
            statement.setString(2, taskId);
            statement.setInt(3, totalTokens);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO generation_model_prompt_selection
                    (callId, taskId, promptKey, promptVersion, channel,
                     contentHash, bundleId, createTime, isDelete)
                VALUES (?, ?, ?, ?, ?, ?, ?, '2026-08-28 10:10:00', 0)
                """)) {
            statement.setString(1, callId);
            statement.setString(2, taskId);
            statement.setString(3, PROMPT_KEY);
            statement.setString(4, version);
            statement.setString(5, channel);
            statement.setString(6, contentHash);
            statement.setString(7, BUNDLE_ID);
            statement.executeUpdate();
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
