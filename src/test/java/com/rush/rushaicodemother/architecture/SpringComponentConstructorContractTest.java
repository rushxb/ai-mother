package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringComponentConstructorContractTest {

    @Test
    void multiConstructorComponentsMustDeclareTheInjectionConstructor() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class, true, true));
        List<String> violations = new ArrayList<>();

        for (var candidate : scanner.findCandidateComponents("com.rush.rushaicodemother")) {
            Class<?> type = Class.forName(
                    candidate.getBeanClassName(), false,
                    SpringComponentConstructorContractTest.class.getClassLoader());
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length <= 1
                    || Arrays.stream(constructors).anyMatch(constructor -> constructor.getParameterCount() == 0)
                    || Arrays.stream(constructors).anyMatch(
                            constructor -> constructor.isAnnotationPresent(Autowired.class))) {
                continue;
            }
            violations.add(type.getName());
        }

        assertTrue(
                violations.isEmpty(),
                "多构造器 Spring 组件必须显式标记注入构造器: " + String.join(", ", violations)
        );
    }
}
