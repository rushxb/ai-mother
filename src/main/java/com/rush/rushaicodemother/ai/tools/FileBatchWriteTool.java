package com.rush.rushaicodemother.ai.tools;

import cn.hutool.json.JSONObject;
import com.rush.rushaicodemother.config.AiToolWorkspaceProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionGateway;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.model.output.structured.Description;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 在一个事务补丁中批量写入多个文件，任一文件校验失败时整批不落盘。
 */
@Slf4j
@Component
public class FileBatchWriteTool extends BaseTool {

    private final ToolExecutionGateway toolExecutionGateway;
    private final ToolWorkspaceFileService workspaceFileService;
    private final AiToolWorkspaceProperties properties;

    public FileBatchWriteTool(ToolExecutionGateway toolExecutionGateway,
                              ToolWorkspaceFileService workspaceFileService,
                              AiToolWorkspaceProperties properties) {
        this.toolExecutionGateway = toolExecutionGateway;
        this.workspaceFileService = workspaceFileService;
        this.properties = properties;
    }

    /**
 * 写入文件。
 *
 * @param files 文件
 * @param appId 应用编号
 * @return 处理后的文件文本
 */
    @Tool("原子批量写入多个项目文件。需要写入两个或更多文件时优先使用；任一文件不合法时不会写入任何文件。")
    public String writeFiles(
            @P("待写入文件列表，顺序即补丁执行顺序") List<FileWrite> files,
            @ToolMemoryId Long appId
    ) {
        try {
            PlannedBatch batch = planBatch(files, appId);
            PatchApplyResult result = toolExecutionGateway.applyPatch(
                    appId,
                    batch.projectRoot(),
                    batch.operations(),
                    "tool-write-files",
                    "write_files"
            );
            if ("applied".equals(result.status())) {
                return "批量文件写入成功：共 " + result.appliedOperationCount() + " 个文件";
            }
            return "批量文件写入失败：" + result.reason();
        } catch (ToolInputException exception) {
            return renderInputError("批量文件写入失败：", exception);
        } catch (GenerationExecutionPolicyException policyFailure) {
            throw policyFailure;
        } catch (Exception exception) {
            log.error("批量文件写入失败，appId={}, fileCount={}",
                    appId, files == null ? 0 : files.size(), LogExceptionSanitizer.sanitize(exception));
            return "批量文件写入失败，请稍后重试";
        }
    }

    /** 返回计划批次。 */
    private PlannedBatch planBatch(List<FileWrite> files, Long appId) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (files == null || files.isEmpty()) {
            throw new ToolInputException("文件列表不能为空");
        }
        if (files.size() > properties.getMaxBatchWriteFiles()) {
            throw new ToolInputException(
                    "单次最多写入 " + properties.getMaxBatchWriteFiles() + " 个文件");
        }

        long totalChars = 0;
        Path projectRoot = null;
        Set<String> normalizedPaths = new HashSet<>();
        List<PatchOperation> operations = new ArrayList<>(files.size());
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (int index = 0; index < files.size(); index++) {
            FileWrite requested = files.get(index);
            if (requested == null) {
                throw new ToolInputException("第 " + (index + 1) + " 个文件参数不能为空");
            }
            if (requested.content() == null) {
                throw new ToolInputException("第 " + (index + 1) + " 个文件内容不能为 null");
            }
            totalChars += requested.content().length();
            if (totalChars > properties.getMaxBatchWriteTotalChars()) {
                throw new ToolInputException(
                        "文件总内容超过 " + properties.getMaxBatchWriteTotalChars() + " 字符限制");
            }

            ToolWorkspaceFileService.ToolWorkspaceFile file =
                    workspaceFileService.resolveFile(appId, requested.relativeFilePath());
            if (!normalizedPaths.add(file.relativePath())) {
                throw new ToolInputException("批次中存在重复文件路径：" + file.relativePath());
            }
            if (projectRoot == null) {
                projectRoot = file.projectRoot();
            } else if (!projectRoot.equals(file.projectRoot())) {
                throw new ToolInputException("批次文件不属于同一项目工作区");
            }
            boolean exists = workspaceFileService.exists(file);
            if (exists && !workspaceFileService.isRegularFile(file)) {
                throw new ToolInputException("指定路径不是普通文件：" + file.relativePath());
            }
            operations.add(exists
                    ? PatchOperation.modify(file.relativePath(), requested.content())
                    : PatchOperation.add(file.relativePath(), requested.content()));
        }
        return new PlannedBatch(projectRoot, List.copyOf(operations));
    }

    @Override
    public ToolRiskLevel getRiskLevel() {
        return ToolRiskLevel.WRITE;
    }

    @Override
    public boolean canMutateWorkspace() {
        return true;
    }

    @Override
    public String getToolName() {
        return "writeFiles";
    }

    @Override
    public String getDisplayName() {
        return "批量写入文件";
    }

    /**
 * 将工具执行结果整理为模型可消费的文本。
 *
 * @param arguments 参数
 * @return 处理后的方法执行结果文本
 */
    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        Object files = arguments == null ? null : arguments.get("files");
        int fileCount = files instanceof Collection<?> collection ? collection.size() : 0;
        return "[工具调用] " + getDisplayName() + " " + fileCount
                + " 个文件（内容已写入工作区，可在代码面板查看）";
    }

    public record FileWrite(
            @Description("项目内相对文件路径") String relativeFilePath,
            @Description("完整文件内容") String content
    ) {
    }

    private record PlannedBatch(Path projectRoot, List<PatchOperation> operations) {
    }
}
