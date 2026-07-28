package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 当生产环境要求经过验证的 Milvus 内存后端时，应用程序启动失败。 */
@Component
@ConditionalOnProperty(prefix = "app.memory.long-term", name = "enabled", havingValue = "true")
public class MilvusMemoryStartupVerifier implements SmartInitializingSingleton {

    private final MilvusMemoryProperties properties;
    private final MilvusMemoryCollectionManager collectionManager;

    public MilvusMemoryStartupVerifier(MilvusMemoryProperties properties,
                                       MilvusMemoryCollectionManager collectionManager) {
        this.properties = properties;
        this.collectionManager = collectionManager;
    }

    /** 在 Spring 单例 Bean 初始化完成后执行启动校验。 */
    @Override
    public void afterSingletonsInstantiated() {
        if (properties.isVerifyOnStartup()) {
            collectionManager.ensureReady();
        }
    }
}
