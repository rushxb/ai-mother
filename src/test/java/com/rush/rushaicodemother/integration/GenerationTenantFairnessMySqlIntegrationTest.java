package com.rush.rushaicodemother.integration;

import com.rush.rushaicodemother.config.GenerationCreditReservationProperties;
import com.rush.rushaicodemother.config.GenerationTaskAdmissionProperties;
import com.rush.rushaicodemother.config.UserCreditProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.infrastructure.persistence.task.MyBatisDurableGenerationTaskRepository;
import com.rush.rushaicodemother.infrastructure.persistence.task.MyBatisGenerationTaskAdmissionRepository;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.mapper.UserCreditMapper;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationCreditMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.GenerationTenantAdmissionMetricsCollector;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskAdmissionPolicy;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskAdmissionService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskConcurrencyAdmissionPolicy;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskLeaseCoordinator;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskLeaseOwnerProvider;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskLeaseProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTenantQuotaAdmissionPolicy;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeService;
import com.rush.rushaicodemother.service.credit.DefaultUserCreditPersistenceService;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationPolicy;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 两个独立应用实例共享 MySQL 时的租户准入与公平补投验收。 */
@Tag("integration")
class GenerationTenantFairnessMySqlIntegrationTest {

    private static final String DATABASE = "ai_mother_tenant_fairness_it";
    private static final String ADMIN_URL = requiredProperty("integration.mysql.admin-url");
    private static final String USERNAME = requiredProperty("integration.mysql.username");
    private static final String PASSWORD = requiredProperty("integration.mysql.password");
    private static final String JDBC_URL = databaseUrl(ADMIN_URL, DATABASE);
    private static final int MONTHLY_BUDGET = 5;
    private static final int ADMISSION_ATTEMPTS = 12;
    private static final Instant FAIRNESS_NOW = Instant.parse("2026-08-28T04:00:00Z");

    private static final List<InstanceHarness> INSTANCES = new ArrayList<>();
    private static SimpleMeterRegistry meterRegistry;

    @BeforeAll
    static void migrateSeedAndConfigureInstances() throws Exception {
        recreateDatabase();
        Flyway.configure()
                .dataSource(JDBC_URL, USERNAME, PASSWORD)
                .locations("filesystem:sql/migrations")
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load()
                .migrate();
        seedTenantsUsersAppsAndQueue();

        meterRegistry = new SimpleMeterRegistry();
        INSTANCES.add(createInstance("tenant-fairness-node-a"));
        INSTANCES.add(createInstance("tenant-fairness-node-b"));
    }

    @AfterAll
    static void closeAndCleanDatabase() throws Exception {
        for (InstanceHarness instance : INSTANCES) {
            instance.dataSource().forceCloseAll();
        }
        INSTANCES.clear();
        if (meterRegistry != null) {
            meterRegistry.close();
        }
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
        }
    }

    @Test
    void concurrentAdmissionsAcrossInstancesMustNotDeadlockOrOverspendTenantBudget()
            throws Exception {
        CountDownLatch ready = new CountDownLatch(ADMISSION_ATTEMPTS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(ADMISSION_ATTEMPTS);
        List<Future<AdmissionOutcome>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < ADMISSION_ATTEMPTS; index++) {
                int attempt = index;
                InstanceHarness instance = INSTANCES.get(index % INSTANCES.size());
                futures.add(executor.submit(admissionAttempt(instance, attempt, ready, start)));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发准入线程未按时就绪");
            start.countDown();

            List<AdmissionOutcome> outcomes = new ArrayList<>();
            for (Future<AdmissionOutcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }

            assertEquals(MONTHLY_BUDGET,
                    outcomes.stream().filter(AdmissionOutcome.ADMITTED::equals).count());
            assertEquals(ADMISSION_ATTEMPTS - MONTHLY_BUDGET,
                    outcomes.stream().filter(AdmissionOutcome.BUDGET_REJECTED::equals).count());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(MONTHLY_BUDGET, scalarLong("""
                SELECT COUNT(*) FROM generation_task
                WHERE tenantId = 3 AND taskId LIKE 'tenant-admission-%' AND isDelete = 0
                """));
        assertEquals(MONTHLY_BUDGET, scalarLong("""
                SELECT COUNT(*) FROM user_credit_transaction
                WHERE tenantId = 3 AND type = 'GENERATION_RESERVATION'
                  AND bizId LIKE 'tenant-admission-%' AND isDelete = 0
                """));
        assertEquals(MONTHLY_BUDGET, scalarLong("""
                SELECT COALESCE(SUM(-changeAmount), 0) FROM user_credit_transaction
                WHERE tenantId = 3 AND type = 'GENERATION_RESERVATION'
                  AND bizId LIKE 'tenant-admission-%' AND isDelete = 0
                """));
        assertEquals(0L, scalarLong("""
                SELECT COUNT(*)
                FROM user_credit_transaction reservation
                LEFT JOIN generation_task task ON task.taskId = reservation.bizId AND task.isDelete = 0
                WHERE reservation.tenantId = 3
                  AND reservation.type = 'GENERATION_RESERVATION'
                  AND reservation.bizId LIKE 'tenant-admission-%'
                  AND reservation.isDelete = 0
                  AND task.id IS NULL
                """));
        assertEquals(ADMISSION_ATTEMPTS * 100L - MONTHLY_BUDGET, scalarLong("""
                SELECT SUM(creditBalance) FROM `user` WHERE id BETWEEN 100 AND 111 AND isDelete = 0
                """));
    }

    @Test
    void databaseFallbackAcrossInstancesMustRotateTenantsUntilEveryTaskIsDispatched()
            throws Exception {
        List<String> firstWave = dispatchWave(INSTANCES.get(0), 3);
        List<String> secondWave = dispatchWave(INSTANCES.get(1), 3);
        List<String> thirdWave = dispatchWave(INSTANCES.get(0), 3);

        assertEquals(List.of("fair-a-1", "fair-b-1", "fair-c-1"), firstWave,
                "每个有积压的租户必须先获得一个分派槽位");
        assertEquals(List.of("fair-a-2", "fair-b-2", "fair-a-3"), secondWave);
        assertEquals(List.of("fair-a-4", "fair-a-5"), thirdWave);

        List<String> allDispatched = new ArrayList<>();
        allDispatched.addAll(firstWave);
        allDispatched.addAll(secondWave);
        allDispatched.addAll(thirdWave);
        assertEquals(8, allDispatched.size());
        assertEquals(8, new HashSet<>(allDispatched).size(), "跨实例补投不得遗漏或重复本轮任务");
        assertEquals(Set.of(
                        "fair-a-1", "fair-a-2", "fair-a-3", "fair-a-4", "fair-a-5",
                        "fair-b-1", "fair-b-2", "fair-c-1"),
                new HashSet<>(allDispatched));
        assertEquals(0, INSTANCES.get(1).repository().findDispatchableQueuedTaskIds(
                FAIRNESS_NOW, FAIRNESS_NOW.minusSeconds(1), 3).size());
        assertEquals(8L, scalarLong("""
                SELECT COUNT(*) FROM generation_task
                WHERE taskId LIKE 'fair-%' AND dispatchAttempt = 1 AND isDelete = 0
                """));
    }

    private static Callable<AdmissionOutcome> admissionAttempt(InstanceHarness instance,
                                                                int attempt,
                                                                CountDownLatch ready,
                                                                CountDownLatch start) {
        return () -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS), "并发准入开始信号超时");
            try {
                instance.transactions().executeWithoutResult(ignored ->
                        instance.admissionService().admit(command(attempt)));
                return AdmissionOutcome.ADMITTED;
            } catch (BusinessException rejected) {
                assertTrue(rejected.getMessage().contains("租户本月生成预算不足"),
                        () -> "出现非预算业务拒绝: " + rejected.getMessage());
                return AdmissionOutcome.BUDGET_REJECTED;
            }
        };
    }

    private static List<String> dispatchWave(InstanceHarness instance, int limit) {
        List<String> taskIds = instance.repository().findDispatchableQueuedTaskIds(
                FAIRNESS_NOW, FAIRNESS_NOW.minusSeconds(1), limit);
        for (String taskId : taskIds) {
            instance.transactions().executeWithoutResult(ignored ->
                    instance.repository().recordDispatchSuccess(taskId, FAIRNESS_NOW));
        }
        return taskIds;
    }

    private static GenerationTaskCommand command(int attempt) {
        Instant submittedAt = Instant.parse("2026-08-28T10:00:00Z").plusSeconds(attempt);
        return new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                "tenant-admission-" + attempt,
                1000L + attempt,
                100L + attempt,
                3L,
                "跨实例租户预算并发验收 " + attempt,
                CodeGenTypeEnum.MULTI_FILE,
                GenerationMode.LIGHT_EDIT,
                0.95,
                "integration-test",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD,
                "",
                submittedAt,
                submittedAt.plusSeconds(600)
        );
    }

    private static InstanceHarness createInstance(String ownerId) {
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", JDBC_URL, USERNAME, PASSWORD);
        dataSource.setPoolMaximumActiveConnections(ADMISSION_ATTEMPTS);
        TransactionFactory transactionFactory = new SpringManagedTransactionFactory();
        Environment environment = new Environment(ownerId, transactionFactory, dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(GenerationTaskRuntimeMapper.class);
        configuration.addMapper(UserCreditMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(sessionFactory);

        GenerationTaskRuntimeMapper runtimeMapper =
                sessionTemplate.getMapper(GenerationTaskRuntimeMapper.class);
        MyBatisDurableGenerationTaskRepository durableRepository =
                new MyBatisDurableGenerationTaskRepository(runtimeMapper);
        MyBatisGenerationTaskAdmissionRepository admissionRepository =
                new MyBatisGenerationTaskAdmissionRepository(runtimeMapper);

        UserCreditProperties creditProperties = new UserCreditProperties();
        creditProperties.setTokensPerCredit(1L);
        UserCreditCostCalculator costCalculator = new UserCreditCostCalculator(creditProperties);
        UserCreditService creditService = new UserCreditServiceImpl(
                new DefaultUserCreditPersistenceService(
                        sessionTemplate.getMapper(UserCreditMapper.class)),
                costCalculator,
                new GenerationCreditMetricsCollector(meterRegistry),
                new ProviderCostGenerationUserBillingPolicy()
        );

        GenerationCreditReservationProperties reservationProperties =
                new GenerationCreditReservationProperties();
        reservationProperties.setLightEditEstimatedTokens(1L);
        reservationProperties.setMultiFileMultiplierPercent(100);
        GenerationCreditReservationPolicy reservationPolicy =
                new GenerationCreditReservationPolicy(reservationProperties, costCalculator);

        GenerationTaskAdmissionProperties admissionProperties =
                new GenerationTaskAdmissionProperties();
        admissionProperties.setMaxNonTerminalTasksPerUser(100);
        admissionProperties.setMaxNonTerminalTasksPerTenant(100);
        admissionProperties.setMaxHeavyTasksPerTenant(100);
        admissionProperties.setMonthlyCreditLimitPerTenant(MONTHLY_BUDGET);
        List<GenerationTaskAdmissionPolicy> policies = List.of(
                new GenerationTaskConcurrencyAdmissionPolicy(admissionProperties),
                new GenerationTenantQuotaAdmissionPolicy(
                        admissionProperties, GenerationTenantAdmissionMetricsCollector.noOp())
        );

        GenerationTaskLeaseProperties leaseProperties = new GenerationTaskLeaseProperties();
        leaseProperties.setOwnerId(ownerId);
        GenerationTaskLeaseCoordinator leaseCoordinator = new GenerationTaskLeaseCoordinator(
                durableRepository,
                leaseProperties,
                new GenerationTaskLeaseOwnerProvider(leaseProperties),
                new GenerationExecutionContextService(new GenerationRuntimeProperties())
        );
        GenerationTaskRuntimeLifecycleService lifecycleService =
                new GenerationTaskRuntimeLifecycleService(
                        durableRepository,
                        leaseCoordinator,
                        mock(GenerationOrchestrationMetricsCollector.class),
                        mock(GenerationPerformanceMonitorService.class)
                );
        GenerationTaskAdmissionService admissionService = new GenerationTaskAdmissionService(
                reservationPolicy,
                policies,
                admissionRepository,
                mock(AiModelRuntimeService.class),
                creditService,
                lifecycleService
        );
        return new InstanceHarness(
                dataSource,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                admissionService,
                durableRepository
        );
    }

    private static long scalarLong(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static void seedTenantsUsersAppsAndQueue() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            StringBuilder users = new StringBuilder("""
                    INSERT INTO `user`
                        (id, userAccount, userPassword, userName, creditBalance)
                    VALUES
                    """);
            StringBuilder apps = new StringBuilder("""
                    INSERT INTO app (id, appName, userId, tenantId, isDelete)
                    VALUES
                    """);
            for (int index = 0; index < ADMISSION_ATTEMPTS; index++) {
                if (index > 0) {
                    users.append(",\n");
                    apps.append(",\n");
                }
                users.append("(").append(100 + index)
                        .append(", 'tenant-admission-user-").append(index)
                        .append("', 'not-a-real-password', 'Admission ").append(index)
                        .append("', 100)");
                apps.append("(").append(1000 + index)
                        .append(", 'tenant-admission-app-").append(index)
                        .append("', ").append(100 + index).append(", 3, 0)");
            }
            users.append(",\n")
                    .append("(200, 'fair-user-a', 'not-a-real-password', 'Fair A', 100),\n")
                    .append("(201, 'fair-user-b', 'not-a-real-password', 'Fair B', 100),\n")
                    .append("(202, 'fair-user-c', 'not-a-real-password', 'Fair C', 100)");

            statement.executeUpdate(users.toString());
            statement.executeUpdate("""
                    INSERT INTO tenant
                        (id, tenantKey, tenantType, displayName, ownerUserId, status, isDelete)
                    VALUES
                        (3, 'organization:tenant-admission-it', 'organization',
                         'Tenant Admission IT', 100, 'active', 0),
                        (20, 'organization:fair-a-it', 'organization',
                         'Fair A IT', 200, 'active', 0),
                        (21, 'organization:fair-b-it', 'organization',
                         'Fair B IT', 201, 'active', 0),
                        (22, 'organization:fair-c-it', 'organization',
                         'Fair C IT', 202, 'active', 0)
                    """);
            statement.executeUpdate(apps.toString());
            statement.executeUpdate("""
                    INSERT INTO app (id, appName, userId, tenantId, isDelete)
                    VALUES
                        (2000, 'fair-app-a', 200, 20, 0),
                        (2001, 'fair-app-b', 201, 21, 0),
                        (2002, 'fair-app-c', 202, 22, 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO generation_task
                        (taskId, appId, userId, tenantId, route, status, stage,
                         submittedAt, deadlineAt, dispatchAttempt, isDelete)
                    VALUES
                        ('fair-a-1', 2000, 200, 20, 'lightweight_edit', 'queued', 'queued',
                         '2026-08-28 10:00:00', '2026-08-29 14:00:00', 0, 0),
                        ('fair-b-1', 2001, 201, 21, 'lightweight_edit', 'queued', 'queued',
                         '2026-08-28 10:01:00', '2026-08-29 14:00:00', 0, 0),
                        ('fair-c-1', 2002, 202, 22, 'lightweight_edit', 'queued', 'queued',
                         '2026-08-28 10:02:00', '2026-08-29 14:00:00', 0, 0),
                        ('fair-a-2', 2000, 200, 20, 'lightweight_edit', 'queued', 'queued',
                         '2026-08-28 10:03:00', '2026-08-29 14:00:00', 0, 0),
                        ('fair-b-2', 2001, 201, 21, 'lightweight_edit', 'queued', 'queued',
                         '2026-08-28 10:04:00', '2026-08-29 14:00:00', 0, 0),
                        ('fair-a-3', 2000, 200, 20, 'lightweight_edit', 'queued', 'queued',
                         '2026-08-28 10:05:00', '2026-08-29 14:00:00', 0, 0),
                        ('fair-a-4', 2000, 200, 20, 'lightweight_edit', 'queued', 'queued',
                         '2026-08-28 10:06:00', '2026-08-29 14:00:00', 0, 0),
                        ('fair-a-5', 2000, 200, 20, 'lightweight_edit', 'queued', 'queued',
                         '2026-08-28 10:07:00', '2026-08-29 14:00:00', 0, 0)
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

    private enum AdmissionOutcome {
        ADMITTED,
        BUDGET_REJECTED
    }

    private record InstanceHarness(
            PooledDataSource dataSource,
            TransactionTemplate transactions,
            GenerationTaskAdmissionService admissionService,
            MyBatisDurableGenerationTaskRepository repository
    ) {
    }
}
