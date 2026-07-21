package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        String production = Files.readString(Path.of(
                "src", "main", "resources", "application-prod.yml"));

        assertFalse(baseline.toLowerCase().contains("create database"));
        assertFalse(baseline.toLowerCase().contains("use rush_ai_code_mother"));
        assertFalse(baseline.toLowerCase().contains("add column if not exists"));
        assertFalse(baseline.contains("create table if not exists generation_tool_approval"));
        assertTrue(defaults.contains("enabled: ${FLYWAY_ENABLED:false}"));
        assertTrue(defaults.contains("clean-disabled: true"));
        assertTrue(defaults.contains("baseline-on-migrate: false"));
        assertTrue(production.contains("enabled: ${FLYWAY_ENABLED:true}"));
        assertTrue(production.contains("baseline-on-migrate: ${FLYWAY_BASELINE_ON_MIGRATE:false}"));
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
