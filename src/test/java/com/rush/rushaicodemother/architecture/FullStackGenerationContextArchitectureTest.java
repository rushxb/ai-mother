package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止全栈连接事实重新退化为 Template 与 Code 节点各自解释的动态 Map。 */
class FullStackGenerationContextArchitectureTest {

    private static final Path AGENT_SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother",
            "orchestration", "agent");

    @Test
    void fullStackProducerAndConsumerMustCrossTheTypedArtifactBoundary() throws Exception {
        String producer = Files.readString(AGENT_SOURCE_ROOT.resolve("TemplateAgentNode.java"));
        String consumer = Files.readString(AGENT_SOURCE_ROOT.resolve("CodeAgentNode.java"));

        assertThat(producer)
                .contains("FullStackGenerationContext", ".fromPayload(", ".toArtifact()")
                .doesNotContain("\"full_stack_context\"");
        assertThat(consumer)
                .contains("FullStackGenerationContext.KEY", "FullStackGenerationContext.fromArtifact(")
                .doesNotContain("artifactStringValue(context, \"full_stack_context\"");
    }
}
