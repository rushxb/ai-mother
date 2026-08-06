package com.rush.rushaicodemother.architecture;

import com.rush.rushaicodemother.config.production.ProfileDefaultsEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywayMigrationArchitectureTest {

    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("^V(.+)__.+\\.sql$");

    @Test
    void buildMustPackageVersionedMigrationsForFlyway() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertTrue(pom.contains("<artifactId>flyway-core</artifactId>"));
        assertTrue(pom.contains("<artifactId>flyway-mysql</artifactId>"));
        assertTrue(pom.contains("<directory>sql/migrations</directory>"));
        assertTrue(pom.contains("<targetPath>db/migration</targetPath>"));
        assertTrue(pom.contains("<include>B*.sql</include>"));
        assertTrue(pom.contains("<include>V*.sql</include>"));
    }

    @Test
    void baselineMustBeDatabaseAgnosticAndProductionMustRequireExplicitAdoption() throws Exception {
        String baseline = Files.readString(Path.of(
                "sql", "migrations", "B20260716_5__production_schema_baseline.sql"));
        String defaults = Files.readString(Path.of("src", "main", "resources", "application.yml"));

        assertFalse(baseline.toLowerCase().contains("create database"));
        assertFalse(baseline.toLowerCase().contains("use rush_ai_code_mother"));
        assertFalse(baseline.toLowerCase().contains("add column if not exists"));
        assertFalse(baseline.contains("create table if not exists generation_tool_approval"));
        assertTrue(defaults.contains("enabled: ${FLYWAY_ENABLED:false}"));
        assertTrue(defaults.contains("clean-disabled: true"));
        assertTrue(defaults.contains("baseline-on-migrate: false"));

        // 生产 Profile yaml 已删除，Flyway 固定配置改由 ProfileDefaultsEnvironmentPostProcessor 注入。
        StandardEnvironment production = productionEnvironment();
        assertEquals("true", production.getProperty("spring.flyway.enabled"));
        // 基线采纳必须显式开启：默认 false，避免对空库或未知库自动打基线。
        assertEquals("false", production.getProperty("spring.flyway.baseline-on-migrate"));
        assertEquals("20260716.5", production.getProperty("spring.flyway.baseline-version"));
    }

    /** 部署方仍可通过环境变量显式采纳基线。 */
    @Test
    void productionBaselineAdoptionMustRemainExplicitlyOverridable() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");
        environment.getPropertySources().addFirst(new MapPropertySource(
                "显式采纳基线",
                Map.of("FLYWAY_BASELINE_ON_MIGRATE", "true")));

        new ProfileDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertEquals("true", environment.getProperty("spring.flyway.baseline-on-migrate"));
    }

    /** 返回注入生产固定配置后的环境。 */
    private StandardEnvironment productionEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");
        new ProfileDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        return environment;
    }

    @Test
    void everyFlywayVersionMustBeUnique() throws Exception {
        Set<String> versions = new HashSet<>();
        try (var migrations = Files.list(Path.of("sql", "migrations"))) {
            migrations
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
                    .forEach(name -> {
                        Matcher matcher = VERSIONED_MIGRATION.matcher(name);
                        assertTrue(matcher.matches(), "invalid Flyway migration name: " + name);
                        String flywayVersion = matcher.group(1).replace('_', '.');
                        assertTrue(versions.add(flywayVersion),
                                "duplicate Flyway migration version: " + flywayVersion);
                    });
        }
    }

    @Test
    void migrationsMustNotUseMariaDbOnlyConditionalColumnSyntax() throws Exception {
        try (var migrations = Files.list(Path.of("sql", "migrations"))) {
            migrations
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .forEach(path -> {
                        try {
                            String sql = Files.readString(path).toLowerCase();
                            assertFalse(sql.contains("add column if not exists"),
                                    "MySQL does not support ADD COLUMN IF NOT EXISTS: " + path);
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
        }
    }
}
