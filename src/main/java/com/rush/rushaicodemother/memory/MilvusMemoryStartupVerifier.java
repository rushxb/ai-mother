package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fails application startup when production asks for a verified Milvus memory backend. */
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

    @Override
    public void afterSingletonsInstantiated() {
        if (properties.isVerifyOnStartup()) {
            collectionManager.ensureReady();
        }
    }
}
