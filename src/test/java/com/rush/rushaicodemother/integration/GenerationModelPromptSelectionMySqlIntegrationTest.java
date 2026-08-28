package com.rush.rushaicodemother.integration;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.mapper.GenerationTraceMapper;
import com.rush.rushaicodemother.model.enums.GenerationModelCallStatus;
import com.rush.rushaicodemother.model.enums.GenerationModelUsageSource;
import com.rush.rushaicodemother.service.trace.DefaultGenerationTracePersistenceService;
import com.rush.rushaicodemother.service.trace.DefaultGenerationTraceService;
import com.rush.rushaicodemother.service.trace.GenerationModelCallCommand;
import com.rush.rushaicodemother.service.trace.GenerationModelCallProvenance;
import com.rush.rushaicodemother.service.trace.GenerationPromptSelectionProvenance;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 真实 MySQL 下模型调用与 Prompt 版本事实的事务、幂等验收。 */
@Tag("integration")
class GenerationModelPromptSelectionMySqlIntegrationTest {

    private static final String DATABASE = "ai_mother_model_prompt_selection_it";
    private static final String ADMIN_URL = requiredProperty("integration.mysql.admin-url");
    private static final String USERNAME = requiredProperty("integration.mysql.username");
    private static final String PASSWORD = requiredProperty("integration.mysql.password");
    private static final String JDBC_URL = databaseUrl(ADMIN_URL, DATABASE);
    private static final String CALL_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String HASH = "a".repeat(64);

    private static PooledDataSource dataSource;
    private static DefaultGenerationTraceService traceService;
    private static TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrateAndConfigureService() throws Exception {
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
                "generation-model-prompt-selection-it",
                new SpringManagedTransactionFactory(),
                dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(GenerationTraceMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        GenerationTraceMapper mapper = new SqlSessionTemplate(sessionFactory)
                .getMapper(GenerationTraceMapper.class);
        traceService = new DefaultGenerationTraceService(
                new DefaultGenerationTracePersistenceService(mapper), null);
        transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void closeAndCleanDatabase() throws Exception {
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
        }
    }

    @Test
    void actualPromptSelectionMustBeStoredOnceAndParticipateInCallIdIdempotency()
            throws Exception {
        GenerationModelCallCommand command = command(HASH);

        transactionTemplate.executeWithoutResult(ignored -> traceService.recordModelCall(command));
        transactionTemplate.executeWithoutResult(ignored -> traceService.recordModelCall(command));

        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD)) {
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM generation_model_call"));
            assertEquals(1, scalar(connection,
                    "SELECT COUNT(*) FROM generation_model_prompt_selection"));
            assertEquals("codegen-vue-project", text(connection,
                    "SELECT promptKey FROM generation_model_prompt_selection"));
            assertEquals("canary", text(connection,
                    "SELECT channel FROM generation_model_prompt_selection"));
            assertEquals(HASH, text(connection,
                    "SELECT contentHash FROM generation_model_prompt_selection"));
        }

        GenerationModelCallCommand conflicting = command("b".repeat(64));
        assertThrows(BusinessException.class, () -> transactionTemplate.executeWithoutResult(
                ignored -> traceService.recordModelCall(conflicting)));
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD)) {
            assertEquals(1, scalar(connection,
                    "SELECT COUNT(*) FROM generation_model_prompt_selection"));
            assertEquals(HASH, text(connection,
                    "SELECT contentHash FROM generation_model_prompt_selection"));
        }
    }

    private GenerationModelCallCommand command(String contentHash) {
        return new GenerationModelCallCommand(
                CALL_ID,
                "prompt-canary-task",
                11L,
                7L,
                "integration-provider",
                "integration-model",
                GenerationModelCallStatus.SUCCESS,
                "provider-request-1",
                80,
                20,
                100,
                250L,
                "STOP",
                GenerationModelUsageSource.OFFICIAL,
                null,
                new GenerationModelCallProvenance(
                        HASH,
                        HASH,
                        HASH,
                        HASH,
                        2,
                        3,
                        "{}",
                        List.of(new GenerationPromptSelectionProvenance(
                                "codegen-vue-project",
                                "v2",
                                "canary",
                                contentHash,
                                HASH))));
    }

    private static void recreateDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
            statement.execute("CREATE DATABASE " + DATABASE
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private static int scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static String text(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
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
