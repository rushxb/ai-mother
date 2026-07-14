package com.rush.rushaicodemother.core.builder;

import cn.hutool.core.util.StrUtil;

/** 已成功安装或构建的 Vue 项目指纹状态。 */
record VueBuildState(
        String dependencyFingerprint,
        String criticalFingerprint,
        String presentationFingerprint
) {

    VueBuildState {
        dependencyFingerprint = StrUtil.nullToEmpty(dependencyFingerprint);
        criticalFingerprint = StrUtil.nullToEmpty(criticalFingerprint);
        presentationFingerprint = StrUtil.nullToEmpty(presentationFingerprint);
    }

    static VueBuildState empty() {
        return new VueBuildState("", "", "");
    }

    static VueBuildState fromSnapshot(VueProjectSnapshot snapshot) {
        return new VueBuildState(
                snapshot.dependencyFingerprint(),
                snapshot.criticalFingerprint(),
                snapshot.presentationFingerprint()
        );
    }

    VueBuildState withDependencyFingerprint(String fingerprint) {
        return new VueBuildState(fingerprint, criticalFingerprint, presentationFingerprint);
    }
}
