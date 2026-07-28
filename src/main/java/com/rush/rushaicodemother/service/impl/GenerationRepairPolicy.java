package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

/**
 * 控制生成阶段是否允许进入自动修复。
 * 新建应用的首轮生成不允许直接修复，必须先产出一个可检查的项目骨架。
 */
public final class GenerationRepairPolicy {

    private GenerationRepairPolicy() {
    }

    /**
 * 返回{@code allow}{@code Auto}{@code Repair}。
 *
 * @param generatingStage {@code generatingStage} 对应的调用参数
 * @param targetType 目标类型
 * @param maxAutoRepairRounds 待处理的 {@code maxAutoRepairRounds} 集合
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public static boolean allowAutoRepair(String generatingStage, CodeGenTypeEnum targetType, int maxAutoRepairRounds) {
        return maxAutoRepairRounds > 0
                && CodeGenTypeEnum.VUE_PROJECT == targetType
                && AppConstant.GENERATING_STAGE_UPDATE.equals(generatingStage);
    }
}
