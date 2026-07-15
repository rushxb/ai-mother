package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeStoragePropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldExposeNormalizedAbsoluteRoots() {
        CodeStorageProperties properties = properties(
                Path.of("target", "storage", "..", "generated"),
                Path.of("target", "deployment"),
                Path.of("target", "snapshots", "..", "generation-snapshots")
        );

        assertEquals(Path.of("target", "generated").toAbsolutePath().normalize(), properties.outputRoot());
        assertEquals(Path.of("target", "deployment").toAbsolutePath().normalize(), properties.deployRoot());
        assertEquals(
                Path.of("target", "generation-snapshots").toAbsolutePath().normalize(),
                properties.snapshotRoot()
        );
        assertTrue(properties.outputRoot().isAbsolute());
        assertTrue(properties.deployRoot().isAbsolute());
        assertTrue(properties.snapshotRoot().isAbsolute());
        assertTrue(validator.validate(properties).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("invalidStorageLayouts")
    void shouldRejectMissingEqualOrNestedStorageRoots(
            Path outputRoot,
            Path deployRoot,
            Path snapshotRoot
    ) {
        assertFalse(validator.validate(properties(outputRoot, deployRoot, snapshotRoot)).isEmpty());
    }

    private static Stream<Arguments> invalidStorageLayouts() {
        Path output = Path.of("target", "storage-layout", "output");
        Path deploy = Path.of("target", "storage-layout", "deploy");
        Path snapshot = Path.of("target", "storage-layout", "snapshot");
        return Stream.of(
                Arguments.of(null, deploy, snapshot),
                Arguments.of(output, null, snapshot),
                Arguments.of(output, deploy, null),
                Arguments.of(output, output, snapshot),
                Arguments.of(output, deploy, output),
                Arguments.of(output, deploy, deploy),
                Arguments.of(output, output.resolve("deploy"), snapshot),
                Arguments.of(output.resolve("generated"), output, snapshot),
                Arguments.of(output, deploy, deploy.resolve("snapshot")),
                Arguments.of(output, snapshot.resolve("deploy"), snapshot),
                Arguments.of(snapshot.resolve("output"), deploy, snapshot),
                Arguments.of(output, deploy, output.resolve("snapshot"))
        );
    }

    private CodeStorageProperties properties(Path outputRoot, Path deployRoot, Path snapshotRoot) {
        CodeStorageProperties properties = new CodeStorageProperties();
        properties.setOutputRootDir(outputRoot);
        properties.setDeployRootDir(deployRoot);
        properties.setSnapshotRootDir(snapshotRoot);
        return properties;
    }
}
