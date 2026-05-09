package com.yupi.yuaicodemother.orchestration.agent;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.dag.AgentNodeResult;
import com.yupi.yuaicodemother.orchestration.dag.GenerationAgentContext;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.yupi.yuaicodemother.constant.AppConstant.CODE_OUTPUT_ROOT_DIR;

/**
 * Context：提取项目上下文。
 */
@Component
public class ContextAgentNode extends BaseGenerationAgentNode {

    private final GenerationAgentSupport support;

    public ContextAgentNode(GenerationAgentSupport support) {
        super("context", "Context", "context", List.of());
        this.support = support;
    }

    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        App app = context.getRequest().app();
        CodeGenTypeEnum currentType = context.getRequest().currentType();
        String projectContext = "";
        int fileCount = 0;
        if (app != null && app.getId() != null && context.getRequest().hasGeneratedCode()) {
            File rootDir = new File(CODE_OUTPUT_ROOT_DIR + File.separator + app.getCodeGenType() + "_" + app.getId());
            if (rootDir.exists() && rootDir.isDirectory()) {
                projectContext = support.buildProjectContext(app, currentType, rootDir);
                fileCount = FileUtil.loopFiles(rootDir, file -> file.isFile()).size();
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectContext", StrUtil.blankToDefault(projectContext, ""));
        payload.put("hasGeneratedCode", context.getRequest().hasGeneratedCode());
        payload.put("indexedFileCount", fileCount);
        GenerationArtifact artifact = GenerationArtifact.of("context_summary", "Context", "项目上下文", payload);
        String summary = StrUtil.isBlank(projectContext)
                ? "未发现可复用项目上下文，将按新项目处理"
                : "已提取项目索引和关键文件上下文";
        return AgentNodeResult.of(summary, List.of(artifact), Map.of("indexedFileCount", fileCount));
    }
}
