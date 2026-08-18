package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

/** 单一工程类型的 CREATE 模板规划适配器。 */
public interface CreateTemplatePlanningAdapter {

    /** 返回该 adapter 唯一负责的工程类型。 */
    CodeGenTypeEnum codeGenType();

    /** 根据用户需求生成可执行的模板与 slot 计划。 */
    CreateGenerationPlan plan(String userMessage);
}
