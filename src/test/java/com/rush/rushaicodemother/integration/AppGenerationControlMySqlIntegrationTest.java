package com.rush.rushaicodemother.integration;

import com.rush.rushaicodemother.config.UserCreditProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.infrastructure.persistence.governance.MyBatisAppGenerationControlRepository;
import com.rush.rushaicodemother.infrastructure.persistence.task.MyBatisGenerationTaskAdmissionRepository;
import com.rush.rushaicodemother.mapper.AppGenerationControlMapper;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.mapper.UserCreditMapper;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationCreditMetricsCollector;
import com.rush.rushaicodemother.orchestration.governance.app.AppGenerationControlPolicy;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.runtime.task.AppGenerationControlAdmissionPolicy;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskAdmissionSnapshot;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskPreflightAdmissionContext;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.credit.DefaultUserCreditPersistenceService;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationCommand;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationQuote;
import com.rush.rushaicodemother.service.credit.ProviderCostGenerationUserBillingPolicy;
import com.rush.rushaicodemother.service.credit.UserCreditCostCalculator;
import com.rush.rushaicodemother.service.impl.UserCreditServiceImpl;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 应用控制策略和 appId 预算预授权的真实 MySQL 验收。 */
@Tag("integration")
class AppGenerationControlMySqlIntegrationTest {

    private static final String DATABASE = "ai_mother_app_control_it";
    private static final String ADMIN_URL = requiredProperty("integration.mysql.admin-url");
    private static final String USERNAME = requiredProperty("integration.mysql.username");
    private static final String PASSWORD = requiredProperty("integration.mysql.password");
    private static final String JDBC_URL = databaseUrl(ADMIN_URL, DATABASE);
    private static final List<InstanceHarness> INSTANCES = new ArrayList<>();

    @BeforeAll
    static void migrateSeedAndCreateInstances() throws Exception {
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
        seed();
        INSTANCES.add(createInstance());
        INSTANCES.add(createInstance());
    }

    @AfterAll
    static void closeAndCleanDatabase() throws Exception {
        for (InstanceHarness instance : INSTANCES) {
            instance.dataSource().forceCloseAll();
        }
        INSTANCES.clear();
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
        }
    }

    @Test
    void pauseVersioningAndCrossInstanceBudgetMustShareOneDurableFactSource()
            throws Exception {
        InstanceHarness first = INSTANCES.getFirst();
        AppGenerationControlAdmissionPolicy admissionPolicy =
                new AppGenerationControlAdmissionPolicy();

        assertThrows(BusinessException.class, () -> first.transactions().executeWithoutResult(
                ignored -> admissionPolicy.assertMayPreflight(preflightContext(
                        first.admissionRepository().lockScopeAndMeasure(3L, 7L, 11L),
                        3L))));
        assertEquals(0L, scalarLong("""
                SELECT COUNT(*) FROM user_credit_transaction
                WHERE type = 'GENERATION_RESERVATION' AND isDelete = 0
                """));

        first.transactions().executeWithoutResult(ignored -> {
            first.controlRepository().lockActiveApplication(11L);
            AppGenerationControlPolicy current = first.controlRepository().get(11L);
            AppGenerationControlPolicy updated = new AppGenerationControlPolicy(
                    11L, 2L, false, false, 1,
                    AppGenerationControlPolicy.ModelPolicy.ECONOMY_ONLY,
                    AppGenerationControlPolicy.DependencyMutationPolicy.DENY,
                    AppGenerationControlPolicy.DependencyNetworkPolicy.DENY,
                    AppGenerationControlPolicy.DangerousToolPolicy.DENY,
                    5L, 7L, Instant.parse("2026-08-28T08:00:00Z"));
            assertTrue(first.controlRepository().update(updated, current.version()));
            assertFalse(first.controlRepository().update(updated, current.version()));
        });
        AppGenerationControlPolicy persisted = first.controlRepository().get(11L);
        assertEquals(2L, persisted.version());
        assertEquals(5L, persisted.monthlyCreditLimit());
        assertEquals(AppGenerationControlPolicy.ModelPolicy.ECONOMY_ONLY,
                persisted.modelPolicy());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                int attempt = index;
                InstanceHarness instance = INSTANCES.get(index);
                outcomes.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        instance.transactions().executeWithoutResult(ignored -> {
                            GenerationTaskAdmissionSnapshot snapshot =
                                    instance.admissionRepository()
                                            .lockScopeAndMeasure(3L, 7L, 11L);
                            admissionPolicy.assertMayPreflight(preflightContext(snapshot, 3L));
                            instance.creditService().reserveGenerationPreflight(
                                    new GenerationCreditReservationCommand(
                                            "app-control-preflight-" + attempt,
                                            7L, 3L, 11L, 3L, "app-control-it"));
                        });
                        return true;
                    } catch (BusinessException rejected) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get(30, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertEquals(1, accepted);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertEquals(1L, scalarLong("""
                SELECT COUNT(*) FROM user_credit_transaction
                WHERE type = 'GENERATION_RESERVATION'
                  AND appId = 11
                  AND isDelete = 0
                """));
        assertEquals(3L, scalarLong("""
                SELECT COALESCE(SUM(-changeAmount), 0)
                FROM user_credit_transaction
                WHERE type = 'GENERATION_RESERVATION'
                  AND appId = 11
                  AND isDelete = 0
                """));
        assertEquals(97L, scalarLong("SELECT creditBalance FROM `user` WHERE id = 7"));
    }

    private GenerationTaskPreflightAdmissionContext preflightContext(
            GenerationTaskAdmissionSnapshot snapshot,
            long reservation) {
        return new GenerationTaskPreflightAdmissionContext(
                3L, 7L, CodeGenTypeEnum.VUE_PROJECT, IntentProfile.unknown(), snapshot,
                new GenerationCreditReservationQuote(100_000L, reservation, "app-control-it"));
    }

    private static InstanceHarness createInstance() {
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", JDBC_URL, USERNAME, PASSWORD);
        TransactionFactory transactionFactory = new SpringManagedTransactionFactory();
        Environment environment = new Environment("app-control-it", transactionFactory, dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(AppGenerationControlMapper.class);
        configuration.addMapper(GenerationTaskRuntimeMapper.class);
        configuration.addMapper(UserCreditMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate sessions = new SqlSessionTemplate(sessionFactory);
        MyBatisAppGenerationControlRepository controlRepository =
                new MyBatisAppGenerationControlRepository(
                        sessions.getMapper(AppGenerationControlMapper.class));
        MyBatisGenerationTaskAdmissionRepository admissionRepository =
                new MyBatisGenerationTaskAdmissionRepository(
                        sessions.getMapper(GenerationTaskRuntimeMapper.class),
                        controlRepository);
        UserCreditProperties properties = new UserCreditProperties();
        properties.setTokensPerCredit(1L);
        UserCreditService creditService = new UserCreditServiceImpl(
                new DefaultUserCreditPersistenceService(
                        sessions.getMapper(UserCreditMapper.class)),
                new UserCreditCostCalculator(properties),
                mock(GenerationCreditMetricsCollector.class),
                new ProviderCostGenerationUserBillingPolicy());
        return new InstanceHarness(
                dataSource,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                controlRepository,
                admissionRepository,
                creditService);
    }

    private static void seed() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO `user` (id, userAccount, userPassword, userName, creditBalance)
                    VALUES (7, 'app-control-it', 'not-a-real-password', 'App Control IT', 100)
                    """);
            statement.executeUpdate("""
                    INSERT INTO tenant
                        (id, tenantKey, tenantType, displayName, ownerUserId, status, isDelete)
                    VALUES
                        (3, 'organization:app-control-it', 'organization',
                         'App Control IT', 7, 'active', 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO app (id, appName, userId, tenantId, isDelete)
                    VALUES (11, 'app-control-it-app', 7, 3, 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO app_generation_control (
                        appId, generationPaused, emergencyStopped, maxConcurrentTasks,
                        modelPolicy, dependencyMutationPolicy, dependencyNetworkPolicy,
                        dangerousToolPolicy, monthlyCreditLimit, version, updatedBy)
                    VALUES (
                        11, 1, 0, 1, 'PLATFORM_DEFAULT', 'ALLOW',
                        'TRUSTED_REGISTRY_ONLY', 'REQUIRE_APPROVAL', 5, 1, 7)
                    """);
        }
    }

    private static long scalarLong(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
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

    private record InstanceHarness(
            PooledDataSource dataSource,
            TransactionTemplate transactions,
            MyBatisAppGenerationControlRepository controlRepository,
            MyBatisGenerationTaskAdmissionRepository admissionRepository,
            UserCreditService creditService) {
    }
}
