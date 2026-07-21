package com.rush.rushaicodemother.integration;

import com.rush.rushaicodemother.infrastructure.persistence.devserver.MyBatisDevServerSessionRegistry;
import com.rush.rushaicodemother.mapper.DevServerSessionMapper;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionClaimResult;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRecord;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRegistration;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRegistry;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionState;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class DevServerSessionRegistryMySqlIntegrationTest {

    private static final String DATABASE = "ai_mother_dev_server_it";
    private static final String ADMIN_URL = requiredProperty("integration.mysql.admin-url");
    private static final String USERNAME = requiredProperty("integration.mysql.username");
    private static final String PASSWORD = requiredProperty("integration.mysql.password");
    private static final String JDBC_URL = databaseUrl(ADMIN_URL, DATABASE);
    private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");

    private static PooledDataSource dataSource;
    private static TransactionTemplate transactions;
    private static DevServerSessionRegistry registry;

    @BeforeAll
    static void migrateAndConfigureRegistry() throws Exception {
        recreateDatabase();
        Flyway.configure()
                .dataSource(JDBC_URL, USERNAME, PASSWORD)
                .locations("filesystem:sql/migrations")
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load()
                .migrate();
        insertUser(101L, "dev-server-owner-101");
        insertUser(102L, "dev-server-owner-102");

        dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", JDBC_URL, USERNAME, PASSWORD);
        Environment environment = new Environment(
                "dev-server-registry-it",
                new SpringManagedTransactionFactory(),
                dataSource
        );
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(DevServerSessionMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(sessionFactory);
        registry = new MyBatisDevServerSessionRegistry(
                sessionTemplate.getMapper(DevServerSessionMapper.class));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void clearSessions() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM dev_server_session");
        }
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
    }

    @Test
    void concurrentNodesMustHaveExactlyOneAppOwner() throws Exception {
        List<DevServerSessionClaimResult> results = raceClaims(
                registration(1001L, 101L, "node-a", "owner-a", 31001),
                registration(1001L, 101L, "node-b", "owner-b", 31002),
                4
        );

        assertEquals(1, frequency(results, DevServerSessionClaimResult.ACQUIRED));
        assertEquals(1, frequency(results, DevServerSessionClaimResult.ACTIVE_SESSION_EXISTS));
        DevServerSessionRecord stored = transaction(
                () -> registry.findByAppId(1001L).orElseThrow());
        assertEquals(DevServerSessionState.STARTING, stored.state());
        assertTrue(List.of("owner-a", "owner-b").contains(stored.leaseOwner()));
    }

    @Test
    void userQuotaMustSerializeConcurrentClaimsForDifferentApps() throws Exception {
        List<DevServerSessionClaimResult> results = raceClaims(
                registration(2001L, 101L, "node-a", "quota-owner-a", 32001),
                registration(2002L, 101L, "node-b", "quota-owner-b", 32002),
                1
        );

        assertEquals(1, frequency(results, DevServerSessionClaimResult.ACQUIRED));
        assertEquals(1, frequency(results, DevServerSessionClaimResult.USER_QUOTA_EXCEEDED));
    }

    @Test
    void resourceManifestAndExpiredRecoveryMustRemainFenced() {
        DevServerSessionRegistration initial = registration(
                3001L, 102L, "node-a", "initial-owner", 33001);
        assertEquals(DevServerSessionClaimResult.ACQUIRED, transaction(() ->
                registry.claimStarting(initial, NOW, NOW.plusSeconds(30), 2)));
        assertTrue(transaction(() -> registry.recordStartingResources(
                3001L,
                "initial-owner",
                "container",
                List.of("dev-container", "preview-gateway"),
                NOW.plusSeconds(1),
                NOW.plusSeconds(31)
        )));

        DevServerSessionRecord starting = transaction(
                () -> registry.findByAppId(3001L).orElseThrow());
        assertEquals(DevServerSessionState.STARTING, starting.state());
        assertEquals("container", starting.sandboxBackend());
        assertEquals(List.of("dev-container", "preview-gateway"), starting.cleanupResourceIds());
        assertTrue(transaction(() -> registry.markRunning(
                3001L,
                "initial-owner",
                "container",
                starting.cleanupResourceIds(),
                NOW.plusSeconds(2),
                NOW.plusSeconds(5)
        )));

        Instant recoveryTime = NOW.plusSeconds(10);
        DevServerSessionRegistration replacement = registration(
                3001L, 102L, "node-b", "replacement-owner", 33002);
        assertEquals(DevServerSessionClaimResult.ACTIVE_SESSION_EXISTS, transaction(() ->
                registry.claimStarting(replacement, recoveryTime, recoveryTime.plusSeconds(30), 2)));

        DevServerSessionRecord expired = transaction(() -> registry.findExpired(recoveryTime, 10))
                .stream()
                .filter(candidate -> candidate.appId().equals(3001L))
                .findFirst()
                .orElseThrow();
        assertTrue(transaction(() -> registry.claimRecovery(
                expired, "recovery-node", "recovery-owner",
                recoveryTime, recoveryTime.plusSeconds(30))));
        assertFalse(transaction(() -> registry.claimRecovery(
                expired, "stale-node", "stale-owner",
                recoveryTime, recoveryTime.plusSeconds(30))));

        DevServerSessionRecord recovering = transaction(
                () -> registry.findByAppId(3001L).orElseThrow());
        assertEquals(DevServerSessionState.RECOVERING, recovering.state());
        assertEquals("recovery-owner", recovering.leaseOwner());
        assertEquals(List.of("dev-container", "preview-gateway"), recovering.cleanupResourceIds());
        assertTrue(transaction(() -> registry.markStopped(
                3001L, "recovery-owner", recoveryTime.plusSeconds(1), "orphan resources removed")));

        assertEquals(DevServerSessionClaimResult.ACQUIRED, transaction(() ->
                registry.claimStarting(
                        replacement,
                        recoveryTime.plusSeconds(2),
                        recoveryTime.plusSeconds(32),
                        2
                )));
        DevServerSessionRecord reclaimed = transaction(
                () -> registry.findByAppId(3001L).orElseThrow());
        assertEquals(DevServerSessionState.STARTING, reclaimed.state());
        assertEquals("replacement-owner", reclaimed.leaseOwner());
        assertNull(reclaimed.sandboxBackend());
        assertTrue(reclaimed.cleanupResourceIds().isEmpty());
    }

    private List<DevServerSessionClaimResult> raceClaims(
            DevServerSessionRegistration first,
            DevServerSessionRegistration second,
            int quota
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<DevServerSessionClaimResult> firstResult = executor.submit(
                    concurrentClaim(first, quota, ready, start));
            Future<DevServerSessionClaimResult> secondResult = executor.submit(
                    concurrentClaim(second, quota, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return List.of(
                    firstResult.get(10, TimeUnit.SECONDS),
                    secondResult.get(10, TimeUnit.SECONDS)
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Callable<DevServerSessionClaimResult> concurrentClaim(
            DevServerSessionRegistration registration,
            int quota,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return transaction(() -> registry.claimStarting(
                    registration, NOW, NOW.plusSeconds(30), quota));
        };
    }

    private DevServerSessionRegistration registration(
            Long appId,
            Long userId,
            String nodeId,
            String leaseOwner,
            int port
    ) {
        return new DevServerSessionRegistration(
                appId,
                userId,
                nodeId,
                leaseOwner,
                Path.of("target", "dev-server-it", appId.toString()),
                port
        );
    }

    private <T> T transaction(Supplier<T> action) {
        return Objects.requireNonNull(transactions.execute(status -> action.get()));
    }

    private long frequency(List<DevServerSessionClaimResult> results,
                           DevServerSessionClaimResult expected) {
        return results.stream().filter(expected::equals).count();
    }

    private static void recreateDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
            statement.execute("CREATE DATABASE " + DATABASE
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private static void insertUser(long userId, String account) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO user (id, userAccount, userPassword)
                    VALUES (%d, '%s', 'integration-test-password')
                    """.formatted(userId, account));
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
