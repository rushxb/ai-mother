package com.rush.rushaicodemother.integration;

import com.rush.rushaicodemother.infrastructure.persistence.tool.MyBatisToolApprovalRepository;
import com.rush.rushaicodemother.mapper.GenerationToolApprovalMapper;
import com.rush.rushaicodemother.orchestration.tool.DestructiveToolAction;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalRecord;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalStatus;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionOutcome;
import com.rush.rushaicodemother.orchestration.tool.ToolInvocationCheckpoint;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class ToolApprovalMySqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final long REQUEST_EXECUTION_EPOCH = 3L;

    private static final String DATABASE = "ai_mother_tool_it";
    private static final String ADMIN_URL = requiredProperty("integration.mysql.admin-url");
    private static final String USERNAME = requiredProperty("integration.mysql.username");
    private static final String PASSWORD = requiredProperty("integration.mysql.password");
    private static final String JDBC_URL = databaseUrl(ADMIN_URL, DATABASE);

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
            statement.execute("CREATE DATABASE " + DATABASE
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try (var connection = DriverManager.getConnection(
                JDBC_URL, USERNAME, PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                // 本测试只装载审批相关迁移，提供新迁移回填语句所需的最小任务表契约。
                statement.execute("""
                        CREATE TABLE generation_task (
                            taskId varchar(128) PRIMARY KEY,
                            status varchar(32) NOT NULL,
                            executionEpoch bigint NOT NULL DEFAULT 0,
                            isDelete tinyint NOT NULL DEFAULT 0
                        )
                        """);
            }
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    "sql/migrations/V20260716_6__generation_tool_approval.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    "sql/migrations/V20260828_1__tool_approval_request_epoch.sql"));
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
    void approvalExecutionMustBeAtomicReplayableAndSingleOwner() {
        try (var session = sqlSessionFactory().openSession(false)) {
            MyBatisToolApprovalRepository repository = new MyBatisToolApprovalRepository(
                    session.getMapper(GenerationToolApprovalMapper.class));
            ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                    ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                    "call-1", "manageSnapshot", "{\"action\":\"deleteSnapshot\"}",
                    "{\"taskId\":\"task-mysql\"}", NOW);
            ToolApprovalRecord pending = new ToolApprovalRecord(
                    "a".repeat(64), "task-mysql", REQUEST_EXECUTION_EPOCH, 11L, 7L,
                    DestructiveToolAction.SNAPSHOT_DELETE, "{\"snapshotName\":\"safe\"}",
                    ToolApprovalStatus.PENDING, NOW, NOW.plusSeconds(600),
                    null, null, null, 0, checkpoint);

            repository.createPending(pending);
            assertTrue(repository.approve(
                    pending.taskId(), pending.requestExecutionEpoch(), pending.action(),
                    pending.approvalId(), 7L, NOW.plusSeconds(1)));
            assertTrue(repository.beginExecution(
                    pending.taskId(), pending.requestExecutionEpoch(), pending.action(), pending.approvalId(),
                    checkpoint.requestId(), NOW.plusSeconds(2), 3));
            assertFalse(repository.beginExecution(
                    pending.taskId(), pending.requestExecutionEpoch(), pending.action(), pending.approvalId(),
                    checkpoint.requestId(), NOW.plusSeconds(3), 3));

            ToolExecutionOutcome outcome = new ToolExecutionOutcome(false, "deleted safe");
            assertTrue(repository.completeExecution(
                    pending.taskId(), pending.requestExecutionEpoch(), pending.action(), pending.approvalId(),
                    checkpoint.requestId(), outcome, NOW.plusSeconds(4)));
            ToolApprovalRecord consumed = repository.find(
                    pending.taskId(), pending.requestExecutionEpoch(),
                    pending.action(), pending.approvalId()).orElseThrow();

            assertEquals(ToolApprovalStatus.CONSUMED, consumed.status());
            assertEquals(REQUEST_EXECUTION_EPOCH, consumed.requestExecutionEpoch());
            assertEquals(outcome, consumed.executionOutcome());
            assertEquals(1, consumed.executionAttempt());
            session.commit();
        }
    }

    private SqlSessionFactory sqlSessionFactory() {
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                JDBC_URL, USERNAME, PASSWORD);
        Environment environment = new Environment(
                "test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(GenerationToolApprovalMapper.class);
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
