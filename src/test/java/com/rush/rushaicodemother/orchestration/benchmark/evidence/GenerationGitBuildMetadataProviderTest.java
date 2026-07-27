package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationGitBuildMetadataProviderTest {

    private static final String COMMIT = "a".repeat(40);

    @Test
    void mustReadCanonicalBuildMetadata() {
        GenerationGitBuildMetadataProvider provider = provider(
                "git.commit.id.full=" + COMMIT + "\ngit.dirty=true\n"
        );

        GenerationGitBuildMetadataProvider.BuildMetadata metadata = provider.current();

        assertEquals(COMMIT, metadata.commit());
        assertTrue(metadata.dirty());
    }

    @Test
    void incompleteBuildMetadataMustFailClosed() {
        GenerationGitBuildMetadataProvider provider = provider("git.dirty=false\n");

        assertThrows(BusinessException.class, provider::current);
    }

    private GenerationGitBuildMetadataProvider provider(String content) {
        return new GenerationGitBuildMetadataProvider(new ByteArrayResource(
                content.getBytes(StandardCharsets.ISO_8859_1)
        ));
    }
}
