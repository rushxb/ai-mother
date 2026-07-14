package com.rush.rushaicodemother.service.deployment;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** 使用密码学安全随机数生成 URL 安全部署标识。 */
@Component
public final class SecureDeploymentKeyGenerator implements DeploymentKeyGenerator {

    private static final int DEPLOY_KEY_LENGTH = 12;
    private static final String DEPLOY_KEY_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String generate() {
        StringBuilder deployKey = new StringBuilder(DEPLOY_KEY_LENGTH);
        for (int index = 0; index < DEPLOY_KEY_LENGTH; index++) {
            deployKey.append(DEPLOY_KEY_ALPHABET.charAt(
                    secureRandom.nextInt(DEPLOY_KEY_ALPHABET.length())
            ));
        }
        return deployKey.toString();
    }
}
