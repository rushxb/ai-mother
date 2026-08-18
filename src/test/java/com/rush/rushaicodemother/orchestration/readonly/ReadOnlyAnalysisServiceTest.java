package com.rush.rushaicodemother.orchestration.readonly;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.edit.AgentEditContextCollector;
import com.rush.rushaicodemother.orchestration.edit.AgentEditReadResult;
import com.rush.rushaicodemother.orchestration.edit.EditContextPackage;
import com.rush.rushaicodemother.orchestration.edit.EditFileCandidate;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadOnlyAnalysisServiceTest {

    @Test
    void analysisMustOnlyPublishReferencesGroundedInCollectedWorkspaceContext() {
        AgentEditContextCollector contextCollector = mock(AgentEditContextCollector.class);
        GenerationWorkspace workspace = workspace();
        when(contextCollector.collect(workspace, "审计鉴权链路", CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(contextResult());
        ReadOnlyAnalysisModel model = (taskId, request) -> new ReadOnlyAnalysisResult(
                "鉴权链路存在边界风险",
                List.of(new ReadOnlyAnalysisResult.Finding(
                        "缺少所有权校验", "HIGH", "详情接口未校验资源所有者")),
                List.of(
                        new ReadOnlyAnalysisResult.FileReference(
                                "src/auth.ts", 18, "详情读取入口"),
                        new ReadOnlyAnalysisResult.FileReference(
                                "../../secrets.txt", 1, "模型臆造的越界文件")),
                "本次请求仅要求审计，因此未修改工作区"
        );
        ReadOnlyAnalysisService service = new ReadOnlyAnalysisService(contextCollector, model);

        ReadOnlyAnalysisResult result = service.analyze(
                "read-only-task", IntentOperationType.AUDIT, "审计鉴权链路",
                workspace, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals(List.of("src/auth.ts"),
                result.references().stream()
                        .map(ReadOnlyAnalysisResult.FileReference::relativePath)
                        .toList());
        assertTrue(result.renderMarkdown().contains("src/auth.ts:18"));
        assertFalse(result.renderMarkdown().contains("secrets.txt"));
    }

    @Test
    void analysisMustNotPublishLineNumbersOutsideCollectedFileContent() {
        AgentEditContextCollector contextCollector = mock(AgentEditContextCollector.class);
        GenerationWorkspace workspace = workspace();
        when(contextCollector.collect(workspace, "解释鉴权入口", CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(contextResult());
        ReadOnlyAnalysisModel model = (taskId, request) -> new ReadOnlyAnalysisResult(
                "已定位鉴权入口",
                List.of(),
                List.of(new ReadOnlyAnalysisResult.FileReference(
                        "src/auth.ts", 999, "鉴权入口")),
                "本次请求仅要求解释，因此未修改工作区"
        );
        ReadOnlyAnalysisService service = new ReadOnlyAnalysisService(contextCollector, model);

        ReadOnlyAnalysisResult result = service.analyze(
                "read-only-task", IntentOperationType.EXPLAIN, "解释鉴权入口",
                workspace, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals(1, result.references().size());
        assertNull(result.references().getFirst().line());
        assertFalse(result.renderMarkdown().contains(":999"));
    }

    @Test
    void analysisMustKeepDistinctGroundedLocationsInTheSameFile() {
        AgentEditContextCollector contextCollector = mock(AgentEditContextCollector.class);
        GenerationWorkspace workspace = workspace();
        when(contextCollector.collect(workspace, "审计鉴权实现", CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(contextResult());
        ReadOnlyAnalysisModel model = (taskId, request) -> new ReadOnlyAnalysisResult(
                "鉴权文件包含两个独立风险点",
                List.of(),
                List.of(
                        new ReadOnlyAnalysisResult.FileReference(
                                "src/auth.ts", 3, "入口缺少参数校验"),
                        new ReadOnlyAnalysisResult.FileReference(
                                "src/auth.ts", 18, "读取前缺少所有权校验")),
                "本次请求仅要求审计，因此未修改工作区"
        );
        ReadOnlyAnalysisService service = new ReadOnlyAnalysisService(contextCollector, model);

        ReadOnlyAnalysisResult result = service.analyze(
                "read-only-task", IntentOperationType.AUDIT, "审计鉴权实现",
                workspace, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals(List.of(3, 18), result.references().stream()
                .map(ReadOnlyAnalysisResult.FileReference::line)
                .toList());
    }

    private AgentEditReadResult contextResult() {
        EditFileCandidate candidate = new EditFileCandidate(
                "src/auth.ts", "auth.ts", "keyword", 100, "鉴权关键词命中", List.of("鉴权"));
        return new AgentEditReadResult(
                "audit",
                List.of(candidate),
                new EditContextPackage(
                        List.of(candidate),
                        Map.of("src/auth.ts", authSourceWith18Lines()),
                        64,
                        "src/auth.ts"),
                Map.of(),
                List.of(),
                List.of("getApp"),
                List.of(),
                List.of(),
                "medium"
        );
    }

    private String authSourceWith18Lines() {
        return "// collected context\n".repeat(17)
                + "export function getApp(id: number) { return api.get(id); }";
    }

    private GenerationWorkspace workspace() {
        Path root = Path.of("target/read-only-analysis-service-test").toAbsolutePath().normalize();
        return new GenerationWorkspace(
                1L, CodeGenTypeEnum.VUE_PROJECT, root, root, true,
                root, root, Set.of(), Set.of());
    }
}
