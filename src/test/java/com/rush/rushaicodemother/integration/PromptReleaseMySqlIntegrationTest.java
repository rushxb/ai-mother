package com.rush.rushaicodemother.integration;

import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseAction;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseConflictException;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseMutation;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.infrastructure.persistence.prompt.MyBatisPromptReleaseRepository;
import com.rush.rushaicodemother.mapper.AiPromptReleaseMapper;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class PromptReleaseMySqlIntegrationTest {

    private static final String DATABASE = "ai_mother_prompt_release_it";
    private static final String ADMIN_URL = requiredProperty("integration.mysql.admin-url");
    private static final String USERNAME = requiredProperty("integration.mysql.username");
    private static final String PASSWORD = requiredProperty("integration.mysql.password");
    private static final String JDBC_URL = databaseUrl(ADMIN_URL, DATABASE);

    private static PooledDataSource dataSource;
    private static TransactionTemplate transactions;
    private static MyBatisPromptReleaseRepository repository;

    @BeforeAll
    static void migrateAndConfigureRepository() throws Exception {
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
                "prompt-release-it",
                new SpringManagedTransactionFactory(),
                dataSource
        );
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(AiPromptReleaseMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(sessionFactory);
        repository = new MyBatisPromptReleaseRepository(
                sessionTemplate.getMapper(AiPromptReleaseMapper.class));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void resetReleaseState() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM ai_prompt_release_history");
            statement.executeUpdate("DELETE FROM ai_prompt_release");
            statement.executeUpdate("UPDATE ai_prompt_release_bundle SET revision = 0, updatedBy = NULL WHERE id = 1");
        }
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
    }

    @Test
    void concurrentPublishMustProduceOneWinnerMonotonicBundleAndImmutableRollbackAudit()
            throws Exception {
        var initial = transaction(() -> repository.publish(mutation(
                new PromptReleaseSpec("v1", "", 0),
                0L,
                PromptReleaseAction.PUBLISH,
                null,
                "initial stable"
        )));
        assertEquals(1L, initial.revision());

        List<Long> outcomes = racePublish();
        assertEquals(1, outcomes.stream().filter(revision -> revision == 2L).count());
        assertEquals(1, outcomes.stream().filter(revision -> revision == -1L).count());
        assertEquals(2L, transaction(repository::loadCurrent).revision());
        assertEquals(2, repository.listHistory("test-prompt", 10).size());

        long activePromptRevision = transaction(repository::loadCurrent)
                .releases().get("test-prompt").revision();
        var rollback = transaction(() -> repository.publish(mutation(
                new PromptReleaseSpec("v1", "", 0),
                activePromptRevision,
                PromptReleaseAction.ROLLBACK,
                1L,
                "restore initial stable"
        )));

        assertEquals(3L, rollback.revision());
        var history = repository.listHistory("test-prompt", 10);
        assertEquals(3, history.size());
        assertEquals(PromptReleaseAction.ROLLBACK, history.getFirst().action());
        assertEquals(1L, history.getFirst().sourceRevision());
    }

    private List<Long> racePublish() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Long> first = executor.submit(concurrentPublish(
                    new PromptReleaseSpec("v2", "", 0), ready, start));
            Future<Long> second = executor.submit(concurrentPublish(
                    new PromptReleaseSpec("v1", "v2", 10), ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Callable<Long> concurrentPublish(PromptReleaseSpec release,
                                             CountDownLatch ready,
                                             CountDownLatch start) {
        return () -> {
            ready.countDown();
            assertTrue(start.await(5, TimeUnit.SECONDS));
            try {
                return transaction(() -> repository.publish(mutation(
                        release,
                        1L,
                        PromptReleaseAction.PUBLISH,
                        null,
                        "concurrent publish"
                ))).revision();
            } catch (PromptReleaseConflictException exception) {
                return -1L;
            }
        };
    }

    private PromptReleaseMutation mutation(PromptReleaseSpec release,
                                           long expectedRevision,
                                           PromptReleaseAction action,
                                           Long sourceRevision,
                                           String note) {
        return new PromptReleaseMutation(
                "test-prompt",
                release,
                expectedRevision,
                9L,
                note,
                action,
                sourceRevision
        );
    }

    private <T> T transaction(Supplier<T> action) {
        return Objects.requireNonNull(transactions.execute(status -> action.get()));
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
