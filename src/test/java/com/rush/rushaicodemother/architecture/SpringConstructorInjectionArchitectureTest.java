package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 防止测试专用构造器破坏 Spring 生产组件实例化。 */
class SpringConstructorInjectionArchitectureTest {

    private static final String BASE_PACKAGE = "com.rush.rushaicodemother";
    private static final List<String> INJECTION_ANNOTATIONS = List.of(
            Autowired.class.getName(),
            "jakarta.inject.Inject",
            "javax.inject.Inject"
    );

    @Test
    void componentsWithMultipleConstructorsMustSelectTheProductionConstructor() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<String> violations = new ArrayList<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        for (var candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String className = candidate.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> componentType = Class.forName(className, false, classLoader);
            if (componentType.isInterface() || Modifier.isAbstract(componentType.getModifiers())) {
                continue;
            }
            Constructor<?>[] constructors = Arrays.stream(componentType.getDeclaredConstructors())
                    .filter(constructor -> !constructor.isSynthetic())
                    .toArray(Constructor<?>[]::new);
            if (constructors.length <= 1 || hasExplicitInjectionConstructor(constructors)
                    || hasPublicNoArgumentConstructor(constructors)) {
                continue;
            }
            violations.add(componentType.getName());
        }

        assertTrue(violations.isEmpty(), () ->
                "以下 Spring 组件声明了多个构造器，却未显式选择生产构造器: " + violations);
    }

    private boolean hasExplicitInjectionConstructor(Constructor<?>[] constructors) {
        return Arrays.stream(constructors).anyMatch(constructor ->
                Arrays.stream(constructor.getDeclaredAnnotations())
                        .map(Annotation::annotationType)
                        .map(Class::getName)
                        .anyMatch(INJECTION_ANNOTATIONS::contains));
    }

    private boolean hasPublicNoArgumentConstructor(Constructor<?>[] constructors) {
        return Arrays.stream(constructors).anyMatch(constructor ->
                constructor.getParameterCount() == 0
                        && Modifier.isPublic(constructor.getModifiers()));
    }
}
