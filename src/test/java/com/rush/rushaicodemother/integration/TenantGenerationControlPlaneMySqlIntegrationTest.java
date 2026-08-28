package com.rush.rushaicodemother.integration;

import com.rush.rushaicodemother.config.GenerationTaskAdmissionProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.infrastructure.persistence.governance.MyBatisTenantGenerationControlPlaneRepository;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.mapper.TenantGenerationControlPlaneMapper;
import com.rush.rushaicodemother.mapper.TenantMapper;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.governance.TenantGenerationControlPlaneService;
import com.rush.rushaicodemother.orchestration.governance.TenantGenerationControlPlaneSnapshot;
import com.rush.rushaicodemother.service.tenant.DefaultTenantPersistenceService;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("integration")
class TenantGenerationControlPlaneMySqlIntegrationTest {

    private static final String DATABASE = "ai_mother_tenant_control_plane_it";
    private static final String ADMIN_URL = requiredProperty("integration.mysql.admin-url");
    private static final String USERNAME = requiredProperty("integration.mysql.username");
    private static final String PASSWORD = requiredProperty("integration.mysql.password");
    private static final String JDBC_URL = databaseUrl(ADMIN_URL, DATABASE);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-28T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

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
                    VALUES
                        (7, 'tenant-control-admin', 'not-a-real-password', 'Tenant Admin', 1000),
                        (8, 'tenant-control-viewer', 'not-a-real-password', 'Tenant Viewer', 1000)
                    """);
            statement.executeUpdate("""
                    INSERT INTO tenant
                        (id, tenantKey, tenantType, displayName, ownerUserId, status, isDelete)
                    VALUES
                        (3, 'organization:tenant-control-it', 'organization',
                         'Tenant Control IT', 7, 'active', 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO tenant_membership
                        (tenantId, userId, role, status, joinedAt, isDelete)
                    VALUES
                        (3, 7, 'admin', 'active', '2026-08-01 00:00:00.000000', 0),
                        (3, 8, 'viewer', 'active', '2026-08-01 00:00:00.000000', 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO app (id, appName, userId, tenantId, isDelete)
                    VALUES (11, 'tenant-control-it-app', 7, 3, 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO generation_task
                        (taskId, appId, userId, tenantId, originalCodeGenType,
                         targetCodeGenType, route, status, stage, submittedAt,
                         deadlineAt, startTime, endTime, totalTokens,
                         creditCost, creditCharged, isDelete)
                    VALUES
                        ('tenant-cost-success-1', 11, 7, 3, 'single_file', 'multi_file',
                         'heavy_generation', 'success', 'completed',
                         '2026-08-10 10:00:00', '2026-08-10 10:20:00',
                         '2026-08-10 10:00:00', '2026-08-10 10:15:00', 200000, 4, 1, 0),
                        ('tenant-cost-failed-1', 11, 7, 3, 'multi_file', 'multi_file',
                         'heavy_generation', 'failed', 'failed',
                         '2026-08-11 10:00:00', '2026-08-11 10:20:00',
                         '2026-08-11 10:00:00', '2026-08-11 10:10:00', 100000, 2, 1, 0),
                        ('tenant-cost-success-2', 11, 7, 3, 'multi_file', 'multi_file',
                         'heavy_generation', 'success', 'completed',
                         '2026-08-12 10:00:00', '2026-08-12 10:20:00',
                         '2026-08-12 10:00:00', '2026-08-12 10:12:00', 150000, 3, 1, 0),
                        ('tenant-cost-old', 11, 7, 3, 'multi_file', 'multi_file',
                         'heavy_generation', 'success', 'completed',
                         '2026-07-20 10:00:00', '2026-07-20 10:20:00',
                         '2026-07-20 10:00:00', '2026-07-20 10:12:00', 500000, 100, 1, 0),
                        ('tenant-queue-1', 11, 7, 3, 'multi_file', 'multi_file',
                         'heavy_generation', 'queued', 'queued',
                         '2026-08-28 10:00:00', '2026-08-28 10:20:00',
                         '2026-08-28 10:00:00', NULL, 0, 0, 0, 0),
                        ('tenant-running-1', 11, 7, 3, 'single_file', 'single_file',
                         'lightweight_edit', 'running', 'generating',
                         '2026-08-28 10:01:00', '2026-08-28 10:21:00',
                         '2026-08-28 10:01:00', NULL, 0, 0, 0, 0),
                        ('tenant-approval-1', 11, 7, 3, 'multi_file', 'multi_file',
                         'heavy_generation', 'waiting_approval', 'approval',
                         '2026-08-28 10:02:00', '2026-08-28 10:22:00',
                         '2026-08-28 10:02:00', NULL, 0, 0, 0, 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO user_credit_transaction
                        (userId, tenantId, appId, changeAmount, balanceAfter, type,
                         bizId, remark, tokenCount, createTime)
                    VALUES
                        (7, 3, 11, -5, 995, 'GENERATION_RESERVATION',
                         'tenant-cost-success-1', 'reservation', NULL, '2026-08-10 10:00:00'),
                        (7, 3, 11, 1, 996, 'GENERATION_SETTLEMENT',
                         'tenant-cost-success-1', 'settlement', 200000, '2026-08-10 10:15:00'),
                        (7, 3, 11, -2, 994, 'GENERATION_RESERVATION',
                         'tenant-cost-failed-1', 'reservation', NULL, '2026-08-11 10:00:00'),
                        (7, 3, 11, -3, 991, 'GENERATION_CHARGE',
                         'tenant-cost-success-2', 'legacy-charge', 150000, '2026-08-12 10:12:00'),
                        (7, 3, 11, -100, 891, 'GENERATION_CHARGE',
                         'tenant-cost-old', 'old-charge', 500000, '2026-07-20 10:12:00')
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
    void realMySqlMustExposeAdminFactsAndRejectOrdinaryMembers() {
        SqlSessionFactory factory = sqlSessionFactory();
        try (var session = factory.openSession(true)) {
            TenantAuthorizationService authorizationService = new TenantAuthorizationService(
                    new DefaultTenantPersistenceService(session.getMapper(TenantMapper.class)));
            MyBatisTenantGenerationControlPlaneRepository repository =
                    new MyBatisTenantGenerationControlPlaneRepository(
                            session.getMapper(GenerationTaskRuntimeMapper.class),
                            session.getMapper(TenantGenerationControlPlaneMapper.class));
            TenantGenerationControlPlaneService service = new TenantGenerationControlPlaneService(
                    authorizationService, repository,
                    new GenerationTaskAdmissionProperties(), CLOCK);

            TenantGenerationControlPlaneSnapshot snapshot = service.get(
                    3L, User.builder().id(7L).build());

            assertEquals(9L, snapshot.budget().consumedCredit());
            assertEquals(9_991L, snapshot.budget().remainingCredit());
            assertEquals(1, snapshot.queue().queuedTasks());
            assertEquals(1, snapshot.queue().runningTasks());
            assertEquals(1, snapshot.queue().waitingApprovalTasks());
            assertEquals(3, snapshot.queue().totalNonTerminalTasks());
            assertEquals(2, snapshot.queue().heavyNonTerminalTasks());
            assertEquals(1, snapshot.scenarioCosts().size());
            assertEquals(3L, snapshot.scenarioCosts().getFirst().settledTasks());
            assertEquals(2L, snapshot.scenarioCosts().getFirst().successfulDeliveries());
            assertEquals(9L, snapshot.scenarioCosts().getFirst().totalCreditCost());
            assertEquals(new BigDecimal("4.50"),
                    snapshot.scenarioCosts().getFirst().unitSuccessfulCreditCost());
            assertEquals(0, snapshot.activeRejectionReasons().size());

            assertThrows(BusinessException.class,
                    () -> service.get(3L, User.builder().id(8L).build()));
        }
    }

    private SqlSessionFactory sqlSessionFactory() {
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", JDBC_URL, USERNAME, PASSWORD);
        Environment environment = new Environment(
                "test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(GenerationTaskRuntimeMapper.class);
        configuration.addMapper(TenantGenerationControlPlaneMapper.class);
        configuration.addMapper(TenantMapper.class);
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
