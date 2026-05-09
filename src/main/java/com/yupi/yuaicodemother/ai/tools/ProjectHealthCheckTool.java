package com.yupi.yuaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目健康检查工具
 */
@Slf4j
@Component
public class ProjectHealthCheckTool extends BaseTool {

    @Tool("对 Vue 项目的 package.json、vite 配置、入口文件、路由和 index.html 做静态健康检查，提前发现 base、alias、hash 路由、入口缺失等高频问题。")
    public String checkProjectHealth(
            @P("可选，相对项目子目录；为空则检查整个项目根目录")
            String relativeProjectPath,
            @ToolMemoryId Long appId
    ) {
        try {
            Path projectPath = ToolPathSupport.resolvePath(relativeProjectPath, appId);
            if (!projectPath.toFile().exists()) {
                return "错误：项目目录不存在";
            }
            List<String> blockers = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            List<String> passes = new ArrayList<>();
            checkPackageJson(projectPath, blockers, warnings, passes);
            checkViteConfig(projectPath, blockers, warnings, passes);
            checkEntrypoints(projectPath, blockers, warnings, passes);
            checkRouter(projectPath, warnings, passes);
            checkIndexHtml(projectPath, blockers, warnings, passes);
            return buildReport(projectPath, blockers, warnings, passes);
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("项目健康检查失败", e);
            return "项目健康检查失败: " + e.getMessage();
        }
    }

    private void checkPackageJson(Path projectPath, List<String> blockers, List<String> warnings, List<String> passes) {
        File packageJsonFile = projectPath.resolve("package.json").toFile();
        if (!packageJsonFile.exists()) {
            blockers.add("缺少 package.json。");
            return;
        }
        JSONObject packageJson = JSONUtil.parseObj(FileUtil.readString(packageJsonFile, StandardCharsets.UTF_8));
        JSONObject dependencies = defaultObject(packageJson.getJSONObject("dependencies"));
        JSONObject devDependencies = defaultObject(packageJson.getJSONObject("devDependencies"));
        JSONObject scripts = defaultObject(packageJson.getJSONObject("scripts"));
        if (!scripts.containsKey("dev")) {
            blockers.add("package.json 缺少 dev 脚本。");
        } else {
            passes.add("已存在 dev 脚本。");
        }
        if (!scripts.containsKey("build")) {
            blockers.add("package.json 缺少 build 脚本。");
        } else {
            passes.add("已存在 build 脚本。");
        }
        if (!dependencies.containsKey("vue")) {
            blockers.add("dependencies 中缺少 vue。");
        } else {
            passes.add("已声明 vue 依赖。");
        }
        if (!dependencies.containsKey("vue-router")) {
            warnings.add("dependencies 中未发现 vue-router；如果项目有多页或导航需求，需确认是否故意省略。");
        } else {
            passes.add("已声明 vue-router 依赖。");
        }
        if (!devDependencies.containsKey("vite")) {
            blockers.add("devDependencies 中缺少 vite。");
        } else {
            passes.add("已声明 vite 依赖。");
        }
        if (!devDependencies.containsKey("@vitejs/plugin-vue")) {
            blockers.add("devDependencies 中缺少 @vitejs/plugin-vue。");
        } else {
            passes.add("已声明 @vitejs/plugin-vue 依赖。");
        }
    }

    private void checkViteConfig(Path projectPath, List<String> blockers, List<String> warnings, List<String> passes) {
        File viteConfigFile = firstExistingFile(projectPath, "vite.config.js", "vite.config.ts", "vite.config.mjs");
        if (viteConfigFile == null) {
            blockers.add("缺少 vite.config.js 或 vite.config.ts。");
            return;
        }
        String content = FileUtil.readString(viteConfigFile, StandardCharsets.UTF_8);
        if (!content.contains("base: './'") && !content.contains("base:\"./\"") && !content.contains("base: \"./\"")) {
            warnings.add("vite 配置中未发现 `base: './'`，子路径部署可能出问题。");
        } else {
            passes.add("vite base 路径已配置为相对路径。");
        }
        if (!content.contains("@vitejs/plugin-vue") && !content.contains("plugin-vue")) {
            blockers.add("vite 配置中未发现 Vue 插件引入。");
        } else {
            passes.add("vite 配置包含 Vue 插件。");
        }
        if (!content.contains("alias") || !content.contains("@")) {
            warnings.add("vite 配置中未发现 `@` alias，后续若源码使用 `@/` 导入会报错。");
        } else {
            passes.add("vite 配置包含 `@` alias。");
        }
    }

    private void checkEntrypoints(Path projectPath, List<String> blockers, List<String> warnings, List<String> passes) {
        File mainJs = projectPath.resolve("src/main.js").toFile();
        File mainTs = projectPath.resolve("src/main.ts").toFile();
        if (!mainJs.exists() && !mainTs.exists()) {
            blockers.add("缺少 src/main.js 或 src/main.ts 入口文件。");
        } else {
            passes.add("入口文件存在。");
        }
        File appVue = projectPath.resolve("src/App.vue").toFile();
        if (!appVue.exists()) {
            warnings.add("未发现 src/App.vue，请确认是否采用了其它根组件结构。");
        } else {
            passes.add("根组件 App.vue 存在。");
        }
    }

    private void checkRouter(Path projectPath, List<String> warnings, List<String> passes) {
        File routerFile = firstExistingFile(projectPath, "src/router/index.js", "src/router/index.ts");
        if (routerFile == null) {
            warnings.add("未发现 router 入口文件；如果项目需要路由，请确认是否遗漏。");
            return;
        }
        String content = FileUtil.readString(routerFile, StandardCharsets.UTF_8);
        if (content.contains("createWebHistory(") && !content.contains("createWebHashHistory(")) {
            warnings.add("路由当前更像 history 模式；如果没有额外服务器配置，部署后容易出现刷新 404。");
        } else if (content.contains("createWebHashHistory(")) {
            passes.add("路由使用 hash 模式。");
        }
    }

    private void checkIndexHtml(Path projectPath, List<String> blockers, List<String> warnings, List<String> passes) {
        File indexHtml = projectPath.resolve("index.html").toFile();
        if (!indexHtml.exists()) {
            blockers.add("缺少 index.html。");
            return;
        }
        String content = FileUtil.readString(indexHtml, StandardCharsets.UTF_8);
        if (!content.contains("id=\"app\"") && !content.contains("id='app'")) {
            warnings.add("index.html 中未发现 id=app 挂载节点，请确认与 main.* 中的挂载目标一致。");
        } else {
            passes.add("index.html 包含 app 挂载节点。");
        }
    }

    private String buildReport(Path projectPath, List<String> blockers, List<String> warnings, List<String> passes) {
        StringBuilder builder = new StringBuilder();
        builder.append("项目健康检查: ").append(projectPath).append('\n');
        builder.append("阻断问题: ").append(blockers.size()).append('\n');
        builder.append("告警问题: ").append(warnings.size()).append('\n');
        builder.append("通过项: ").append(passes.size()).append('\n');
        if (!blockers.isEmpty()) {
            builder.append("\n[阻断问题]\n");
            blockers.forEach(item -> builder.append("- ").append(item).append('\n'));
        }
        if (!warnings.isEmpty()) {
            builder.append("\n[告警问题]\n");
            warnings.forEach(item -> builder.append("- ").append(item).append('\n'));
        }
        if (!passes.isEmpty()) {
            builder.append("\n[通过项]\n");
            passes.forEach(item -> builder.append("- ").append(item).append('\n'));
        }
        return builder.toString().trim();
    }

    private JSONObject defaultObject(JSONObject object) {
        return object == null ? new JSONObject() : object;
    }

    private File firstExistingFile(Path rootPath, String... relativePaths) {
        for (String relativePath : relativePaths) {
            File file = rootPath.resolve(relativePath).toFile();
            if (file.exists() && file.isFile()) {
                return file;
            }
        }
        return null;
    }

    @Override
    public String getToolName() {
        return "checkProjectHealth";
    }

    @Override
    public String getDisplayName() {
        return "项目健康检查";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return String.format("[工具调用] %s", getDisplayName());
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
