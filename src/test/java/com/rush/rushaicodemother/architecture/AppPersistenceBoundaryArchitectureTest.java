package com.rush.rushaicodemother.architecture;

import com.mybatisflex.core.service.IService;
import com.rush.rushaicodemother.service.AppService;
import com.rush.rushaicodemother.service.impl.AppServiceImpl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 应用模块持久化边界门禁。 */
class AppPersistenceBoundaryArchitectureTest {

    private static final Path APP_APPLICATION_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "application", "app"
    );
    private static final Path CONTROLLER_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "controller"
    );
    private static final Pattern GENERIC_APP_CRUD_CALL = Pattern.compile(
            "appService\\s*\\.\\s*(getById|updateById|page|getQueryWrapper|removeById)\\s*\\("
    );

    @Test
    void appBusinessServiceMustNotExposeGenericCrudContract() {
        assertFalse(IService.class.isAssignableFrom(AppService.class));
        assertEquals(Object.class, AppServiceImpl.class.getSuperclass());
    }

    @Test
    void applicationLayerMustNotUseGenericAppCrudOrOrmQueryObjects() throws IOException {
        List<String> violations = scanJavaSources(APP_APPLICATION_ROOT, source ->
                source.contains("QueryWrapper")
                        || source.contains("com.rush.rushaicodemother.mapper.AppMapper")
                        || GENERIC_APP_CRUD_CALL.matcher(source).find()
        );

        assertTrue(violations.isEmpty(), () ->
                "App application services must use scenario-specific persistence operations:\n - "
                        + String.join("\n - ", violations));
    }

    @Test
    void controllersMustNotDependOnAppPersistenceImplementation() throws IOException {
        List<String> violations = scanJavaSources(CONTROLLER_ROOT, source ->
                source.contains("service.app.AppPersistenceService")
                        || source.contains("mapper.AppMapper")
        );

        assertTrue(violations.isEmpty(), () ->
                "Controllers must delegate App use cases instead of accessing persistence:\n - "
                        + String.join("\n - ", violations));
    }

    @Test
    void persistenceBoundaryMustUseExplicitActiveRowWrites() throws IOException {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother",
                "service", "app", "DefaultAppPersistenceService.java"
        ));

        assertFalse(source.contains("appMapper.update("));
        assertFalse(source.contains("selectOneById("));
        assertTrue(source.contains("updateActiveName("));
        assertTrue(source.contains("updateActiveAdministrationFields("));
        assertTrue(source.contains("updateActiveDevServerPort("));
    }

    private List<String> scanJavaSources(Path root, SourceViolation predicate) throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path sourceFile : paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                String source = Files.readString(sourceFile);
                if (predicate.test(source)) {
                    violations.add(sourceFile.toString());
                }
            }
        }
        violations.sort(Comparator.naturalOrder());
        return violations;
    }

    @FunctionalInterface
    private interface SourceViolation {
        boolean test(String source);
    }
}
