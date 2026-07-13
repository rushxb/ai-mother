package com.rush.rushaicodemother.config.production;

/**
 * 生产环境配置不满足启动契约时抛出的异常。
 */
public class ProductionConfigurationException extends IllegalStateException {

    public ProductionConfigurationException(String message) {
        super(message);
    }
}
