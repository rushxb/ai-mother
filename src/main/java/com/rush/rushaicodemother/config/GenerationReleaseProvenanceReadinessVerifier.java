package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationReleaseProvenanceProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** 在生产实例接流量前验证发布来源元数据完整且来自干净制品。 */
@Component
@RequiredArgsConstructor
public class GenerationReleaseProvenanceReadinessVerifier implements SmartInitializingSingleton {

    private static final Profiles PRODUCTION = Profiles.of("prod");

    private final Environment environment;
    private final GenerationReleaseProvenanceProvider provenanceProvider;

    /** 在 Spring 单例 Bean 初始化完成后执行启动校验。 */
    @Override
    public void afterSingletonsInstantiated() {
        if (environment.acceptsProfiles(PRODUCTION)) {
            provenanceProvider.current();
        }
    }
}
