package com.rush.rushaicodemother.ai.tools;

import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolManagerContractTest {

    @Test
    void exitToolRegistrationMustMatchTheNameVisibleToTheModel() throws Exception {
        ToolManager manager = new ToolManager(new BaseTool[]{new ExitTool()});

        manager.initTools();

        assertEquals("exitTool", manager.getTool("exitTool").getToolName());
        assertEquals(
                ReturnBehavior.IMMEDIATE_IF_LAST,
                ExitTool.class.getMethod("exitTool")
                        .getAnnotation(Tool.class)
                        .returnBehavior()
        );
    }

    @Test
    void mismatchedRegistrationAndAnnotatedMethodNamesMustFailFast() {
        ToolManager manager = new ToolManager(new BaseTool[]{new MismatchedTool()});

        assertThrows(IllegalStateException.class, manager::initTools);
    }

    private static final class MismatchedTool extends BaseTool {

        @Tool("测试工具")
        public String actualName() {
            return "ok";
        }

        @Override
        public String getToolName() {
            return "differentName";
        }

        @Override
        public String getDisplayName() {
            return "名称不一致工具";
        }

        @Override
        public ToolRiskLevel getRiskLevel() {
            return ToolRiskLevel.READ_ONLY;
        }

        @Override
        public String generateToolExecutedResult(JSONObject arguments) {
            return "ok";
        }
    }
}
