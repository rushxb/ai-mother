package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.config.ProjectCommandProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class VueBuildResultRegistryTest {

    @TempDir
    Path projectRoot;

    @Test
    void shouldEvictLeastRecentlyUsedResultsAtConfiguredLimit() {
        ProjectCommandProperties properties = new ProjectCommandProperties();
        properties.setRecentBuildResultMaxEntries(2);
        VueBuildResultRegistry registry = new VueBuildResultRegistry(properties);
        VueProjectSnapshot first = snapshot("1");
        VueProjectSnapshot second = snapshot("2");
        VueProjectSnapshot third = snapshot("3");
        VueBuildResult firstResult = VueBuildResult.invalid(projectRoot.toString(), "first");
        VueBuildResult secondResult = VueBuildResult.invalid(projectRoot.toString(), "second");
        VueBuildResult thirdResult = VueBuildResult.invalid(projectRoot.toString(), "third");

        registry.remember(projectRoot, first, firstResult);
        registry.remember(projectRoot, second, secondResult);
        assertSame(firstResult, registry.find(projectRoot, first));
        registry.remember(projectRoot, third, thirdResult);

        assertEquals(2, registry.size());
        assertNull(registry.find(projectRoot, second));
        assertSame(firstResult, registry.find(projectRoot, first));
        assertSame(thirdResult, registry.find(projectRoot, third));
    }

    private VueProjectSnapshot snapshot(String suffix) {
        return new VueProjectSnapshot("dependency-" + suffix, "critical-" + suffix, "presentation-" + suffix);
    }
}
