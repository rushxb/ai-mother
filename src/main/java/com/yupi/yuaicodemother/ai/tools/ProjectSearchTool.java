package com.yupi.yuaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 项目搜索工具
 */
@Slf4j
@Component
public class ProjectSearchTool extends BaseTool {

    private static final Set<String> IGNORED_NAMES = Set.of(
            ".git", ".idea", ".vscode", "node_modules", "dist", "build", "target", "coverage"
    );

    private static final Set<String> SEARCHABLE_EXTENSIONS = Set.of(
            "vue", "js", "ts", "jsx", "tsx", "json", "css", "scss", "less", "html", "md", "txt", "yml", "yaml"
    );

    private static final int MAX_RESULTS = 20;
    private static final int CONTEXT_RADIUS = 2;
    private static final int MAX_FILE_SIZE = 512 * 1024;

    @Tool("按文件名或文本内容搜索当前项目，适合在排查问题、定位组件、定位路由、查找变量和引用时使用。")
    public String searchProject(
            @P("搜索关键词，支持按文件名或文件内容模糊匹配")
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
            File searchRootFile = searchRoot.toFile();
            if (!searchRootFile.exists() || !searchRootFile.isDirectory()) {
                return "错误：搜索目录不存在 - " + relativeDirPath;
            }
            Set<String> extensionFilter = parseExtensions(extensions);
            List<SearchHit> hits = new ArrayList<>();
            try (Stream<Path> pathStream = Files.walk(searchRoot)) {
                List<File> files = pathStream
                        .filter(Files::isRegularFile)
                        .map(Path::toFile)
                        .filter(this::shouldIncludeFile)
                        .sorted(Comparator.comparing(File::getAbsolutePath))
                        .toList();
                for (File file : files) {
                    collectHits(searchRoot, file, keyword, extensionFilter, hits);
                    if (hits.size() >= MAX_RESULTS) {
                        break;
                    }
                }
            }
            if (hits.isEmpty()) {
                return "未找到与关键词相关的文件或内容";
            }
            hits.sort(Comparator.comparing(SearchHit::filePath).thenComparing(hit -> hit.lineNumber == null ? 0 : hit.lineNumber));
            StringBuilder builder = new StringBuilder();
            builder.append("搜索关键词: ").append(keyword).append('\n');
            builder.append("命中数量: ").append(hits.size()).append('\n');
            for (SearchHit hit : hits) {
                builder.append("\n[命中]\n")
                        .append("文件: ").append(hit.filePath()).append('\n');
                if (hit.lineNumber() != null) {
                    builder.append("行号: ").append(hit.lineNumber()).append('\n');
                } else {
                    builder.append("类型: 文件名匹配").append('\n');
                }
                builder.append("内容:\n").append(hit.preview()).append('\n');
            }
            return builder.toString().trim();
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("项目搜索失败，keyword: {}", keyword, e);
            return "项目搜索失败: " + e.getMessage();
        }
    }

    private void collectHits(Path rootPath, File file, String keyword, Set<String> extensionFilter, List<SearchHit> hits) {
        if (hits.size() >= MAX_RESULTS) {
            return;
        }
        String ext = FileUtil.extName(file).toLowerCase();
        if (!extensionFilter.isEmpty() && !extensionFilter.contains(ext)) {
            return;
        }
        String relativePath = rootPath.relativize(file.toPath()).toString().replace(File.separator, "/");
        if (relativePath.toLowerCase().contains(keyword.toLowerCase())) {
            hits.add(new SearchHit(relativePath, null, "文件名命中: " + relativePath));
            if (hits.size() >= MAX_RESULTS) {
                return;
            }
        }
        if (file.length() > MAX_FILE_SIZE || !SEARCHABLE_EXTENSIONS.contains(ext)) {
            return;
        }
        List<String> lines = FileUtil.readLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!StrUtil.containsIgnoreCase(line, keyword)) {
                continue;
            }
            hits.add(new SearchHit(relativePath, i + 1, buildPreview(lines, i)));
            if (hits.size() >= MAX_RESULTS) {
                return;
            }
        }
    }

    private String buildPreview(List<String> lines, int index) {
        int start = Math.max(0, index - CONTEXT_RADIUS);
        int end = Math.min(lines.size() - 1, index + CONTEXT_RADIUS);
        StringBuilder builder = new StringBuilder();
        for (int i = start; i <= end; i++) {
            builder.append(i + 1)
                    .append(": ")
                    .append(lines.get(i))
                    .append('\n');
        }
        return builder.toString().trim();
    }

    private boolean shouldIncludeFile(File file) {
        Path path = file.toPath();
        for (Path part : path) {
            if (IGNORED_NAMES.contains(part.toString())) {
                return false;
            }
        }
        return true;
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

    private record SearchHit(String filePath, Integer lineNumber, String preview) {
    }
}
