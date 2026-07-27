package com.rush.rushaicodemother.service.artifact;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 部署工件目录密钥的单一验证策略。 */
@Component
public class DeploymentKeyPolicy {

    private static final Pattern DEPLOYMENT_KEY_PATTERN = Pattern.compile("[A-Za-z0-9]{6,64}");

    /** 返回所提供的值是否可以安全地识别一个部署目录。 */
    public boolean isValid(String deploymentKey) {
        return deploymentKey != null && DEPLOYMENT_KEY_PATTERN.matcher(deploymentKey).matches();
    }

    /** 使用参数样式错误处理的调用者需要有效的部署密钥。 */
    public void requireValid(String deploymentKey) {
        if (!isValid(deploymentKey)) {
            throw new IllegalArgumentException("部署标识格式错误");
        }
    }
}
