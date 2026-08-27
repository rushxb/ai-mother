package com.rush.rushaicodemother.orchestration.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.ai.tools.FileBatchWriteTool;
import com.rush.rushaicodemother.ai.tools.FileDeleteTool;
import com.rush.rushaicodemother.ai.tools.FileModifyTool;
import com.rush.rushaicodemother.ai.tools.FileReadTool;
import com.rush.rushaicodemother.ai.tools.FileWriteTool;
import com.rush.rushaicodemother.ai.tools.ReadMultipleFilesTool;
import com.rush.rushaicodemother.orchestration.tool.ToolResultEvidence;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具路径提取回归。
 *
 * <p>提取表按工具名和参数名硬编码。这些名称并非本类自己定义的常量，而是
 * LangChain4j 从 {@code @Tool} 方法签名推导后真正发给模型的协议字段，
 * 因此测试直接用同一条推导链路取名字来比对：任何一次方法改名、参数改名，
 * 或编译期 {@code -parameters} 被关闭，都会在此失败。</p>
 *
 * <p>若不设这道防线，名称漂移的后果是折叠摘要静默变空 —— 模型会把已写好的
 * 文件重新写一遍，且全链路不报任何错。</p>
 */
class ToolRoundPathExtractorTest {

    private final ToolRoundPathExtractor extractor =
            new ToolRoundPathExtractor(new ObjectMapper());

    /** 一个文件工具的协议期望：工具类、承载路径的顶层参数名、作用类别。 */
    private record ToolContract(
            Class<?> toolClass,
            String expectedPathProperty,
            ToolRoundPathExtractor.PathEffect expectedEffect
    ) {
    }

    private static final List<ToolContract> FILE_TOOL_CONTRACTS = List.of(
            new ToolContract(FileReadTool.class, "relativeFilePath",
                    ToolRoundPathExtractor.PathEffect.READ),
            new ToolContract(ReadMultipleFilesTool.class, "relativeFilePaths",
                    ToolRoundPathExtractor.PathEffect.READ),
            new ToolContract(FileWriteTool.class, "relativeFilePath",
                    ToolRoundPathExtractor.PathEffect.MUTATE),
            new ToolContract(FileModifyTool.class, "relativeFilePath",
                    ToolRoundPathExtractor.PathEffect.MUTATE),
            new ToolContract(FileBatchWriteTool.class, "files",
                    ToolRoundPathExtractor.PathEffect.MUTATE),
            new ToolContract(FileDeleteTool.class, "relativeFilePath",
                    ToolRoundPathExtractor.PathEffect.DELETE));

    @Test
    void everyFileToolWireContractMustBeCoveredByExtractor() {
        for (ToolContract contract : FILE_TOOL_CONTRACTS) {
            ToolSpecification spec = specificationOf(contract.toolClass());

            assertTrue(spec.parameters().properties().containsKey(contract.expectedPathProperty()),
                    spec.name() + " 的协议参数已不含 " + contract.expectedPathProperty()
                            + "，实际参数为 " + spec.parameters().properties().keySet()
                            + "；ToolRoundPathExtractor 必须同步更新");

            // 用协议里真实的参数名合成调用，等价于模型实际会发出的报文。
            ToolRoundPathExtractor.ExtractedPaths extracted = switch (contract.expectedEffect()) {
                case READ -> ReadMultipleFilesTool.class.equals(contract.toolClass())
                        ? extractBatchRead(
                                spec.name(),
                                sampleArguments(contract.expectedPathProperty()),
                                List.of("src/a.ts")
                        )
                        : extract(spec.name(), sampleArguments(contract.expectedPathProperty()));
                case MUTATE, DELETE -> extractWorkspaceMutation(
                        spec.name(),
                        sampleArguments(contract.expectedPathProperty()),
                        List.of("src/a.ts")
                );
            };

            assertEquals(List.of("src/a.ts"), extracted.paths(),
                    "提取表未覆盖工具 " + spec.name() + "，折叠摘要会静默丢失该工具的文件路径");
            assertEquals(contract.expectedEffect(), extracted.effect(),
                    spec.name() + " 的作用类别归类错误，摘要会把路径写进错误的栏目");
        }
    }

    @Test
    void batchWriteNestedPathFieldMustMatchRecordComponent() {
        // 嵌套字段名来自记录组件名，记录组件名一定写入字节码，可直接反射比对。
        assertEquals("relativeFilePath",
                FileBatchWriteTool.FileWrite.class.getRecordComponents()[0].getName(),
                "FileWrite 首个组件已改名，ToolRoundPathExtractor 的嵌套字段名需同步");
    }

    @Test
    void batchToolsMustExtractEveryPathNotOnlyTheFirst() {
        assertEquals(List.of("src/a.ts", "src/b.ts"), extractWorkspaceMutation(
                "writeFiles",
                "{\"files\":[{\"relativeFilePath\":\"src/a.ts\",\"content\":\"x\"},"
                        + "{\"relativeFilePath\":\"src/b.ts\",\"content\":\"y\"}]}",
                List.of("src/a.ts", "src/b.ts")
        ).paths());

        assertEquals(List.of("src/a.ts", "src/b.ts"), extractBatchRead(
                "readMultipleFiles",
                "{\"relativeFilePaths\":[\"src/a.ts\",\"src/b.ts\"],\"maxCharsPerFile\":100}",
                List.of("src/a.ts", "src/b.ts")
        ).paths());
    }

    @Test
    void batchReadEvidenceMustBeLimitedToPathsInTheOriginalRequest() {
        ToolRoundPathExtractor.ExtractedPaths extracted = extractBatchRead(
                "readMultipleFiles",
                "{\"relativeFilePaths\":[\"src/requested.ts\"],\"maxCharsPerFile\":100}",
                List.of("src/foreign.ts")
        );

        assertTrue(extracted.paths().isEmpty(),
                "持久化证据不得注入本次工具请求之外的路径事实");
    }

    @Test
    void mutationEvidenceMustBeLimitedToCanonicalPathsInTheOriginalRequest() {
        ToolRoundPathExtractor.ExtractedPaths polluted = extractWorkspaceMutation(
                "writeFile",
                "{\"relativeFilePath\":\"src/requested.ts\",\"content\":\"x\"}",
                List.of("src/foreign.ts")
        );
        ToolRoundPathExtractor.ExtractedPaths canonical = extractWorkspaceMutation(
                "writeFile",
                "{\"relativeFilePath\":\"src\\\\App.vue\",\"content\":\"x\"}",
                List.of("src/App.vue")
        );

        assertTrue(polluted.paths().isEmpty(),
                "持久化证据不得注入本次工具请求之外的 mutation 路径");
        assertEquals(ToolRoundPathExtractor.MutationEvidenceState.UNKNOWN,
                polluted.mutationEvidenceState(),
                "证据与请求不相交时只能标记为未知，不能伪装成已确认 no-op");
        assertEquals(List.of("src/App.vue"), canonical.paths(),
                "请求与执行结果必须使用同一套相对路径规范化规则后再取交集");
        assertEquals(ToolRoundPathExtractor.MutationEvidenceState.CONFIRMED_PATHS,
                canonical.mutationEvidenceState());
    }

    @Test
    void legacyMutationResultWithoutEvidenceMustFailClosed() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("legacy-write")
                .name("writeFile")
                .arguments("{\"relativeFilePath\":\"src/legacy.ts\",\"content\":\"x\"}")
                .build();

        ToolRoundPathExtractor.ExtractedPaths extracted = extractor.extract(
                request,
                ToolExecutionResultMessage.from(request.id(), request.name(), "旧格式成功文本")
        );

        assertTrue(extracted.paths().isEmpty(),
                "旧消息没有平台变更证据时不能从请求参数猜测已落盘事实");
        assertEquals(ToolRoundPathExtractor.MutationEvidenceState.UNKNOWN,
                extracted.mutationEvidenceState(),
                "缺失证据与明确 no-op 必须保持不同状态，折叠后仍需提示核验");
    }

    @Test
    void explicitNoOpAndNonMutatingPackageActionsMustKeepDistinctStates() {
        ToolRoundPathExtractor.ExtractedPaths noOp = extractWorkspaceMutation(
                "writeFile",
                "{\"relativeFilePath\":\"src/App.vue\",\"content\":\"same\"}",
                List.of()
        );
        ToolRoundPathExtractor.ExtractedPaths packageQuery = extract(
                "managePackageJson",
                "{\"action\":\"getPackageJson\"}",
                "package.json 内容"
        );
        ToolRoundPathExtractor.ExtractedPaths deferredInstall = extract(
                "managePackageJson",
                "{\"action\":\"installDependencies\"}",
                "依赖安装已移交构建阶段"
        );

        assertEquals(ToolRoundPathExtractor.MutationEvidenceState.CONFIRMED_NOOP,
                noOp.mutationEvidenceState());
        assertEquals(ToolRoundPathExtractor.MutationEvidenceState.NOT_APPLICABLE,
                packageQuery.mutationEvidenceState());
        assertEquals(ToolRoundPathExtractor.MutationEvidenceState.NOT_APPLICABLE,
                deferredInstall.mutationEvidenceState());
    }

    @Test
    void packageManagerMayOnlyReportItsTwoResolvedPackageJsonLocations() {
        ToolRoundPathExtractor.ExtractedPaths frontendPackage = extractWorkspaceMutation(
                "managePackageJson",
                "{\"action\":\"addDependency\",\"packageName\":\"marked\"}",
                List.of("frontend/package.json")
        );
        ToolRoundPathExtractor.ExtractedPaths polluted = extractWorkspaceMutation(
                "managePackageJson",
                "{\"action\":\"addDependency\",\"packageName\":\"marked\"}",
                List.of("src/foreign.json")
        );

        assertEquals(List.of("frontend/package.json"), frontendPackage.paths());
        assertTrue(polluted.paths().isEmpty());
    }

    @Test
    void legacySuccessWithoutExplicitErrorFlagMustStillBeRecognized() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("legacy-1")
                .name("readFile")
                .arguments("{\"relativeFilePath\":\"src/legacy.ts\"}")
                .build();

        ToolRoundPathExtractor.ExtractedPaths extracted = extractor.extract(
                request,
                ToolExecutionResultMessage.from(request.id(), request.name(), "legacy-content")
        );

        assertEquals(List.of("src/legacy.ts"), extracted.paths());
    }

    @Test
    void malformedOrUnknownInputMustDegradeToEmptyInsteadOfThrowing() {
        // 参数由模型生成，必然出现畸形报文；折叠不能因此中断整条生成链路。
        assertTrue(extract("readFile", "{不是合法 JSON").paths().isEmpty());
        assertTrue(extract("readFile", "[\"数组不是对象\"]").paths().isEmpty());
        assertTrue(extract("readFile", "{\"relativeFilePath\":123}").paths().isEmpty());
        assertTrue(extract("readFile", "{\"relativeFilePath\":\"   \"}").paths().isEmpty());
        assertTrue(extract("readFile", "{}").paths().isEmpty());
        assertTrue(extract("readFile", "").paths().isEmpty());
        assertTrue(extract("writeFiles", "{\"files\":[\"字符串不是对象\"]}").paths().isEmpty());
        assertTrue(extract("writeFiles", "{\"files\":{}}").paths().isEmpty());
        assertTrue(extract("someUnregisteredTool", "{\"relativeFilePath\":\"a.ts\"}")
                .paths().isEmpty());
        assertTrue(extractor.extract(
                null,
                ToolExecutionResultMessage.from("call-1", "readFile", "成功")
        ).paths().isEmpty());
        assertTrue(extract(null, "{\"relativeFilePath\":\"a.ts\"}").paths().isEmpty());
    }

    private ToolRoundPathExtractor.ExtractedPaths extract(String tool, String arguments) {
        return extract(tool, arguments, "成功");
    }

    private ToolRoundPathExtractor.ExtractedPaths extractBatchRead(
            String tool,
            String arguments,
            List<String> successfulPaths
    ) {
        return extract(
                tool,
                arguments,
                ToolResultEvidence.successfulReads("批量读取完成", successfulPaths)
        );
    }

    private ToolRoundPathExtractor.ExtractedPaths extractWorkspaceMutation(
            String tool,
            String arguments,
            List<String> effectivePaths
    ) {
        return extract(
                tool,
                arguments,
                ToolResultEvidence.effectiveMutations("工作区操作完成", effectivePaths)
        );
    }

    private ToolRoundPathExtractor.ExtractedPaths extract(
            String tool,
            String arguments,
            String resultText
    ) {
        return extract(tool, arguments, TextContent.from(resultText));
    }

    private ToolRoundPathExtractor.ExtractedPaths extract(
            String tool,
            String arguments,
            TextContent resultContent
    ) {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1").name(tool).arguments(arguments).build();
        ToolExecutionResult executionResult = ToolExecutionResult.builder()
                .result(resultContent)
                .resultContents(List.of(resultContent))
                .build();
        ToolExecutionResultMessage result =
                ToolResultEvidence.toMessage(request, executionResult);
        return extractor.extract(request, result);
    }

    /** 走 LangChain4j 的真实推导链路取协议定义，而非复制一份工具名字面量。 */
    private static ToolSpecification specificationOf(Class<?> toolClass) {
        Method toolMethod = Stream.of(toolClass.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        toolClass.getSimpleName() + " 上找不到 @Tool 方法"));
        return ToolSpecifications.toolSpecificationFrom(toolMethod);
    }

    private static String sampleArguments(String pathProperty) {
        return switch (pathProperty) {
            case "relativeFilePaths" -> "{\"relativeFilePaths\":[\"src/a.ts\"]}";
            case "files" ->
                    "{\"files\":[{\"relativeFilePath\":\"src/a.ts\",\"content\":\"x\"}]}";
            default -> "{\"" + pathProperty + "\":\"src/a.ts\"}";
        };
    }
}
