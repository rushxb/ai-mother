package com.rush.rushaicodemother.core.builder;

import cn.hutool.core.util.StrUtil;

/** Vue 项目中会影响安装或构建结果的三类内容指纹。 */
record VueProjectSnapshot(
        String dependencyFingerprint,
        String criticalFingerprint,
        String presentationFingerprint
) {

    VueProjectSnapshot {
        dependencyFingerprint = StrUtil.nullToEmpty(dependencyFingerprint);
        criticalFingerprint = StrUtil.nullToEmpty(criticalFingerprint);
        presentationFingerprint = StrUtil.nullToEmpty(presentationFingerprint);
    }
}
