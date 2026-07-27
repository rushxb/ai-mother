package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationReleaseBuildMetadataArchitectureTest {

    @Test
    void buildMustPackageFullGitCommitAndDirtyMarker() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("git.properties")) {
            assertNotNull(input, "构建产物必须包含 git.properties");
            Properties properties = new Properties();
            properties.load(input);

            assertTrue(properties.getProperty("git.commit.id.full", "")
                    .matches("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})"));
            assertTrue(properties.getProperty("git.dirty", "")
                    .matches("true|false"));
        }
    }
}
