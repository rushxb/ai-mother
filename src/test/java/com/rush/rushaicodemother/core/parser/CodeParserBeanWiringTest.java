package com.rush.rushaicodemother.core.parser;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CodeParserBeanWiringTest {

    @Test
    void wiresAllProductionParsersIntoExecutor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(HtmlCodeParser.class, MultiFileCodeParser.class, CodeParserExecutor.class);

            context.refresh();

            assertNotNull(context.getBean(CodeParserExecutor.class));
            assertEquals(2, context.getBeansOfType(CodeParser.class).size());
        }
    }
}
