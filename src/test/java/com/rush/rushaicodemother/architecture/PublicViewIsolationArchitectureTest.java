package com.rush.rushaicodemother.architecture;

import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.controller.UserController;
import com.rush.rushaicodemother.model.vo.OwnerAppVO;
import com.rush.rushaicodemother.model.vo.PublicAppVO;
import com.rush.rushaicodemother.model.vo.PublicUserSummaryVO;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 公开、租户详情与管理员视图不得再次退化为一个通用 VO。 */
class PublicViewIsolationArchitectureTest {

    @Test
    void publicUserEndpointMustRequireLoginAndReturnOnlyPublicSummary() throws Exception {
        var method = UserController.class.getMethod("getUserVOById", long.class);

        assertNotNull(method.getAnnotation(AuthCheck.class));
        assertEquals(PublicUserSummaryVO.class,
                method.getGenericReturnType() instanceof java.lang.reflect.ParameterizedType response
                        ? response.getActualTypeArguments()[0] : null);
        assertEquals(Set.of("id", "userName", "userAvatar"), fieldsOf(PublicUserSummaryVO.class));
    }

    @Test
    void publicApplicationViewMustExcludeEverySensitiveDetailField() {
        assertEquals(Set.of(
                        "id", "appName", "cover", "codeGenType", "deployKey",
                        "deployedTime", "userId", "createTime", "user"),
                fieldsOf(PublicAppVO.class));
        assertEquals(PublicUserSummaryVO.class,
                Arrays.stream(OwnerAppVO.class.getDeclaredFields())
                        .filter(field -> field.getName().equals("user"))
                        .findFirst()
                        .orElseThrow()
                        .getType());
    }

    @Test
    void ambiguousLegacyViewTypesMustNotRemainAvailableForReuse() {
        Path viewRoot = Path.of("src", "main", "java", "com", "rush", "rushaicodemother",
                "model", "vo");

        assertFalse(Files.exists(viewRoot.resolve("AppVO.java")));
        assertFalse(Files.exists(viewRoot.resolve("UserVO.java")));
        assertTrue(Files.exists(viewRoot.resolve("OwnerAppVO.java")));
        assertTrue(Files.exists(viewRoot.resolve("AdminUserVO.java")));
    }

    private Set<String> fieldsOf(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toUnmodifiableSet());
    }
}
