package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 防止模板类型选择与工作区解析重新泄漏到 DAG 或 CREATE 调用方。 */
class GenerationTemplateBootstrapOwnershipArchitectureTest {

    private static final List<String> FORBIDDEN_IMPLEMENTATION_TYPES = List.of(
            "VueProjectTemplateBootstrapService",
            "BackendProjectTemplateBootstrapService",
            "FullStackPortAllocator",
            "GenerationTemplateBootstrapAdapter"
    );

    @Test
    void templateCallersMustDependOnlyOnTheSharedRegistry() throws IOException {
        assertRegistryOnly(source(
                "orchestration", "agent", "TemplateAgentNode.java"));
        assertRegistryOnly(source(
                "orchestration", "create", "CreateTemplateRuntime.java"));
        assertRegistryOnly(source(
                "orchestration", "benchmark", "GenerationBenchmarkFixtureService.java"));
    }

    private void assertRegistryOnly(String source) {
        assertTrue(
                source.contains("GenerationTemplateBootstrapRegistry"),
                "模板调用方必须复用共享 registry"
        );
        FORBIDDEN_IMPLEMENTATION_TYPES.forEach(type -> assertFalse(
                source.contains(type),
                () -> "模板调用方不得直接依赖具体实现: " + type
        ));
    }

    private String source(String... relativePath) throws IOException {
        Path path = Path.of("src", "main", "java", "com", "rush", "rushaicodemother");
        for (String segment : relativePath) {
            path = path.resolve(segment);
        }
        return Files.readString(path);
    }
}
