package com.rush.rushaicodemother.orchestration.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.ai.tools.FileBatchWriteTool;
import com.rush.rushaicodemother.ai.tools.FileDeleteTool;
import com.rush.rushaicodemother.ai.tools.FileModifyTool;
import com.rush.rushaicodemother.ai.tools.FileReadTool;
import com.rush.rushaicodemother.ai.tools.FileWriteTool;
import com.rush.rushaicodemother.ai.tools.ReadMultipleFilesTool;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
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
            ToolRoundPathExtractor.ExtractedPaths extracted = extract(
                    spec.name(), sampleArguments(contract.expectedPathProperty()));

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
        assertEquals(List.of("src/a.ts", "src/b.ts"), extract("writeFiles",
                "{\"files\":[{\"relativeFilePath\":\"src/a.ts\",\"content\":\"x\"},"
                        + "{\"relativeFilePath\":\"src/b.ts\",\"content\":\"y\"}]}").paths());

        assertEquals(List.of("src/a.ts", "src/b.ts"), extract("readMultipleFiles",
                "{\"relativeFilePaths\":[\"src/a.ts\",\"src/b.ts\"],\"maxCharsPerFile\":100}")
                .paths());
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
        assertTrue(extractor.extract(null).paths().isEmpty());
        assertTrue(extract(null, "{\"relativeFilePath\":\"a.ts\"}").paths().isEmpty());
    }

    private ToolRoundPathExtractor.ExtractedPaths extract(String tool, String arguments) {
        return extractor.extract(ToolExecutionRequest.builder()
                .id("call-1").name(tool).arguments(arguments).build());
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
