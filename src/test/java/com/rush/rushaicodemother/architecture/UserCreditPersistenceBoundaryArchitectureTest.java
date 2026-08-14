package com.rush.rushaicodemother.architecture;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.core.BaseMapper;
import com.rush.rushaicodemother.mapper.UserCreditMapper;
import com.rush.rushaicodemother.model.entity.UserCreditTransaction;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.impl.UserCreditServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用户积分账务边界和显式 SQL 的架构门禁。 */
class UserCreditPersistenceBoundaryArchitectureTest {

    private static final Path JAVA_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother"
    );

    @Test
    void businessServiceMustNotLeakPersistenceOrPricingDetails() throws IOException {
        String serviceSource = read("service", "impl", "UserCreditServiceImpl.java");

        assertFalse(serviceSource.contains("com.rush.rushaicodemother.mapper"));
        assertFalse(serviceSource.contains("com.rush.rushaicodemother.model.entity"));
        assertFalse(serviceSource.contains("QueryWrapper"));
        assertFalse(Arrays.stream(UserCreditService.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("calculateCreditCost")));
        assertFalse(Arrays.stream(UserCreditService.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("TOKENS_PER_CREDIT")));
    }

    @Test
    void creditMapperMustUseExplicitSqlAndDatabaseAggregation() throws IOException {
        assertFalse(BaseMapper.class.isAssignableFrom(UserCreditMapper.class));
        String mapperSource = read("mapper", "UserCreditMapper.java");

        assertTrue(mapperSource.contains(
                "CASE WHEN callStatus = 'SUCCESS' AND totalTokens > 0"));
        assertTrue(mapperSource.contains("pendingCallCount"),
                "STARTED 或 usage 不可用的调用必须阻止按 0 结算");
        assertTrue(mapperSource.contains("FROM generation_model_call"));
        assertTrue(mapperSource.contains("isDelete = 0"));
        assertTrue(mapperSource.split("FOR UPDATE", -1).length - 1 >= 2,
                "积分账户和生成任务必须使用悲观锁串行化结算");
        assertTrue(mapperSource.contains("AND creditCharged = 0"),
                "生成任务结算更新必须使用未结算状态作为并发条件");
        assertFalse(mapperSource.contains("selectListByQuery"));
    }

    @Test
    void legacyCreditPersistenceMethodsAndMapperMustRemainRemoved() throws IOException {
        String userMapperSource = read("mapper", "UserMapper.java");
        String traceMapperSource = read("mapper", "GenerationTraceMapper.java");

        assertFalse(userMapperSource.contains("selectCreditAccountForUpdate"));
        assertFalse(userMapperSource.contains("updateCreditBalance"));
        assertFalse(traceMapperSource.contains("selectCreditAccountForUpdate"));
        assertFalse(traceMapperSource.contains("updateCreditSettlement"));
        assertFalse(Files.exists(JAVA_ROOT.resolve(Path.of("mapper", "GenerationTaskMapper.java"))));
        assertFalse(Files.exists(JAVA_ROOT.resolve(Path.of("mapper", "UserCreditTransactionMapper.java"))));
    }

    @Test
    void transactionPrimaryKeyMustMatchAutoIncrementSchema() throws NoSuchFieldException {
        Field idField = UserCreditTransaction.class.getDeclaredField("id");
        Id id = idField.getAnnotation(Id.class);

        assertNotNull(id);
        assertEquals(KeyType.Auto, id.keyType());
    }

    @Test
    void creditWriteUseCasesMustOwnOneDatabaseTransaction() throws NoSuchMethodException {
        assertTransactional("initializeCredit", Long.class, Long.class, Long.class);
        assertTransactional("adjustCreditByAdmin",
                com.rush.rushaicodemother.service.credit.AdminCreditAdjustmentCommand.class);
        assertTransactional("chargeGenerationTask", String.class);
        assertTransactional("reserveGenerationTask",
                com.rush.rushaicodemother.service.credit.GenerationCreditReservationCommand.class);
    }

    @Test
    void creditSchemaAndUpgradeMigrationMustEnforceLedgerInvariants() throws IOException {
        String schema = Files.readString(Path.of("sql", "create_table.sql"));
        Path migrationPath = Path.of(
                "sql", "migrations", "V20260714_3__user_credit_integrity.sql");

        assertTrue(Files.exists(migrationPath), "用户积分模块必须提供既有数据库升级迁移");
        String migration = Files.readString(migrationPath);

        assertTrue(schema.contains("chk_user_credit_balance_nonnegative"));
        assertTrue(schema.contains("chk_user_credit_transaction_balance_nonnegative"));
        assertTrue(schema.contains("chk_user_credit_transaction_shape"));
        assertTrue(schema.contains("bizId         varchar(128)                       not null"));
        assertTrue(schema.contains("UNIQUE KEY uk_type_bizId (type, bizId)"));
        assertTrue(schema.contains("GENERATION_RESERVATION"));
        assertTrue(schema.contains("GENERATION_SETTLEMENT"));

        String reservationMigration = Files.readString(Path.of(
                "sql", "migrations", "V20260718_1__generation_credit_reservation.sql"));
        assertTrue(reservationMigration.contains("DROP CHECK chk_user_credit_transaction_shape"));
        assertTrue(reservationMigration.contains("GENERATION_RESERVATION"));
        assertTrue(reservationMigration.contains("GENERATION_SETTLEMENT"));

        assertTrue(migration.contains("ADD COLUMN creditBalance"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS user_credit_transaction"));
        assertTrue(migration.contains("MODIFY COLUMN bizId varchar(128) not null"));
        assertTrue(migration.contains("uk_type_bizId"));
        assertTrue(migration.contains("chk_user_credit_balance_nonnegative"));
        assertTrue(migration.contains("chk_user_credit_transaction_shape"));
    }

    @Test
    void creditAvailabilityMustBeEnforcedAtomicallyByGenerationAdmission() throws IOException {
        assertFalse(Arrays.stream(UserService.class.getDeclaredMethods())
                .map(Method::getName)
                .anyMatch("ensureHasCredit"::equals));
        assertTrue(Arrays.stream(UserCreditService.class.getDeclaredMethods())
                .map(Method::getName)
                .anyMatch("ensureHasCredit"::equals));

        String appServiceSource = read("service", "impl", "AppServiceImpl.java");
        String admissionSource = read("orchestration", "runtime", "task",
                "GenerationTaskAdmissionService.java");
        assertFalse(appServiceSource.contains("userCreditService.ensureHasCredit"));
        assertTrue(admissionSource.contains("userCreditService.reserveGenerationTask"));
        assertTrue(admissionSource.indexOf("findByIdempotencyKey")
                < admissionSource.indexOf("reserveGenerationTask"));
        assertFalse(appServiceSource.contains("userService.ensureHasCredit"));
    }

    private void assertTransactional(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = UserCreditServiceImpl.class.getMethod(methodName, parameterTypes);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertNotNull(transactional, methodName + " 必须声明事务边界");
        assertTrue(Arrays.asList(transactional.rollbackFor()).contains(Exception.class),
                methodName + " 必须对受检异常回滚");
    }

    private String read(String... relativePath) throws IOException {
        return Files.readString(JAVA_ROOT.resolve(Path.of("", relativePath)));
    }
}
