package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

/** 工程类型 adapter 的稳定身份。 */
public interface GenerationProjectTypeAdapter {

    /** 返回该 adapter 唯一负责的工程类型。 */
    CodeGenTypeEnum codeGenType();
}
