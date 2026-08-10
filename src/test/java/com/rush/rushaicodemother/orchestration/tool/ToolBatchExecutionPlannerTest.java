package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.ai.tools.BaseTool;
import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.ai.tools.ToolRiskLevel;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 工具批次分段规划的边界与顺序语义。 */
class ToolBatchExecutionPlannerTest {

    private final ToolManager toolManager = mock(ToolManager.class);
    private final ToolBatchExecutionPlanner planner = new ToolBatchExecutionPlanner(toolManager);

    @Test
    void consecutiveReadOnlyToolsMustFormOneConcurrentSegment() {
        registerTool("readFile", ToolRiskLevel.READ_ONLY);
        registerTool("searchProject", ToolRiskLevel.READ_ONLY);

        List<ToolBatchSegment> segments = planner.plan(List.of(
                request("readFile", "1"), request("searchProject", "2"), request("readFile", "3")));

        assertEquals(1, segments.size());
        assertTrue(segments.getFirst().concurrent());
        assertEquals(List.of(0, 1, 2), segments.getFirst().requests().stream()
                .map(IndexedToolRequest::index).toList());
    }

    @Test
    void singleReadOnlyToolMustNotPayConcurrencyOverhead() {
        registerTool("readFile", ToolRiskLevel.READ_ONLY);

        List<ToolBatchSegment> segments = planner.plan(List.of(request("readFile", "1")));

        assertEquals(1, segments.size());
        assertFalse(segments.getFirst().concurrent());
    }

    @Test
    void writeToolMustSplitSegmentsAndPreserveRelativeOrder() {
        registerTool("readFile", ToolRiskLevel.READ_ONLY);
        registerTool("writeFile", ToolRiskLevel.WRITE);

        // 读、读、写、读：写操作必须看到前两次读取结果，且写之后的读单独成段。
        List<ToolBatchSegment> segments = planner.plan(List.of(
                request("readFile", "1"),
                request("readFile", "2"),
                request("writeFile", "3"),
                request("readFile", "4")));

        assertEquals(3, segments.size());
        assertTrue(segments.get(0).concurrent());
        assertEquals(List.of(0, 1), segments.get(0).requests().stream()
                .map(IndexedToolRequest::index).toList());
        assertFalse(segments.get(1).concurrent());
        assertEquals(2, segments.get(1).requests().getFirst().index());
        assertFalse(segments.get(2).concurrent());
        assertEquals(3, segments.get(2).requests().getFirst().index());
    }

    @Test
    void destructiveAndExternalSideEffectToolsMustStaySequential() {
        registerTool("deleteFile", ToolRiskLevel.DESTRUCTIVE);
        registerTool("buildProject", ToolRiskLevel.EXTERNAL_SIDE_EFFECT);

        List<ToolBatchSegment> segments = planner.plan(List.of(
                request("deleteFile", "1"), request("buildProject", "2")));

        assertEquals(2, segments.size());
        assertFalse(segments.get(0).concurrent());
        assertFalse(segments.get(1).concurrent());
    }

    @Test
    void unknownToolMustBeTreatedAsSideEffectingAndStaySequential() {
        registerTool("readFile", ToolRiskLevel.READ_ONLY);
        when(toolManager.getTool("mysteryTool")).thenReturn(null);

        List<ToolBatchSegment> segments = planner.plan(List.of(
                request("readFile", "1"), request("mysteryTool", "2"), request("readFile", "3")));

        assertEquals(3, segments.size());
        assertFalse(segments.get(1).concurrent());
    }

    @Test
    void emptyBatchMustProduceNoSegments() {
        assertTrue(planner.plan(List.of()).isEmpty());
    }

    private void registerTool(String name, ToolRiskLevel riskLevel) {
        when(toolManager.getTool(name)).thenReturn(new StubTool(name, riskLevel));
    }

    private ToolExecutionRequest request(String name, String id) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    private static final class StubTool extends BaseTool {

        private final String name;
        private final ToolRiskLevel riskLevel;

        private StubTool(String name, ToolRiskLevel riskLevel) {
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
}
