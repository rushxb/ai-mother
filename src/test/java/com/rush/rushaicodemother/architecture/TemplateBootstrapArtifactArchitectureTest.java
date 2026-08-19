package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止模板初始化制品的字段解析重新散落到 DAG 节点。 */
class TemplateBootstrapArtifactArchitectureTest {

    private static final Path AGENT_SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother",
            "orchestration", "agent");

    @Test
    void templateProducerAndConsumerMustCrossTheTypedArtifactInterface() throws Exception {
        String producer = Files.readString(AGENT_SOURCE_ROOT.resolve("TemplateAgentNode.java"));
        String consumer = Files.readString(AGENT_SOURCE_ROOT.resolve("CodeAgentNode.java"));

        assertThat(producer)
                .contains("TemplateBootstrapArtifact", ".fromPayload(", ".toArtifact(")
                .doesNotContain("\"template_bootstrap\"");
        assertThat(consumer)
                .contains("TemplateBootstrapArtifact.KEY", "TemplateBootstrapArtifact.fromArtifact(")
                .doesNotContain("\"template_bootstrap\"");
    }
}
