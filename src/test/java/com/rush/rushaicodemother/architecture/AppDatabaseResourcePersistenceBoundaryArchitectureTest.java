package com.rush.rushaicodemother.architecture;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.core.service.IService;
import com.rush.rushaicodemother.model.entity.AppDatabaseResource;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import com.rush.rushaicodemother.service.impl.AppDatabaseResourceServiceImpl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 应用 Database 资源持久化和实体边界门禁。 */
class AppDatabaseResourcePersistenceBoundaryArchitectureTest {

    private static final Path APP_APPLICATION_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "application", "app"
    );
    private static final Path CONTROLLER_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "controller"
    );

    @Test
    void businessServiceMustNotExposeGenericCrudOrPersistenceEntities() {
        assertFalse(IService.class.isAssignableFrom(AppDatabaseResourceService.class));
        assertEquals(Object.class, AppDatabaseResourceServiceImpl.class.getSuperclass());
        for (Method method : AppDatabaseResourceService.class.getDeclaredMethods()) {
            assertFalse(
                    method.getReturnType().equals(AppDatabaseResource.class),
                    () -> method.getName() + " must not return persistence entity"
            );
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertFalse(
                        parameterType.equals(AppDatabaseResource.class),
                        () -> method.getName() + " must not accept persistence entity"
                );
            }
        }
    }

    @Test
    void databaseResourcePrimaryKeyMustMatchAutoIncrementSchema() throws NoSuchFieldException {
        Field idField = AppDatabaseResource.class.getDeclaredField("id");
        Id id = idField.getAnnotation(Id.class);

        assertNotNull(id);
        assertEquals(KeyType.Auto, id.keyType());
    }

    @Test
    void applicationAndControllerLayersMustNotAccessResourcePersistenceDetails() throws IOException {
        List<String> violations = new ArrayList<>();
        violations.addAll(scanJavaSources(APP_APPLICATION_ROOT));
        violations.addAll(scanJavaSources(CONTROLLER_ROOT));
        violations.sort(Comparator.naturalOrder());

        assertTrue(violations.isEmpty(), () ->
                "Application and controller layers must use Database resource views only:\n - "
                        + String.join("\n - ", violations));
    }

    @Test
    void persistenceBoundaryMustUseExplicitAtomicUpsertAndActiveQueries() throws IOException {
        String persistenceSource = Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother",
                "service", "database", "DefaultAppDatabaseResourcePersistenceService.java"
        ));
        String mapperXml = Files.readString(Path.of(
                "src", "main", "resources", "mapper", "AppDatabaseResourceMapper.xml"
        ));

        assertFalse(persistenceSource.contains(".insert("));
        assertFalse(persistenceSource.contains(".updateById("));
        assertFalse(persistenceSource.contains("QueryWrapper"));
        assertTrue(persistenceSource.contains("upsertActiveResource("));
        assertTrue(persistenceSource.contains("selectActiveByAppId("));
        assertTrue(mapperXml.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(mapperXml.contains("AS incoming"));
        assertFalse(mapperXml.contains("VALUES("));
        assertTrue(mapperXml.contains("status = 'active'"));
        assertTrue(mapperXml.contains("isDelete = 0"));
    }

    @Test
    void businessImplementationMustNotReintroduceGenericOrmWrites() throws IOException {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother",
                "service", "impl", "AppDatabaseResourceServiceImpl.java"
        ));

        assertFalse(source.contains("save("));
        assertFalse(source.contains("updateById("));
        assertFalse(source.contains("list(QueryWrapper"));
        assertFalse(source.contains("AppDatabaseResourceMapper"));
    }

    private List<String> scanJavaSources(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path sourceFile : paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                String source = Files.readString(sourceFile);
                if (source.contains("model.entity.AppDatabaseResource")
                        || source.contains("mapper.AppDatabaseResourceMapper")
                        || source.contains("service.database.AppDatabaseResourcePersistenceService")) {
                    violations.add(sourceFile.toString());
                }
            }
        }
        return violations;
    }
}
