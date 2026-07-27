package com.rush.rushaicodemother.core.builder;

import cn.hutool.core.util.StrUtil;

/** Go 项目一次完整源码扫描得到的强内容指纹。 */
record GoProjectSnapshot(String sourceFingerprint) {

    GoProjectSnapshot {
        sourceFingerprint = StrUtil.nullToEmpty(sourceFingerprint);
    }
}
