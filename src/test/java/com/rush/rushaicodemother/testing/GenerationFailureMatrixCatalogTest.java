package com.rush.rushaicodemother.testing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(GenerationFailureMatrix.TAG)
class GenerationFailureMatrixCatalogTest {

    @Test
    void completedScenarioCatalogsMustContainAtLeastTwoSamples() {
        assertTrue(GenerationFailureMatrix.samplesFor(
                GenerationFailureScenario.CANCELLATION).size() >= 2);
        assertTrue(GenerationFailureMatrix.samplesFor(
                GenerationFailureScenario.APPROVAL).size() >= 2);
    }

    @Test
    void declaredSamplesMustBindExecutableEvidenceAndDurableIdentity() throws Exception {
        for (GenerationFailureScenario scenario : GenerationFailureScenario.values()) {
            verifySamples(GenerationFailureMatrix.samplesFor(scenario));
        }
    }

    private void verifySamples(List<GenerationFailureSample> samples) throws Exception {
        for (GenerationFailureSample sample : samples) {
            assertTrue(sample.durableIdentityFields().containsAll(
                    List.of("taskId", "executionEpoch")));
            assertTrue(sample.assertedFacts().size() >= 2);

            Class<?> testClass = Class.forName(sample.testClassName());
            Method testMethod = testClass.getDeclaredMethod(sample.testMethodName());
            GenerationFailureEvidence evidence = testMethod.getAnnotation(
                    GenerationFailureEvidence.class);

            assertTrue(testMethod.isAnnotationPresent(Test.class));
            assertNotNull(evidence);
            assertEquals(sample.id(), evidence.value());
            assertTrue(Arrays.stream(testClass.getAnnotationsByType(Tag.class))
                    .anyMatch(tag -> GenerationFailureMatrix.TAG.equals(tag.value())));
        }
    }
}
