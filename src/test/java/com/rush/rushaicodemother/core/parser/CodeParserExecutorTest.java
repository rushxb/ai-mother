package com.rush.rushaicodemother.core.parser;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeParserExecutorTest {

    @Test
    void routesNewGenerationTypeThroughRegisteredParserWithoutChangingExecutor() {
        CodeParser<String> backendProjectParser = new CodeParser<>() {
            @Override
            public CodeGenTypeEnum codeGenType() {
                return CodeGenTypeEnum.BACKEND_PROJECT;
            }

            @Override
            public String parseCode(String codeContent) {
                return "parsed:" + codeContent;
            }
        };
        CodeParserExecutor executor = new CodeParserExecutor(List.of(
                new HtmlCodeParser(),
                new MultiFileCodeParser(),
                backendProjectParser
        ));

        Object result = executor.executeParser("backend source", CodeGenTypeEnum.BACKEND_PROJECT);

        assertEquals("parsed:backend source", result);
    }

    @Test
    void rejectsDuplicateParserRegistrationAtStartup() {
        CodeParser<String> duplicateHtmlParser = parserFor(CodeGenTypeEnum.HTML);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new CodeParserExecutor(List.of(new HtmlCodeParser(), duplicateHtmlParser))
        );

        assertTrue(exception.getMessage().contains("重复解析器"));
    }

    @Test
    void rejectsUnregisteredGenerationTypeWithExplicitError() {
        CodeParserExecutor executor = new CodeParserExecutor(List.of(
                new HtmlCodeParser(),
                new MultiFileCodeParser()
        ));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> executor.executeParser("source", CodeGenTypeEnum.VUE_PROJECT)
        );

        assertTrue(exception.getMessage().contains("未注册代码解析器"));
    }

    private CodeParser<String> parserFor(CodeGenTypeEnum codeGenType) {
        return new CodeParser<>() {
            @Override
            public CodeGenTypeEnum codeGenType() {
                return codeGenType;
            }

            @Override
            public String parseCode(String codeContent) {
                return codeContent;
            }
        };
    }
}
