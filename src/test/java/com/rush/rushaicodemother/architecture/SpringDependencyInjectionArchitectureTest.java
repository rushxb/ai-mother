package com.rush.rushaicodemother.architecture;

import com.rush.rushaicodemother.RushAiCodeMotherApplication;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 掩盖依赖环。该测试直接扫描主代码编译目录，不依赖测试源码的包结构。</p>
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

    @Test
    void productionClassesMustUseConstructorInjection() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : loadProductionClasses()) {
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
        for (Class<?> type : loadProductionClasses()) {
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

    private List<Class<?>> loadProductionClasses()
            throws IOException, URISyntaxException, ClassNotFoundException {
        Path classesRoot = Path.of(RushAiCodeMotherApplication.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path packageRoot = classesRoot.resolve(BASE_PACKAGE.replace('.', '/'));
        if (!Files.isDirectory(packageRoot)) {
            throw new IllegalStateException("Production classes directory not found: " + packageRoot);
        }

        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(packageRoot)) {
            List<String> classNames = paths
                    .filter(Files::isRegularFile)
                    .map(classesRoot::relativize)
                    .map(Path::toString)
                    .filter(path -> path.endsWith(".class"))
                    .filter(path -> !path.endsWith("module-info.class"))
                    .map(this::toClassName)
                    .sorted()
                    .toList();
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            for (String className : classNames) {
                classes.add(Class.forName(className, false, classLoader));
            }
        }
        return classes;
    }

    private String toClassName(String relativeClassFile) {
        return relativeClassFile
                .substring(0, relativeClassFile.length() - ".class".length())
                .replace('\\', '.')
                .replace('/', '.');
    }

    private boolean hasAnyAnnotation(AnnotatedElement element, Set<String> annotationNames) {
        return Stream.of(element.getDeclaredAnnotations())
                .map(annotation -> annotation.annotationType().getName())
                .anyMatch(annotationNames::contains);
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

    private String methodLocation(Class<?> type, Method method) {
        return type.getName() + "#" + method.getName();
    }

    private void assertNoViolations(String rule, List<String> violations) {
        violations.sort(Comparator.naturalOrder());
        assertTrue(violations.isEmpty(), () -> rule + ":\n - " + String.join("\n - ", violations));
    }
}
