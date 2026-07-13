package com.rush.rushaicodemother.ai.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticSearchHit;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目搜索工具
 */
@Slf4j
@Component
public class ProjectSearchTool extends BaseTool {

    private static final int MAX_RESULTS = 20;

    private final WorkspaceSemanticIndexService semanticIndexService;

    public ProjectSearchTool() {
        this(new WorkspaceSemanticIndexService());
    }

    @Autowired
    public ProjectSearchTool(WorkspaceSemanticIndexService semanticIndexService) {
        this.semanticIndexService = semanticIndexService;
    }

    @Tool("按文件名、符号或文本内容搜索当前项目，适合在排查问题、定位组件、定位路由、查找变量和引用时使用。")
    public String searchProject(
            @P("搜索关键词，支持按文件名、符号或文件内容模糊匹配")
            String keyword,
            @P("可选，限制搜索的文件扩展名，如 vue,ts,js；多个值用英文逗号分隔")
            String extensions,
            @P("可选，指定相对目录，为空则搜索整个项目")
            String relativeDirPath,
            @ToolMemoryId Long appId
    ) {
        if (StrUtil.isBlank(keyword)) {
            return "错误：搜索关键词不能为空";
        }
        try {
            Path searchRoot = ToolPathSupport.resolvePath(relativeDirPath, appId);
            if (!searchRoot.toFile().exists() || !searchRoot.toFile().isDirectory()) {
                return "错误：搜索目录不存在 - " + relativeDirPath;
            }
            Set<String> extensionFilter = parseExtensions(extensions);
            List<WorkspaceSemanticSearchHit> hits = semanticIndexService.search(
                    searchRoot,
                    keyword,
                    extensionFilter,
                    MAX_RESULTS
            );
            if (hits.isEmpty()) {
                return "未找到与关键词相关的文件或内容";
            }
            StringBuilder builder = new StringBuilder();
            builder.append("搜索关键词: ").append(keyword).append('\n');
            builder.append("索引模式: semantic_index").append('\n');
            builder.append("命中数量: ").append(hits.size()).append('\n');
            for (WorkspaceSemanticSearchHit hit : hits) {
                builder.append("\n[命中]\n")
                        .append("文件: ").append(hit.relativePath()).append('\n')
                        .append("类型: ").append(hit.matchType()).append('\n')
                        .append("来源: ").append(hit.recallSource()).append('\n')
                        .append("分数: ").append(hit.score()).append('\n')
                        .append("匹配词: ").append(hit.matchedTerms()).append('\n')
                        .append("匹配符号: ").append(hit.matchedSymbols()).append('\n')
                        .append("内容:\n").append(hit.preview()).append('\n');
            }
            return builder.toString().trim();
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("项目搜索失败，keyword: {}", keyword, e);
            return "项目搜索失败: " + e.getMessage();
        }
    }

    private Set<String> parseExtensions(String extensions) {
        if (StrUtil.isBlank(extensions)) {
            return Set.of();
        }
        return Arrays.stream(extensions.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .map(ext -> ext.startsWith(".") ? ext.substring(1) : ext)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    @Override
    public String getToolName() {
        return "searchProject";
    }

    @Override
    public String getDisplayName() {
        return "项目搜索";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return String.format("[工具调用] %s %s", getDisplayName(), arguments.getStr("keyword"));
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments, String toolResult) {
        return generateToolExecutedResult(arguments) + "\n" + summarizeResult(toolResult, 320);
    }

    private String summarizeResult(String toolResult, int maxChars) {
        if (StrUtil.isBlank(toolResult)) {
            return "";
        }
        String normalized = toolResult.replace("\r", " ").replace("\n", " ").trim();
        return StrUtil.sub(normalized, 0, Math.min(normalized.length(), maxChars));
    }
}
