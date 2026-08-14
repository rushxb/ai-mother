package com.rush.rushaicodemother.architecture;

import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipeline;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 约束每条生产 pipeline 显式拥有自己的静态能力声明。 */
class GenerationPipelineCapabilityArchitectureTest {

    private static final String PIPELINE_PACKAGE =
            "com.rush.rushaicodemother.orchestration.pipeline";

    @Test
    void productionPipelinesMustDeclareCapabilityInsteadOfUsingCompatibilityDefault()
            throws Exception {
        List<String> missingDeclarations = ProductionClassScanner.load(PIPELINE_PACKAGE).stream()
                .filter(GenerationPipeline.class::isAssignableFrom)
                .filter(type -> !type.isInterface() && !Modifier.isAbstract(type.getModifiers()))
                .filter(type -> {
                    try {
                        return type.getDeclaredMethod("capability") == null;
                    } catch (NoSuchMethodException ignored) {
                        return true;
                    }
                })
                .map(Class::getName)
                .sorted()
                .toList();

        assertThat(missingDeclarations)
                .as("生产 pipeline 必须显式声明 capability，registry 才是可靠能力真相源")
                .isEmpty();
    }
}
