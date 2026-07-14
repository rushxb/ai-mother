package com.rush.rushaicodemother.architecture;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.core.service.IService;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.impl.UserServiceImpl;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用户模块持久化边界门禁。 */
class UserPersistenceBoundaryArchitectureTest {

    private static final Path APPLICATION_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "application"
    );
    private static final Path CONTROLLER_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "controller"
    );
    private static final Pattern GENERIC_USER_CRUD_CALL = Pattern.compile(
            "userService\\s*\\.\\s*(getById|getOne|save|updateById|removeById|listByIds|page|getQueryWrapper)\\s*\\("
    );

    @Test
    void userBusinessServiceMustNotExposeGenericCrudContract() {
        assertFalse(IService.class.isAssignableFrom(UserService.class));
        assertEquals(Object.class, UserServiceImpl.class.getSuperclass());
    }

    @Test
    void userEntityIdStrategyMustMatchAutoIncrementSchema() throws NoSuchFieldException {
        Id idMapping = User.class.getDeclaredField("id").getAnnotation(Id.class);

        assertNotNull(idMapping);
        assertEquals(KeyType.Auto, idMapping.keyType());
    }

    @Test
    void applicationLayerMustNotUseGenericUserCrudOrUserMapper() throws IOException {
        List<String> violations = scanJavaSources(APPLICATION_ROOT, source ->
                source.contains("com.rush.rushaicodemother.mapper.UserMapper")
                        || GENERIC_USER_CRUD_CALL.matcher(source).find()
        );

        assertTrue(violations.isEmpty(), () ->
                "Application services must use the User directory or business service:\n - "
                        + String.join("\n - ", violations));
    }

    @Test
    void controllersMustNotAccessUserPersistenceOrEntityCrud() throws IOException {
        List<String> violations = scanJavaSources(CONTROLLER_ROOT, source ->
                source.contains("service.user.UserPersistenceService")
                        || source.contains("mapper.UserMapper")
                        || GENERIC_USER_CRUD_CALL.matcher(source).find()
        );

        assertTrue(violations.isEmpty(), () ->
                "Controllers must delegate User persistence and queries:\n - "
                        + String.join("\n - ", violations));
    }

    @Test
    void persistenceBoundaryMustUseExplicitActiveRowWrites() throws IOException {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother",
                "service", "user", "DefaultUserPersistenceService.java"
        ));

        assertFalse(source.contains("userMapper.insert("));
        assertFalse(source.contains("userMapper.update("));
        assertFalse(source.contains("selectOneById("));
        assertTrue(source.contains("insertUser("));
        assertTrue(source.contains("updateActiveAdministrationFields("));
        assertTrue(source.contains("updateActivePasswordHash("));
        assertTrue(source.contains("logicallyDeleteActiveUser("));
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
