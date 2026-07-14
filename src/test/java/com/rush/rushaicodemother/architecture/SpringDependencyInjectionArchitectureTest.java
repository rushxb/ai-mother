package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 依赖注入架构门禁。
 *
 * <p>生产 Bean 统一使用构造器注入；禁止字段/Setter 注入，也禁止使用 {@code @Lazy}
 * 掩盖依赖环或使用动态 Bean 查找。该测试直接扫描主代码编译目录，不依赖测试源码的包结构。</p>
 */
class SpringDependencyInjectionArchitectureTest {

    private static final String BASE_PACKAGE = "com.rush.rushaicodemother";
    private static final Set<String> FORBIDDEN_INJECTION_ANNOTATIONS = Set.of(
            "jakarta.annotation.Resource",
            "jakarta.inject.Inject",
            "org.springframework.beans.factory.annotation.Autowired"
    );
    private static final String LAZY_ANNOTATION =
            "org.springframework.context.annotation.Lazy";
    private static final String APPLICATION_CONTEXT_AWARE =
            "org.springframework.context.ApplicationContextAware";
    private static final String APPLICATION_CONTEXT =
            "org.springframework.context.ApplicationContext";
    private static final String OBJECT_PROVIDER =
            "org.springframework.beans.factory.ObjectProvider";
    private static final String SELENIUM_WEB_DRIVER =
            "org.openqa.selenium.WebDriver";

    @Test
    void productionClassesMustUseConstructorInjection() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : ProductionClassScanner.load(BASE_PACKAGE)) {
            Stream.of(type.getDeclaredFields())
                    .filter(field -> hasAnyAnnotation(field, FORBIDDEN_INJECTION_ANNOTATIONS))
                    .map(field -> type.getName() + "#" + field.getName() + " uses field injection")
                    .forEach(violations::add);
            Stream.of(type.getDeclaredMethods())
                    .filter(method -> hasAnyAnnotation(method, FORBIDDEN_INJECTION_ANNOTATIONS))
                    .map(method -> type.getName() + "#" + method.getName() + " uses method injection")
                    .forEach(violations::add);
        }

        assertNoViolations("Production dependencies must be constructor-injected", violations);
    }

    @Test
    void productionClassesMustNotUseLazyToHideDependencyCycles() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : ProductionClassScanner.load(BASE_PACKAGE)) {
            collectLazyViolation(type, type.getName(), violations);
            Stream.of(type.getDeclaredFields())
                    .forEach(field -> collectLazyViolation(
                            field,
                            type.getName() + "#" + field.getName(),
                            violations
                    ));
            Stream.of(type.getDeclaredConstructors()).forEach(constructor -> {
                String location = type.getName() + "#<init>";
                collectLazyViolation(constructor, location, violations);
                collectParameterLazyViolations(constructor.getParameterAnnotations(), location, violations);
            });
            Stream.of(type.getDeclaredMethods()).forEach(method -> {
                String location = methodLocation(type, method);
                collectLazyViolation(method, location, violations);
                collectParameterLazyViolations(method.getParameterAnnotations(), location, violations);
            });
        }

        assertNoViolations("@Lazy must not be used to bypass dependency design", violations);
    }

    @Test
    void productionClassesMustNotExposeSpringContextServiceLocators() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : ProductionClassScanner.load(BASE_PACKAGE)) {
            if (implementsInterface(type, APPLICATION_CONTEXT_AWARE)) {
                violations.add(type.getName() + " implements ApplicationContextAware");
            }
            Stream.of(type.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> APPLICATION_CONTEXT.equals(field.getType().getName()))
                    .map(field -> type.getName() + "#" + field.getName()
                            + " stores ApplicationContext in static state")
                    .forEach(violations::add);
        }

        assertNoViolations(
                "Production code must declare dependencies explicitly instead of using Spring context service locators",
                violations
        );
    }

    @Test
    void productionClassesMustNotUseObjectProviderForDynamicDependencyLookup() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : ProductionClassScanner.load(BASE_PACKAGE)) {
            Stream.of(type.getDeclaredFields())
                    .filter(field -> OBJECT_PROVIDER.equals(field.getType().getName()))
                    .map(field -> type.getName() + "#" + field.getName() + " uses ObjectProvider")
                    .forEach(violations::add);
            Stream.of(type.getDeclaredConstructors()).forEach(constructor ->
                    collectForbiddenParameterTypes(
                            constructor.getParameterTypes(),
                            type.getName() + "#<init>",
                            violations
                    ));
            Stream.of(type.getDeclaredMethods()).forEach(method ->
                    collectForbiddenParameterTypes(
                            method.getParameterTypes(),
                            methodLocation(type, method),
                            violations
                    ));
        }

        assertNoViolations(
                "Production dependencies must be explicit instead of resolved through ObjectProvider",
                violations
        );
    }

    @Test
    void productionClassesMustNotShareWebDriverThroughStaticState() throws Exception {
        Class<?> webDriverType = Class.forName(SELENIUM_WEB_DRIVER);
        List<String> violations = new ArrayList<>();
        for (Class<?> type : ProductionClassScanner.load(BASE_PACKAGE)) {
            Stream.of(type.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> webDriverType.isAssignableFrom(field.getType()))
                    .map(field -> type.getName() + "#" + field.getName() + " stores static WebDriver state")
                    .forEach(violations::add);
        }

        assertNoViolations("WebDriver sessions must be request-scoped and closed after use", violations);
    }

    private boolean hasAnyAnnotation(AnnotatedElement element, Set<String> annotationNames) {
        return Stream.of(element.getDeclaredAnnotations())
                .map(annotation -> annotation.annotationType().getName())
                .anyMatch(annotationNames::contains);
    }

    private boolean implementsInterface(Class<?> type, String interfaceName) {
        boolean directlyImplemented = Stream.of(type.getInterfaces())
                .anyMatch(implementedType -> interfaceName.equals(implementedType.getName())
                        || implementsInterface(implementedType, interfaceName));
        if (directlyImplemented) {
            return true;
        }
        Class<?> superclass = type.getSuperclass();
        return superclass != null && implementsInterface(superclass, interfaceName);
    }

    private void collectLazyViolation(AnnotatedElement element,
                                      String location,
                                      List<String> violations) {
        if (hasAnyAnnotation(element, Set.of(LAZY_ANNOTATION))) {
            violations.add(location + " uses @Lazy");
        }
    }

    private void collectParameterLazyViolations(Annotation[][] parameterAnnotations,
                                                String location,
                                                List<String> violations) {
        for (int parameterIndex = 0; parameterIndex < parameterAnnotations.length; parameterIndex++) {
            for (Annotation annotation : parameterAnnotations[parameterIndex]) {
                if (LAZY_ANNOTATION.equals(annotation.annotationType().getName())) {
                    violations.add(location + " parameter[" + parameterIndex + "] uses @Lazy");
                }
            }
        }
    }

    private void collectForbiddenParameterTypes(Class<?>[] parameterTypes,
                                                String location,
                                                List<String> violations) {
        for (int parameterIndex = 0; parameterIndex < parameterTypes.length; parameterIndex++) {
            if (OBJECT_PROVIDER.equals(parameterTypes[parameterIndex].getName())) {
                violations.add(location + " parameter[" + parameterIndex + "] uses ObjectProvider");
            }
        }
    }

    private String methodLocation(Class<?> type, Method method) {
        return type.getName() + "#" + method.getName();
    }

    private void assertNoViolations(String rule, List<String> violations) {
        violations.sort(Comparator.naturalOrder());
        assertTrue(violations.isEmpty(), () -> rule + ":\n - " + String.join("\n - ", violations));
    }
}
