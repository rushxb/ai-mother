package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 物理模型调用账本的数据库与调用边界门禁。 */
class GenerationModelInvocationLedgerArchitectureTest {

    private static final Path STARTED_LEDGER_MIGRATION = Path.of(
            "sql", "migrations", "V20260814_1__model_invocation_started_ledger.sql");

    @Test
    void startedInvocationMustBeAcceptedByUpgradeMigrationAndBootstrapSchemas() throws Exception {
        assertTrue(Files.exists(STARTED_LEDGER_MIGRATION),
                "既有数据库必须提供 STARTED 调用账本升级迁移");
        String migration = normalized(Files.readString(STARTED_LEDGER_MIGRATION));
        String createTable = normalized(Files.readString(Path.of("sql", "create_table.sql")));
        String schema = normalized(Files.readString(Path.of("sql", "schema.sql")));

        assertTrue(migration.contains("drop check chk_generation_model_call_status"));
        assertTrue(migration.contains(
                "check (callstatus in ('started', 'success', 'error'))"));
        assertTrue(createTable.contains(
                "check (callstatus in ('started', 'success', 'error'))"));
        assertTrue(schema.contains(
                "check (callstatus in ('started', 'success', 'error'))"));
    }

    @Test
    void recoveryIndexMustReplaceTheRedundantOutcomeIndex() throws Exception {
        String migration = normalized(Files.readString(Path.of(
                "sql", "migrations", "V20260814_2__model_invocation_purpose_billing.sql")));

        assertTrue(migration.contains("drop index idx_generation_model_call_outcome"));
        assertTrue(migration.contains(
                "idx_model_invocation_recovery (invocationpurpose, billingmode, callstatus, createtime)"));
    }

    @Test
    void everyProductionOpenAiBuilderMustStayBehindTheAuditedModelFactory() throws Exception {
        Path javaRoot = Path.of("src", "main", "java");
        try (var sources = Files.walk(javaRoot)) {
            sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            String source = Files.readString(path);
                            return source.contains("OpenAiChatModel.builder()")
                                    || source.contains("OpenAiStreamingChatModel.builder()");
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .forEach(path -> assertTrue(
                            path.endsWith(Path.of("ai", "model", "StreamingModelFactory.java")),
                            "物理 ChatModel 构建旁路统一账本: " + path));
        }
    }

    @Test
    void billingProjectionMustFailClosedForAnyUnknownBillableGenerationCost() throws Exception {
        String creditMapper = normalized(Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother",
                "mapper", "UserCreditMapper.java")));

        assertTrue(creditMapper.contains(
                "invocationpurpose = 'generation' and billingmode = 'billable'"),
                "用户结算只能消费生成任务的 BILLABLE 投影");
        assertTrue(creditMapper.contains(
                "usagesource = 'unavailable' or totaltokens is null or totaltokens <= 0"),
                "任何未知的可计费成本都必须保持 pending，禁止折算为 0");
    }

    @Test
    void staleRecoveryMustUseTaskFencesAndPreservePreflightCostFacts() throws Exception {
        String mapper = normalized(Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother",
                "mapper", "GenerationTraceMapper.java")));
        String recovery = mapper.substring(
                mapper.indexOf("update generation_model_call call_record"),
                mapper.indexOf("int recoverstalegenerationstartedmodelcalls"));

        assertTrue(recovery.contains("left join generation_task task"),
                "孤儿调用也必须能被恢复");
        assertTrue(recovery.contains("task.executionepoch > 0")
                        && recovery.contains("task.leaseuntil < #{observedat}"),
                "运行中任务只能根据过期的执行栅栏恢复");
        assertTrue(recovery.contains("call_record.callstatus = 'error'"),
                "恢复不能根据任务终态猜测 provider 成功");
        assertTrue(recovery.contains(
                        "when task.status = 'cancelled' then 'model_cancelled'")
                        && recovery.contains(
                        "when task.status = 'deadline_exceeded' then 'model_timeout'"),
                "恢复后的取消与超时成本必须保留稳定 outcome，供独立计费策略消费");
        assertTrue(!recovery.contains("totaltokens = null"),
                "恢复必须保留调用前持久化的保守成本事实");
    }

    private String normalized(String value) {
        return value.replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
