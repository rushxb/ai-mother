package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelPersistenceBoundaryArchitectureTest {

    private static final Path JAVA_ROOT = Path.of(
            "src/main/java/com/rush/rushaicodemother"
    );

    @Test
    void mapperAndEntityMustUseExplicitDatabaseOwnedPersistenceContract() throws IOException {
        String mapper = source("mapper/AiModelMapper.java");
        String entity = source("model/entity/AiModel.java");

        assertFalse(mapper.contains("BaseMapper<AiModel>"));
        assertTrue(mapper.contains("FOR UPDATE"));
        assertTrue(mapper.contains("isDelete = 0"));
        assertTrue(mapper.contains("isDelete = 1"));
        assertTrue(entity.contains("@Id(keyType = KeyType.Auto)"));
    }

    @Test
    void oldServicesAndEntityLeakingConverterMustNotExist() {
        List<String> removedFiles = List.of(
                "service/AiModelService.java",
                "service/AiModelCatalogService.java",
                "service/impl/AiModelServiceImpl.java",
                "service/impl/AiModelCatalogServiceImpl.java",
                "model/converter/AiModelViewConverter.java"
        );

        removedFiles.forEach(path -> assertFalse(Files.exists(JAVA_ROOT.resolve(path)), path));
    }

    @Test
    void webLayerMustDependOnlyOnSafeManagementBoundary() throws IOException {
        String controller = source("controller/AiModelController.java");

        assertTrue(controller.contains("AiModelManagementService"));
        assertFalse(controller.contains("model.entity.AiModel"));
        assertFalse(controller.contains("AiModelRuntimeService"));
        assertFalse(controller.contains("AiModelRuntimeConfiguration"));
    }

    @Test
    void onlyPersistenceAdapterMayImportAiModelMapperFromServiceLayer() throws IOException {
        Path serviceRoot = JAVA_ROOT.resolve("service");
        try (var files = Files.walk(serviceRoot)) {
            List<Path> offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith("DefaultAiModelPersistenceService.java"))
                    .filter(path -> readUnchecked(path).contains("mapper.AiModelMapper"))
                    .toList();
            assertTrue(offenders.isEmpty(), "Mapper boundary violations: " + offenders);
        }
    }

    @Test
    void schemaMustSupportSoftDeleteRecreationWithoutDualIdentityConstraint() throws IOException {
        String schema = Files.readString(Path.of("sql/create_table.sql"));
        String migration = Files.readString(Path.of(
                "sql/migrations/V20260714_2__ai_model_soft_delete_identity.sql"
        ));

        assertTrue(schema.contains("uk_active_provider_model"));
        assertFalse(schema.contains("UNIQUE KEY uk_provider_modelId"));
        assertTrue(migration.contains("DROP INDEX uk_provider_modelId"));
        assertTrue(migration.contains("case when isDelete = 0 then provider else null end"));
    }

    private String source(String relativePath) throws IOException {
        return Files.readString(JAVA_ROOT.resolve(relativePath));
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("读取源码失败: " + path, exception);
        }
    }
}
