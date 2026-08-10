package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护「单值注入点不得存在多个无条件候选 Bean」这一启动期契约。
 *
 * <p>Spring 只有在真正刷新被扫描的生产上下文时才会抛出
 * {@code NoUniqueBeanDefinitionException}，而整包启动测试被标记为 {@code external} 并在默认
 * 构建中排除，于是「同一 SPI 被注册了第二个实现」这类错误只会在生产启动时暴露。
 * 手写的 wiring 测试用 {@code ApplicationContextRunner} 逐个列举 Bean，天然看不到扫描到的
 * 新实现，因此也挡不住这类回归。</p>
 *
 * <p>本测试改为直接对生产类做静态推导：把「被单值注入的自有接口」与「无条件注册的实现数量」
 * 做交叉比对。它遵循开闭原则——新增 SPI 或新增实现都会自动纳入校验，无需维护任何白名单；
 * 通过 {@code @Conditional} 系列注解互斥的多实现（如 Redis/本地传输）与显式声明
 * {@code @Primary} 的场景都不算歧义。</p>
 */
class SpringSingleValuedInjectionAmbiguityArchitectureTest {

    private static final String BASE_PACKAGE = "com.rush.rushaicodemother";
    private static final List<String> INJECTION_FIELD_ANNOTATIONS = List.of(
            Autowired.class.getName(),
            "jakarta.annotation.Resource",
            "jakarta.inject.Inject",
            "javax.inject.Inject"
    );

    @Test
    void singleValuedInjectionPointsMustHaveExactlyOneUnconditionalCandidate() throws Exception {
        List<Class<?>> productionClasses = ProductionClassScanner.load(BASE_PACKAGE);
        Map<Class<?>, Set<String>> candidatesByContract = collectUnconditionalCandidates(productionClasses);
        Set<Class<?>> singleValuedContracts = collectSingleValuedInjectedContracts(productionClasses);

        Map<String, Set<String>> violations = new TreeMap<>();
        for (Class<?> contract : singleValuedContracts) {
            Set<String> candidates = candidatesByContract.getOrDefault(contract, Set.of());
            if (candidates.size() > 1) {
                violations.put(contract.getName(), candidates);
            }
        }

        assertTrue(violations.isEmpty(), () ->
                "以下接口被单值注入，却存在多个无条件候选 Bean，生产上下文会在启动时抛出 "
                        + "NoUniqueBeanDefinitionException。请补充 @Conditional 互斥条件、@Primary，"
                        + "或把注入点改为集合注入: " + violations);
    }

    /** 统计每个自有接口的「无条件注册」候选实现，@Primary 视为已消歧因而不计入。 */
    private Map<Class<?>, Set<String>> collectUnconditionalCandidates(List<Class<?>> productionClasses) {
        Map<Class<?>, Set<String>> candidatesByContract = new LinkedHashMap<>();
        for (Class<?> type : productionClasses) {
            if (isConcreteBeanClass(type) && !isDisambiguated(type)) {
                for (Class<?> contract : ownInterfacesOf(type)) {
                    candidatesByContract.computeIfAbsent(contract, key -> new LinkedHashSet<>())
                            .add(type.getName());
                }
            }
            collectFactoryMethodCandidates(type, candidatesByContract);
        }
        return candidatesByContract;
    }

    /** {@code @Bean} 工厂方法同样产生候选 Bean，遗漏它们会让本测试出现假阴性。 */
    private void collectFactoryMethodCandidates(Class<?> type,
                                                Map<Class<?>, Set<String>> candidatesByContract) {
        if (isDisambiguated(type)) {
            return;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!AnnotatedElementUtils.hasAnnotation(method, Bean.class) || isDisambiguated(method)) {
                continue;
            }
            Class<?> returnType = method.getReturnType();
            if (isOwnInterface(returnType)) {
                candidatesByContract.computeIfAbsent(returnType, key -> new LinkedHashSet<>())
                        .add(type.getName() + "#" + method.getName());
            }
        }
    }

    /** 收集所有「按单个 Bean 解析」的注入点契约：构造器参数、@Bean 工厂方法参数与字段注入。 */
    private Set<Class<?>> collectSingleValuedInjectedContracts(List<Class<?>> productionClasses) {
        Set<Class<?>> contracts = new LinkedHashSet<>();
        for (Class<?> type : productionClasses) {
            if (isConcreteBeanClass(type)) {
                Constructor<?> injectionConstructor = resolveInjectionConstructor(type);
                if (injectionConstructor != null) {
                    collectSingleValuedParameters(injectionConstructor, contracts);
                }
                collectSingleValuedFields(type, contracts);
            }
            for (Method method : type.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, Bean.class)) {
                    collectSingleValuedParameters(method, contracts);
                }
            }
        }
        return contracts;
    }

    private void collectSingleValuedParameters(Executable executable, Set<Class<?>> contracts) {
        for (var parameter : executable.getParameters()) {
            if (isSingleValuedInjection(parameter.getType(), parameter)) {
                contracts.add(parameter.getType());
            }
        }
    }

    private void collectSingleValuedFields(Class<?> type, Set<Class<?>> contracts) {
        for (Field field : type.getDeclaredFields()) {
            if (isInjectedField(field) && isSingleValuedInjection(field.getType(), field)) {
                contracts.add(field.getType());
            }
        }
    }

    /**
     * 只有「自有接口 + 未被 @Qualifier 指名」的注入点才会因多候选而启动失败；
     * 集合、Map、Optional、ObjectProvider 等类型不是原始接口类型，天然被排除。
     */
    private boolean isSingleValuedInjection(Class<?> injectedType, AnnotatedElement injectionPoint) {
        return isOwnInterface(injectedType)
                && !AnnotatedElementUtils.hasAnnotation(injectionPoint, Qualifier.class);
    }

    private Constructor<?> resolveInjectionConstructor(Class<?> type) {
        Constructor<?>[] constructors = Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> !constructor.isSynthetic())
                .toArray(Constructor<?>[]::new);
        if (constructors.length == 1) {
            return constructors[0];
        }
        return Arrays.stream(constructors)
                .filter(constructor -> AnnotatedElementUtils.hasAnnotation(constructor, Autowired.class))
                .findFirst()
                .orElse(null);
    }

    private boolean isInjectedField(Field field) {
        return Arrays.stream(field.getDeclaredAnnotations())
                .map(annotation -> annotation.annotationType().getName())
                .anyMatch(INJECTION_FIELD_ANNOTATIONS::contains);
    }

    private boolean isConcreteBeanClass(Class<?> type) {
        return !type.isInterface()
                && !type.isEnum()
                && !type.isAnnotation()
                && !Modifier.isAbstract(type.getModifiers())
                && AnnotatedElementUtils.hasAnnotation(type, Component.class);
    }

    /** @Primary 与 @Conditional 家族（含 @ConditionalOnProperty、@Profile）都视为已显式消歧。 */
    private boolean isDisambiguated(AnnotatedElement element) {
        return AnnotatedElementUtils.hasAnnotation(element, Primary.class)
                || AnnotatedElementUtils.hasAnnotation(element, Conditional.class);
    }

    private List<Class<?>> ownInterfacesOf(Class<?> type) {
        List<Class<?>> interfaces = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Class<?> candidate : current.getInterfaces()) {
                collectOwnInterfaceHierarchy(candidate, interfaces);
            }
        }
        return interfaces;
    }

    private void collectOwnInterfaceHierarchy(Class<?> candidate, List<Class<?>> interfaces) {
        if (isOwnInterface(candidate) && !interfaces.contains(candidate)) {
            interfaces.add(candidate);
        }
        for (Class<?> parent : candidate.getInterfaces()) {
            collectOwnInterfaceHierarchy(parent, interfaces);
        }
    }

    private boolean isOwnInterface(Class<?> type) {
        return type.isInterface() && type.getName().startsWith(BASE_PACKAGE + ".");
    }
}
