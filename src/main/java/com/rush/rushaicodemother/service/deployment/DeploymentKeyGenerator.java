package com.rush.rushaicodemother.service.deployment;

/** 生成不可预测的应用部署标识。 */
@FunctionalInterface
public interface DeploymentKeyGenerator {

    String generate();
}
