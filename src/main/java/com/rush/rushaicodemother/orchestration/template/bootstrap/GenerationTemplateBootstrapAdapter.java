package com.rush.rushaicodemother.orchestration.template.bootstrap;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

/** 单一工程类型的模板初始化 adapter interface。 */
public interface GenerationTemplateBootstrapAdapter {

    /** 返回该 adapter 唯一负责的工程类型。 */
    CodeGenTypeEnum codeGenType();

    /** 执行模板及其运行上下文初始化。 */
    GenerationTemplateBootstrapOutput bootstrap(GenerationTemplateBootstrapRequest request);
}
