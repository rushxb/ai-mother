package com.rush.rushaicodemother.service.artifact;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** Single validation policy for deployment artifact directory keys. */
@Component
public class DeploymentKeyPolicy {

    private static final Pattern DEPLOYMENT_KEY_PATTERN = Pattern.compile("[A-Za-z0-9]{6,64}");

    /** Returns whether the supplied value can safely identify one deployment directory. */
    public boolean isValid(String deploymentKey) {
        return deploymentKey != null && DEPLOYMENT_KEY_PATTERN.matcher(deploymentKey).matches();
    }

    /** Requires a valid deployment key for callers that use argument-style error handling. */
    public void requireValid(String deploymentKey) {
        if (!isValid(deploymentKey)) {
            throw new IllegalArgumentException("部署标识格式错误");
        }
    }
}
