package com.rush.rushaicodemother.ai.tools;

import cn.hutool.json.JSONObject;
import com.rush.rushaicodemother.ai.tools.policy.DependencyPolicyService;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionGateway;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ToolManagerRiskPolicyTest {

    @Test
    void autonomousCodeGenerationMustExcludeExternalSideEffectTools() {
        ToolManager manager = new ToolManager(new BaseTool[]{
                new ReadTestTool(),
                new WriteTestTool(),
                new ApprovalDeleteTestTool(),
                new InstallTestTool()
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
                new ReadTestTool(),
                new ReadTestTool()
        });

        assertThrows(IllegalStateException.class, manager::initTools);
    }

    @Test
    void destructiveToolWithoutApprovalContractMustFailClosedAtStartup() {
        ToolManager manager = new ToolManager(new BaseTool[]{
                new UnsafeDeleteTestTool()
        });

        assertThrows(IllegalStateException.class, manager::initTools);
    }

    @Test
    void packageManagerMustBeVisibleOnlyToFrontendCodeGenerationModes() {
        PackageManagerTool packageManagerTool = new PackageManagerTool(
                new DependencyPolicyService(),
                mock(ToolExecutionGateway.class),
                mock(ToolWorkspaceFileService.class)
        );
        ToolManager manager = new ToolManager(new BaseTool[]{packageManagerTool});
        manager.initTools();

        assertTrue(manager.isToolAllowedForCodeGen("managePackageJson", CodeGenTypeEnum.VUE_PROJECT));
        assertTrue(manager.isToolAllowedForCodeGen("managePackageJson", CodeGenTypeEnum.FULL_STACK_PROJECT));
        assertFalse(manager.isToolAllowedForCodeGen("managePackageJson", CodeGenTypeEnum.BACKEND_PROJECT));
    }

    private abstract static class TestTool extends BaseTool {
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

    private static final class ReadTestTool extends TestTool {

        private ReadTestTool() {
            super("read", ToolRiskLevel.READ_ONLY);
        }

        @Tool("测试读取工具")
        public String read() {
            return "ok";
        }
    }

    private static final class WriteTestTool extends TestTool {

        private WriteTestTool() {
            super("write", ToolRiskLevel.WRITE);
        }

        @Tool("测试写入工具")
        public String write() {
            return "ok";
        }
    }

    private static final class InstallTestTool extends TestTool {

        private InstallTestTool() {
            super("install", ToolRiskLevel.EXTERNAL_SIDE_EFFECT);
        }

        @Tool("测试外部副作用工具")
        public String install() {
            return "ok";
        }
    }

    private static final class UnsafeDeleteTestTool extends TestTool {

        private UnsafeDeleteTestTool() {
            super("unsafeDelete", ToolRiskLevel.DESTRUCTIVE);
        }

        @Tool("测试未审批删除工具")
        public String unsafeDelete() {
            return "ok";
        }
    }

    private static final class ApprovalDeleteTestTool extends TestTool implements ApprovalGatedTool {

        private ApprovalDeleteTestTool() {
            super("delete", ToolRiskLevel.DESTRUCTIVE);
        }

        @Tool("测试审批删除工具")
        public String delete() {
            return "ok";
        }

        @Override
        public void authorizeInvocation(ToolExecutionRequest request, Long appId) {
            // 测试桩只验证审批契约是否存在。
        }
    }
}
