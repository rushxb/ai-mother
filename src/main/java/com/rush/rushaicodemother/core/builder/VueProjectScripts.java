package com.rush.rushaicodemother.core.builder;

/** package.json 中可用于 Vue 校验和构建的脚本能力。 */
record VueProjectScripts(
        String fullBuildScript,
        String lightBuildScript,
        String lightValidationScript
) {

    boolean supportsLightValidation() {
        return lightValidationScript != null;
    }

    boolean supportsLightBuild() {
        return lightBuildScript != null;
    }
}
