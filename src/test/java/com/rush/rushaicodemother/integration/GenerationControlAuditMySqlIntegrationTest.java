package com.rush.rushaicodemother.integration;

import com.rush.rushaicodemother.infrastructure.persistence.audit.MyBatisGenerationControlAuditStore;
import com.rush.rushaicodemother.mapper.GenerationControlAuditMapper;
import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditEvent;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditOutcome;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditResource;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditSubject;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class GenerationControlAuditMySqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");
    private static final String COMPLETED_EVENT = "11111111-1111-1111-1111-111111111111";
    private static final String EXPIRED_EVENT = "22222222-2222-2222-2222-222222222222";

    private static final String DATABASE = "ai_mother_control_audit_it";
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
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD)) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    "sql/migrations/V20260828_5__generation_control_audit.sql"));
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
    void auditMustTransitionOnceAndDeleteOnlyExpiredRows() throws Exception {
        try (var session = sqlSessionFactory().openSession(true)) {
            MyBatisGenerationControlAuditStore store = new MyBatisGenerationControlAuditStore(
                    session.getMapper(GenerationControlAuditMapper.class));
            store.start(event(
                    COMPLETED_EVENT, GenerationControlAuditSubject.ActorType.USER, 7L,
                    NOW, NOW.plusSeconds(3600)));
            assertTrue(store.complete(
                    COMPLETED_EVENT, GenerationControlAuditOutcome.SUCCESS, "OK", NOW.plusSeconds(1)));
            assertFalse(store.complete(
                    COMPLETED_EVENT, GenerationControlAuditOutcome.FAILED,
                    "INTERNAL_ERROR", NOW.plusSeconds(2)));

            store.start(event(
                    EXPIRED_EVENT, GenerationControlAuditSubject.ActorType.ANONYMOUS, null,
                    NOW.minusSeconds(7200), NOW.minusSeconds(1)));
            assertEquals(1, store.deleteExpired(NOW, 100));
        }

        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT eventId, actorUserId, outcome, resultCode, completedAt
                     FROM generation_control_audit_event
                     ORDER BY id
                     """)) {
            assertTrue(result.next());
            assertEquals(COMPLETED_EVENT, result.getString("eventId"));
            assertEquals(7L, result.getLong("actorUserId"));
            assertEquals("SUCCESS", result.getString("outcome"));
            assertEquals("OK", result.getString("resultCode"));
            assertTrue(result.getTimestamp("completedAt") != null);
            assertFalse(result.next());
        }
    }

    private GenerationControlAuditEvent event(String eventId,
                                               GenerationControlAuditSubject.ActorType actorType,
                                               Long actorUserId,
                                               Instant startedAt,
                                               Instant expiresAt) {
        return new GenerationControlAuditEvent(
                eventId,
                GenerationControlPermission.TASK_CANCEL,
                GenerationControlAuditResource.TASK,
                "task-1",
                actorType,
                actorUserId,
                GenerationControlAuditSubject.Transport.HTTP,
                GenerationControlAuditOutcome.STARTED,
                null,
                startedAt,
                null,
                expiresAt);
    }

    private SqlSessionFactory sqlSessionFactory() {
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", JDBC_URL, USERNAME, PASSWORD);
        Environment environment = new Environment(
                "test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(GenerationControlAuditMapper.class);
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
