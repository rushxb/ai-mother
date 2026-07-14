package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.config.ProjectCommandProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按项目绝对路径和完整快照缓存最近构建结果。LRU 上限避免长期运行时无界占用内存。
 */
@Component
public class VueBuildResultRegistry {

    private final Map<CacheKey, VueBuildResult> recentResults;

    public VueBuildResultRegistry(ProjectCommandProperties properties) {
        int maxEntries = properties.getRecentBuildResultMaxEntries();
        this.recentResults = Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, VueBuildResult> eldest) {
                return size() > maxEntries;
            }
        });
    }

    void remember(Path projectRoot, VueProjectSnapshot snapshot, VueBuildResult buildResult) {
        if (projectRoot == null || snapshot == null || buildResult == null) {
            return;
        }
        recentResults.put(CacheKey.of(projectRoot, snapshot), buildResult);
    }

    VueBuildResult find(Path projectRoot, VueProjectSnapshot snapshot) {
        if (projectRoot == null || snapshot == null) {
            return null;
        }
        return recentResults.get(CacheKey.of(projectRoot, snapshot));
    }

    int size() {
        return recentResults.size();
    }

    private record CacheKey(Path projectRoot, VueProjectSnapshot snapshot) {

        private static CacheKey of(Path projectRoot, VueProjectSnapshot snapshot) {
            return new CacheKey(projectRoot.toAbsolutePath().normalize(), snapshot);
        }
    }
}
