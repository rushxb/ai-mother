package com.yupi.yuaicodemother.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 代码生成类型枚举
 */
@Getter
public enum CodeGenTypeEnum {

    HTML("原生 HTML 模式", "html", 1),
    MULTI_FILE("原生多文件模式", "multi_file", 2),
    VUE_PROJECT("Vue 工程模式", "vue_project", 3),
    BACKEND_PROJECT("后端工程模式", "backend_project", 4),
    FULL_STACK_PROJECT("全栈工程模式", "full_stack_project", 5);

    private final String text;
    private final String value;
    private final int level;

    CodeGenTypeEnum(String text, String value, int level) {
        this.text = text;
        this.value = value;
        this.level = level;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static CodeGenTypeEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (CodeGenTypeEnum anEnum : CodeGenTypeEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }

    /**
     * 是否需要从当前类型升级到目标类型
     *
     * @param targetType 目标类型
     * @return 是否升级
     */
    public boolean canUpgradeTo(CodeGenTypeEnum targetType) {
        return targetType != null && targetType.level > this.level;
    }

    /**
     * 选择两个类型中承载能力更强的一个，避免后续对话把项目降级回简单模式
     *
     * @param left  类型1
     * @param right 类型2
     * @return 更强的代码生成类型
     */
    public static CodeGenTypeEnum max(CodeGenTypeEnum left, CodeGenTypeEnum right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.level >= right.level ? left : right;
    }
}
