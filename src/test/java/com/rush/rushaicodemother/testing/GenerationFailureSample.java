package com.rush.rushaicodemother.testing;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 一条可由 JUnit 故障矩阵执行和追溯的确定性样本声明。 */
public record GenerationFailureSample(
        String id,
        GenerationFailureScenario scenario,
        String testClassName,
        String testMethodName,
        Set<String> durableIdentityFields,
        Set<String> assertedFacts
) {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_]{2,63}");
    private static final Pattern JAVA_NAME_PATTERN = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern JAVA_IDENTIFIER_PATTERN = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*");

    public GenerationFailureSample {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("故障样本标识无效");
        }
        Objects.requireNonNull(scenario, "故障样本类别不能为空");
        if (testClassName == null || !JAVA_NAME_PATTERN.matcher(testClassName).matches()
                || testMethodName == null
                || !JAVA_IDENTIFIER_PATTERN.matcher(testMethodName).matches()) {
            throw new IllegalArgumentException("故障样本测试绑定无效");
        }
        durableIdentityFields = durableIdentityFields == null
                ? Set.of()
                : Set.copyOf(durableIdentityFields);
        assertedFacts = assertedFacts == null ? Set.of() : Set.copyOf(assertedFacts);
        if (!durableIdentityFields.containsAll(Set.of("taskId", "executionEpoch"))) {
            throw new IllegalArgumentException("故障样本必须绑定任务与执行纪元");
        }
        if (assertedFacts.isEmpty() || assertedFacts.stream()
                .anyMatch(fact -> fact == null || !ID_PATTERN.matcher(fact).matches())) {
            throw new IllegalArgumentException("故障样本断言事实无效");
        }
    }
}
