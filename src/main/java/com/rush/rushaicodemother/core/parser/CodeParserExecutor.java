package com.rush.rushaicodemother.core.parser;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按代码生成类型路由解析器。新增生成类型时只需注册新的 {@link CodeParser} Bean。
 */
@Component
public class CodeParserExecutor {

    private final Map<CodeGenTypeEnum, CodeParser<?>> parsersByType;

    /**
     * 构建不可变解析器注册表，并在启动阶段拒绝重复或无类型声明的适配器。
     *
     * @param parsers Spring 收集到的解析器适配器
     */
    public CodeParserExecutor(List<CodeParser<?>> parsers) {
        if (parsers == null) {
            throw new IllegalStateException("代码解析器列表不能为空");
        }
        EnumMap<CodeGenTypeEnum, CodeParser<?>> registeredParsers =
                new EnumMap<>(CodeGenTypeEnum.class);
        for (CodeParser<?> parser : parsers) {
            registerParser(registeredParsers, parser);
        }
        this.parsersByType = Map.copyOf(registeredParsers);
    }

    /**
     * 执行代码解析
     *
     * @param codeContent 代码内容
     * @param codeGenType 代码生成类型
     * @return 对应解析器声明的解析结果
     */
    public Object executeParser(String codeContent, CodeGenTypeEnum codeGenType) {
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码生成类型不能为空");
        }
        CodeParser<?> parser = parsersByType.get(codeGenType);
        if (parser == null) {
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "未注册代码解析器: " + codeGenType.getValue()
            );
        }
        return parser.parseCode(codeContent);
    }

    private static void registerParser(Map<CodeGenTypeEnum, CodeParser<?>> registeredParsers,
                                       CodeParser<?> parser) {
        if (parser == null) {
            throw new IllegalStateException("代码解析器列表不能包含 null");
        }
        CodeGenTypeEnum codeGenType = parser.codeGenType();
        if (codeGenType == null) {
            throw new IllegalStateException("代码解析器必须声明代码生成类型: " + parser.getClass().getName());
        }
        CodeParser<?> previous = registeredParsers.putIfAbsent(codeGenType, parser);
        if (previous != null) {
            throw new IllegalStateException("代码生成类型存在重复解析器: " + codeGenType.getValue());
        }
    }
}
