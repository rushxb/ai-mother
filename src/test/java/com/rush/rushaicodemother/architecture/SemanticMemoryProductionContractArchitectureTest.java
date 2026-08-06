package com.rush.rushaicodemother.architecture;

import com.rush.rushaicodemother.config.production.ProfileDefaultsEnvironmentPostProcessor;
import com.rush.rushaicodemother.memory.SemanticMemoryContract;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticMemoryProductionContractArchitectureTest {

    @Test
    void migrationsMustProvideLeasedDeletionAndVersionedReplayContracts() throws Exception {
        String outboxMigration = normalized(Path.of(
                "sql/migrations/V20260721_1__semantic_memory_production_contract.sql"));
        String replayMigration = normalized(Path.of(
                "sql/migrations/V20260721_2__semantic_memory_v2_reindex_contract.sql"));
        String bootstrap = normalized(Path.of("sql/create_table.sql"));

        assertTrue(outboxMigration.contains("semantic_memory_deletion_outbox"));
        assertTrue(outboxMigration.contains("memoryindexleaseowner"));
        assertTrue(outboxMigration.contains("idx_memory_outbox_claim"));
        assertTrue(replayMigration.contains("memoryindexcontractversion"));
        assertTrue(replayMigration.contains("idx_memory_outbox_contract_claim"));
        assertTrue(replayMigration.contains("chk_generation_task_memory_contract_version"));
        assertFalse(replayMigration.contains("set memoryindexedat = null"),
                "contract upgrades must not require a blocking bulk rewrite of task history");
        assertTrue(bootstrap.contains("memoryindexcontractversion int"));
        assertTrue(bootstrap.contains("semantic_memory_deletion_outbox"));
    }

    /**
     * 生产 Profile yaml 已删除，Milvus 安全硬化改由
     * {@link ProfileDefaultsEnvironmentPostProcessor} 注入，默认值必须继续 fail-closed。
     */
    @Test
    void productionProfileMustFailClosedOnInsecureOrUnverifiedMilvus() throws Exception {
        String defaults = normalized(Path.of("src/main/resources/application.yml"));

        StandardEnvironment production = productionEnvironment();
        assertEquals("true", production.getProperty("app.memory.long-term.enabled"));
        assertEquals("true", production.getProperty("app.memory.long-term.authentication-required"));
        assertEquals("true", production.getProperty("app.memory.long-term.tls-required"));
        assertEquals("true", production.getProperty("app.memory.long-term.verify-on-startup"));
        assertEquals("true", production.getProperty("app.memory.outbox.enabled"));
        assertTrue(defaults.contains("collection-name: ${milvus_memory_collection:generation_memory_v2}"));
        assertEquals(3, SemanticMemoryContract.INDEX_VERSION);
    }

    /**
     * 关闭认证或 TLS 必须是部署方的显式选择，
     * 因此固定配置保留 {@code ${ENV:true}} 占位符形态而非写死字面量。
     */
    @Test
    void insecureMilvusMustRequireExplicitDeploymentOptOut() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");
        environment.getPropertySources().addFirst(new MapPropertySource(
                "显式关闭 Milvus 安全要求",
                Map.of("MILVUS_TLS_REQUIRED", "false",
                        "MILVUS_AUTHENTICATION_REQUIRED", "false")));

        new ProfileDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertFalse(Boolean.parseBoolean(
                environment.getProperty("app.memory.long-term.tls-required")));
        assertFalse(Boolean.parseBoolean(
                environment.getProperty("app.memory.long-term.authentication-required")));
    }

    /** 返回注入生产固定配置后的环境。 */
    private StandardEnvironment productionEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");
        new ProfileDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        return environment;
    }

    private String normalized(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
