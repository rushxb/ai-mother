package com.rush.rushaicodemother.orchestration.agent.template;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;

/** 单一工程类型的模板初始化 adapter interface。 */
public interface GenerationTemplateBootstrapAdapter {

    /** 返回该 adapter 唯一负责的工程类型。 */
    CodeGenTypeEnum codeGenType();

    /** 在新项目工作区中执行模板初始化并返回标准 DAG 结果。 */
    AgentNodeResult bootstrap(GenerationAgentContext context);
}
