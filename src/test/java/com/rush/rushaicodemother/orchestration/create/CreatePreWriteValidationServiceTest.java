package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.codegraph.StructuredSyntaxValidationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreatePreWriteValidationServiceTest {

    private final CreatePreWriteValidationService service = new CreatePreWriteValidationService(new StructuredSyntaxValidationService());

    @Test
    void shouldAcceptValidVueAndGoStructuredPatches() {
        CreatePreWriteValidationService.ValidationResult result = service.validate(List.of(
                PatchOperation.modify("src/views/DashboardView.vue", """
                        <template><main>商品管理</main></template>
                        <script setup lang="ts">
                        import { metrics } from '@/data/adminData'
                        </script>
                        """),
                PatchOperation.goAddImport("cmd/server/main.go", "backend-template/internal/modules/product")
        ));

        assertTrue(result.valid());
    }

    @Test
    void shouldRejectInvalidBackendStructuredPatches() {
        CreatePreWriteValidationService.ValidationResult result = service.validate(List.of(
                PatchOperation.goAddImport("cmd/server/main.go", "\"backend-template/internal/modules/product\""),
                PatchOperation.add("internal/modules/product/model.go", """
                        package sample

                        type Product struct {
                            ID int64 `json:"id"`
                        }
                        """),
                PatchOperation.modify("sql/schema.sql", "create index if not exists idx_products_name on products (name);")
        ));

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("go_import_path_invalid")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("go_package_module_mismatch")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("schema_create_table_missing")));
    }

    @Test
    void shouldRejectInvalidJsonSecretAndDangerousSql() {
        CreatePreWriteValidationService.ValidationResult result = service.validate(List.of(
                PatchOperation.modify("package.json", "{\"scripts\": {\"postinstall\": \"rm -rf /\"}"),
                PatchOperation.modify("src/config.ts", "export const apiKey = \"1234567890abcdef123\""),
                PatchOperation.appendSqlMigration("sql/schema.sql", "drop table users;")
        ));

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("invalid_json")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("secret_or_private_address_detected")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("dangerous_sql")));
    }
}
