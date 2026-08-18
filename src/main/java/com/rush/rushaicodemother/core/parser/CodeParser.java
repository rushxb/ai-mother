package com.rush.rushaicodemother.core.parser;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

/**
 * 代码解析器策略接口
 * 
 * @author rush
 */
public interface CodeParser<T> {

    /**
     * 返回当前解析器负责的代码生成类型。
     *
     * @return 唯一代码生成类型
     */
    CodeGenTypeEnum codeGenType();

    /**
     * 解析代码内容
     * 
     * @param codeContent 原始代码内容
     * @return 解析后的结果对象
     */
    T parseCode(String codeContent);
}
