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
    void cancellationSamplesMustBindExecutableEvidenceAndDurableIdentity() throws Exception {
        List<GenerationFailureSample> samples = GenerationFailureMatrix.samplesFor(
                GenerationFailureScenario.CANCELLATION);

        assertTrue(samples.size() >= 2);
        for (GenerationFailureSample sample : samples) {
            assertTrue(sample.durableIdentityFields().containsAll(List.of(
                    "taskId", "executionEpoch")));
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
