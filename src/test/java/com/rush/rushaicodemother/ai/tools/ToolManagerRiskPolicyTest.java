package com.rush.rushaicodemother.ai.tools;

import cn.hutool.json.JSONObject;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolManagerRiskPolicyTest {

    @Test
    void autonomousCodeGenerationMustExcludeExternalSideEffectTools() {
        ToolManager manager = new ToolManager(new BaseTool[]{
                tool("read", ToolRiskLevel.READ_ONLY),
                tool("write", ToolRiskLevel.WRITE),
                tool("delete", ToolRiskLevel.DESTRUCTIVE),
                tool("install", ToolRiskLevel.EXTERNAL_SIDE_EFFECT)
        });
        manager.initTools();

        Set<String> names = Arrays.stream(manager.getToolsForCodeGen(CodeGenTypeEnum.VUE_PROJECT))
                .map(BaseTool::getToolName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("read", "write", "delete"), names);
    }

    @Test
    void duplicateToolNamesMustFailClosedAtStartup() {
        ToolManager manager = new ToolManager(new BaseTool[]{
                tool("same", ToolRiskLevel.READ_ONLY),
                tool("same", ToolRiskLevel.WRITE)
        });

        assertThrows(IllegalStateException.class, manager::initTools);
    }

    @Test
    void destructiveToolWithoutApprovalContractMustFailClosedAtStartup() {
        ToolManager manager = new ToolManager(new BaseTool[]{
                new TestTool("delete", ToolRiskLevel.DESTRUCTIVE)
        });

        assertThrows(IllegalStateException.class, manager::initTools);
    }

    private BaseTool tool(String name, ToolRiskLevel riskLevel) {
        if (riskLevel == ToolRiskLevel.DESTRUCTIVE) {
            return new ApprovalTestTool(name, riskLevel);
        }
        return new TestTool(name, riskLevel);
    }

    private static class TestTool extends BaseTool {
        private final String name;
        private final ToolRiskLevel riskLevel;

        private TestTool(String name, ToolRiskLevel riskLevel) {
            this.name = name;
            this.riskLevel = riskLevel;
        }

        @Override
        public String getToolName() {
            return name;
        }

        @Override
        public String getDisplayName() {
            return name;
        }

        @Override
        public ToolRiskLevel getRiskLevel() {
            return riskLevel;
        }

        @Override
        public String generateToolExecutedResult(JSONObject arguments) {
            return name;
        }
    }

    private static final class ApprovalTestTool extends TestTool implements ApprovalGatedTool {

        private ApprovalTestTool(String name, ToolRiskLevel riskLevel) {
            super(name, riskLevel);
        }

        @Override
        public void authorizeInvocation(ToolExecutionRequest request, Long appId) {
            // Test-only no-op approval contract.
        }
    }
}
