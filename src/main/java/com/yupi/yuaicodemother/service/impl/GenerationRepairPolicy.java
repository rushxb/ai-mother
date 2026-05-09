package com.yupi.yuaicodemother.service.impl;

import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;

/**
 * 控制生成阶段是否允许进入自动修复。
 * 新建应用的首轮生成不允许直接修复，必须先产出一个可检查的项目骨架。
 */
final class GenerationRepairPolicy {

    private GenerationRepairPolicy() {
    }

    static boolean allowAutoRepair(String generatingStage, CodeGenTypeEnum targetType, int maxAutoRepairRounds) {
        return maxAutoRepairRounds > 0
                && CodeGenTypeEnum.VUE_PROJECT == targetType
                && AppConstant.GENERATING_STAGE_UPDATE.equals(generatingStage);
    }
}
