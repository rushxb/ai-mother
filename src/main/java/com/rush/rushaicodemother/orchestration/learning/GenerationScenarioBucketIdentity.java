package com.rush.rushaicodemother.orchestration.learning;

/** 标识一组可重放的场景策略观测，发布身份必须来自真实运行指纹。 */
public record GenerationScenarioBucketIdentity(
        String intentSignature,
        String profileVersion,
        String decisionVersion,
        String route,
        String releaseIdentity
) {

    public GenerationScenarioBucketIdentity {
        intentSignature = requireText(intentSignature, "场景签名");
        profileVersion = requireText(profileVersion, "意图画像版本");
        decisionVersion = requireText(decisionVersion, "决策版本");
        route = requireText(route, "实际路由");
        releaseIdentity = requireText(releaseIdentity, "发布身份");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.trim();
    }
}
