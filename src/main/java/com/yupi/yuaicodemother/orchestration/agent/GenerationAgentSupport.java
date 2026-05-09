package com.yupi.yuaicodemother.orchestration.agent;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 多智能体节点共享的辅助逻辑。
 */
@Component
public class GenerationAgentSupport {

    private static final int MAX_PROJECT_INDEX_FILES = 120;
    private static final int MAX_MODEL_CONTEXT_FILE_CHARS = 12000;
    private static final Set<String> INDEXABLE_SOURCE_EXTENSIONS = Set.of(
            "vue", "js", "ts", "jsx", "tsx", "css", "scss", "less", "json", "svg", "md", "html"
    );

    public boolean isComplexRequest(String userMessage) {
        String normalized = StrUtil.blankToDefault(userMessage, "").toLowerCase();
        return List.of(
                "vue", "组件", "路由", "router", "模块", "后台", "管理系统", "登录", "注册",
                "api", "接口", "状态管理", "pinia", "图表", "表单", "多页面", "工作台", "dashboard"
        ).stream().anyMatch(normalized::contains);
    }

    public List<String> inferModules(String userMessage, String projectContext) {
        String normalized = (StrUtil.blankToDefault(userMessage, "") + "\n" + StrUtil.blankToDefault(projectContext, "")).toLowerCase();
        List<String> modules = new ArrayList<>();
        if (normalized.contains("登录") || normalized.contains("注册") || normalized.contains("auth")) {
            modules.add("auth");
        }
        if (normalized.contains("dashboard") || normalized.contains("工作台") || normalized.contains("首页")) {
            modules.add("dashboard");
        }
        if (normalized.contains("列表") || normalized.contains("table") || normalized.contains("管理")) {
            modules.add("management");
        }
        if (normalized.contains("图表") || normalized.contains("chart") || normalized.contains("统计")) {
            modules.add("analytics");
        }
        if (normalized.contains("设置") || normalized.contains("setting")) {
            modules.add("settings");
        }
        if (modules.isEmpty()) {
            modules.add("core-app");
        }
        return modules.stream().distinct().toList();
    }

    public String buildProjectContext(App app, CodeGenTypeEnum codeGenTypeEnum, File rootDir) {
        if (app == null || codeGenTypeEnum == null || rootDir == null || !rootDir.exists()) {
            return "";
        }
        String projectIndex = buildProjectIndex(rootDir);
        String keyFiles = switch (codeGenTypeEnum) {
            case HTML -> readSingleFileContext(rootDir, "index.html");
            case MULTI_FILE -> readMultiFileContext(rootDir, List.of("index.html", "style.css", "script.js"));
            case VUE_PROJECT -> readMultiFileContext(rootDir, List.of(
                    "package.json", "src/App.vue", "src/main.js", "src/main.ts", "src/router/index.ts", "index.html"
            ));
        };
        if (StrUtil.isBlank(projectIndex)) {
            return keyFiles;
        }
        if (StrUtil.isBlank(keyFiles)) {
            return projectIndex;
        }
        return projectIndex + "\n\n" + keyFiles;
    }

    private String buildProjectIndex(File rootDir) {
        List<String> indexedFiles = new ArrayList<>();
        FileUtil.walkFiles(rootDir, file -> {
            if (indexedFiles.size() >= MAX_PROJECT_INDEX_FILES || file.isDirectory()) {
                return;
            }
            String relativePath = relativize(rootDir, file);
            if (shouldIndex(relativePath)) {
                indexedFiles.add(relativePath);
            }
        });
        if (indexedFiles.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("项目索引:\n");
        indexedFiles.stream()
                .sorted(Comparator.naturalOrder())
                .limit(MAX_PROJECT_INDEX_FILES)
                .forEach(path -> builder.append("- ").append(path).append('\n'));
        return builder.toString().trim();
    }

    private boolean shouldIndex(String relativePath) {
        if (StrUtil.isBlank(relativePath)) {
            return false;
        }
        String extension = FileUtil.extName(relativePath).toLowerCase();
        if (relativePath.startsWith("src/") || relativePath.startsWith("public/")) {
            return INDEXABLE_SOURCE_EXTENSIONS.contains(extension);
        }
        return Set.of("package.json", "vite.config.js", "vite.config.ts", "index.html",
                "tsconfig.json", "tsconfig.app.json").contains(relativePath);
    }

    private String readSingleFileContext(File rootDir, String relativePath) {
        File file = new File(rootDir, relativePath);
        if (!file.exists() || !file.isFile()) {
            return "";
        }
        String content = FileUtil.readString(file, StandardCharsets.UTF_8);
        return "当前文件: " + relativePath + "\n```html\n" + truncate(content) + "\n```";
    }

    private String readMultiFileContext(File rootDir, List<String> relativePaths) {
        List<String> sections = new ArrayList<>();
        for (String relativePath : relativePaths) {
            File file = new File(rootDir, relativePath);
            if (!file.exists() || !file.isFile()) {
                continue;
            }
            String extension = FileUtil.extName(file);
            String content = FileUtil.readString(file, StandardCharsets.UTF_8);
            sections.add("当前文件: " + relativePath + "\n```" + extension + "\n" + truncate(content) + "\n```");
        }
        return String.join("\n\n", sections);
    }

    private String truncate(String content) {
        if (content == null || content.length() <= MAX_MODEL_CONTEXT_FILE_CHARS) {
            return StrUtil.blankToDefault(content, "");
        }
        return content.substring(0, MAX_MODEL_CONTEXT_FILE_CHARS)
                + "\n<!-- 文件内容过长，以上为截断后的前 "
                + MAX_MODEL_CONTEXT_FILE_CHARS
                + " 个字符 -->";
    }

    private String relativize(File rootDir, File file) {
        Path rootPath = rootDir.toPath().toAbsolutePath().normalize();
        Path filePath = file.toPath().toAbsolutePath().normalize();
        return rootPath.relativize(filePath).toString().replace(File.separator, "/");
    }
}
