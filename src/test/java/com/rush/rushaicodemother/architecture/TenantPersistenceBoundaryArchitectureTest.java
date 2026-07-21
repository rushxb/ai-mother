package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantPersistenceBoundaryArchitectureTest {

    private static final Path SCHEMA = Path.of("sql", "create_table.sql");
    private static final Path MIGRATION = Path.of(
            "sql", "migrations", "V20260718_4__tenant_foundation.sql");

    @Test
    void schemaMustDeclareTenantMembershipAndApplicationIsolationBoundary() throws IOException {
        String schema = normalize(Files.readString(SCHEMA));

        assertTrue(schema.contains("create table if not exists tenant"));
        assertTrue(schema.contains("create table if not exists tenant_membership"));
        assertTrue(schema.contains("unique key uk_tenant_key (tenantkey)"));
        assertTrue(schema.contains("unique key uk_tenant_membership_identity (tenantid, userid)"));
        assertTrue(schema.contains("tenantid bigint not null comment '租户授权边界'"));
        assertTrue(schema.contains("index idx_app_tenant_cursor (tenantid, isdelete, createtime, id)"));
        assertTrue(schema.contains("constraint fk_app_tenant foreign key (tenantid) references tenant (id)"));
    }

    @Test
    void migrationMustBackfillBeforeEnforcingNotNullIndexAndForeignKey() throws IOException {
        String migration = normalize(Files.readString(MIGRATION));

        assertTrue(migration.contains("from `user` u"));
        assertTrue(migration.contains("concat('personal:', u.id)"));
        assertTrue(migration.contains("role = 'owner'"));
        assertTrue(migration.contains("information_schema.columns"));
        assertTrue(migration.contains("information_schema.statistics"));
        assertTrue(migration.contains("information_schema.table_constraints"));
        assertTrue(migration.contains("unique key uk_tenant_membership_identity (tenantid, userid)"));
        assertTrue(migration.contains("constraint fk_tenant_membership_tenant"));
        assertTrue(migration.contains("constraint fk_tenant_membership_user"));

        int addNullable = migration.indexOf("add column tenantid bigint null");
        int backfill = migration.indexOf("set a.tenantid = t.id");
        int enforceNotNull = migration.indexOf("modify column tenantid bigint not null");
        int addIndex = migration.indexOf("add index idx_app_tenant_cursor");
        int addForeignKey = migration.indexOf("add constraint fk_app_tenant");
        assertTrue(addNullable >= 0 && addNullable < backfill);
        assertTrue(backfill < enforceNotNull);
        assertTrue(enforceNotNull < addIndex);
        assertTrue(addIndex < addForeignKey);
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
