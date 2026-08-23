package com.rush.rushaicodemother.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 代码生成类型枚举
 */
@Getter
public enum CodeGenTypeEnum {

    HTML("原生 HTML 模式", "html", 1, true, false),
    MULTI_FILE("原生多文件模式", "multi_file", 2, true, false),
    VUE_PROJECT("Vue 工程模式", "vue_project", 3, true, false),
    BACKEND_PROJECT("后端工程模式", "backend_project", 4, false, true),
    FULL_STACK_PROJECT("全栈工程模式", "full_stack_project", 5, true, true);

    private final String text;
    private final String value;
    private final int level;
    private final boolean frontendCapable;
    private final boolean backendCapable;

    CodeGenTypeEnum(String text,
                    String value,
                    int level,
                    boolean frontendCapable,
                    boolean backendCapable) {
        this.text = text;
        this.value = value;
        this.level = level;
        this.frontendCapable = frontendCapable;
        this.backendCapable = backendCapable;
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
        return targetType != null
                && targetType != this
                && targetType.level > this.level
                && (!frontendCapable || targetType.frontendCapable)
                && (!backendCapable || targetType.backendCapable);
    }

    /**
     * 选择能够同时承载两个工程类型能力的最小类型，避免后续对话丢失已有工程能力。
     *
     * <p>前端与后端不是可直接比较的单一等级；二者组合必须得到全栈工程。
     * 同为前端工程时才使用等级选择更完整的形态。</p>
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
        int minimumLevel = Math.max(left.level, right.level);
        boolean frontendRequired = left.frontendCapable || right.frontendCapable;
        boolean backendRequired = left.backendCapable || right.backendCapable;
        for (CodeGenTypeEnum candidate : values()) {
            if (candidate.level >= minimumLevel
                    && (!frontendRequired || candidate.frontendCapable)
                    && (!backendRequired || candidate.backendCapable)) {
                return candidate;
            }
        }
        throw new IllegalStateException("没有能够组合工程能力的代码生成类型");
    }
}
