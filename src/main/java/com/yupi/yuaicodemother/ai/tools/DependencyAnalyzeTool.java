package com.yupi.yuaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ReUtil;
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
 * 依赖问题分析工具
 */
@Slf4j
@Component
public class DependencyAnalyzeTool extends BaseTool {

    @Tool("分析构建日志、lint/test 日志或报错文本，判断是否是依赖缺失、版本错误、脚本缺失、别名配置问题，并给出下一步处理建议。")
    public String analyzeDependencyIssue(
            @P("构建失败日志、lint/test 日志或运行时报错文本")
            String diagnosticLog,
            @P("可选，相对项目子目录；为空则分析项目根目录 package.json 和关键配置")
            String relativeProjectPath,
            @ToolMemoryId Long appId
    ) {
        if (StrUtil.isBlank(diagnosticLog)) {
            return "错误：诊断日志不能为空";
        }
        try {
            Path projectPath = ToolPathSupport.resolvePath(relativeProjectPath, appId);
            File projectDir = projectPath.toFile();
            if (!projectDir.exists() || !projectDir.isDirectory()) {
                return "错误：项目目录不存在 - " + relativeProjectPath;
            }
            PackageContext context = loadPackageContext(projectPath);
            List<String> findings = new ArrayList<>();
            List<String> suggestions = new ArrayList<>();
            analyzeLog(diagnosticLog, context, findings, suggestions);
            if (findings.isEmpty()) {
                findings.add("未识别出明确的依赖或脚本级问题，建议继续结合项目搜索工具、批量读取文件工具和本地构建诊断工具排查源码或配置问题。");
            }
            StringBuilder builder = new StringBuilder();
            builder.append("依赖诊断结论:\n");
            findings.forEach(item -> builder.append("- ").append(item).append('\n'));
            builder.append("\n当前 package.json 摘要:\n")
                    .append("- dependencies 数量: ").append(context.dependenciesCount()).append('\n')
                    .append("- devDependencies 数量: ").append(context.devDependenciesCount()).append('\n')
                    .append("- scripts 数量: ").append(context.scriptsCount()).append('\n');
            if (!suggestions.isEmpty()) {
                builder.append("\n建议动作:\n");
                suggestions.stream().distinct().forEach(item -> builder.append("- ").append(item).append('\n'));
            }
            return builder.toString().trim();
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("依赖问题分析失败", e);
            return "依赖问题分析失败: " + e.getMessage();
        }
    }

    private PackageContext loadPackageContext(Path projectPath) {
        Path packageJsonPath = projectPath.resolve("package.json");
        if (!packageJsonPath.toFile().exists()) {
            return new PackageContext(new JSONObject(), new JSONObject(), new JSONObject());
        }
        JSONObject packageJson = JSONUtil.parseObj(FileUtil.readString(packageJsonPath.toFile(), StandardCharsets.UTF_8));
        return new PackageContext(
                packageJson.getJSONObject("dependencies") == null ? new JSONObject() : packageJson.getJSONObject("dependencies"),
                packageJson.getJSONObject("devDependencies") == null ? new JSONObject() : packageJson.getJSONObject("devDependencies"),
                packageJson.getJSONObject("scripts") == null ? new JSONObject() : packageJson.getJSONObject("scripts")
        );
    }

    private void analyzeLog(String diagnosticLog, PackageContext context, List<String> findings, List<String> suggestions) {
        String log = diagnosticLog.replace("\r", "\n");

        String missingModule = extractFirst(log,
                "Cannot find module ['\"]([^'\"]+)['\"]",
                "Failed to resolve import ['\"]([^'\"]+)['\"]",
                "Could not resolve ['\"]([^'\"]+)['\"]");
        if (StrUtil.isNotBlank(missingModule)) {
            if (isConfiguredDependency(context, missingModule)) {
                findings.add("日志显示无法解析模块 `" + missingModule + "`，但它已经出现在 package.json 中，更可能是导入路径写错、别名失效或安装产物未同步。");
                suggestions.add("优先检查对应 import 语句是否写错，并重新执行依赖安装或构建诊断。");
            } else {
                findings.add("日志显示缺少模块 `" + missingModule + "`，package.json 中也未发现它，大概率是缺依赖。");
                suggestions.add("使用【依赖与脚本管理工具】把 `" + missingModule + "` 加入 dependencies 或 devDependencies，然后重新构建。");
            }
        }

        if (containsAny(log, "missing script:", "Missing script:")) {
            String missingScript = extractFirst(log, "(?i)missing script:\\s*([^\\s\\n]+)");
            findings.add("日志显示缺少 package script" + (StrUtil.isNotBlank(missingScript) ? " `" + missingScript + "`" : "") + "。");
            suggestions.add("使用【依赖与脚本管理工具】补充缺失的 scripts 配置。");
        }

        if (containsAny(log, "'vite' is not recognized", "vite: not found", "Cannot find package 'vite'")) {
            findings.add("日志显示 Vite 不可用，通常意味着 `vite` 未安装、lock 文件损坏或 devDependencies 缺失。");
            suggestions.add("确认 `vite` 和 `@vitejs/plugin-vue` 存在于 devDependencies，并执行 pnpm install。");
        }

        if (containsAny(log, "@vitejs/plugin-vue", "plugin-vue")) {
            findings.add("日志涉及 `@vitejs/plugin-vue`，可能是插件缺失、版本不匹配或 vite.config 配置错误。");
            suggestions.add("检查 devDependencies 中是否包含 `@vitejs/plugin-vue`，并确认 vite 配置已正确引入该插件。");
        }

        if (containsAny(log, "Failed to resolve import \"@/", "Cannot find module '@/", "Can't resolve '@/")) {
            findings.add("日志显示 `@/` 别名解析失败，问题通常出在 vite alias 配置、tsconfig 路径映射，或实际文件路径不匹配。");
            suggestions.add("优先读取 `vite.config.*`、`tsconfig.*` 和对应文件路径，确认 alias 与真实目录一致。");
        }

        if (containsAny(log, "Property '", "does not exist on type", "Cannot find name")) {
            findings.add("日志带有明显的 TypeScript 类型错误，当前问题更偏源码或类型定义，而不是纯依赖缺失。");
            suggestions.add("使用【项目搜索工具】定位类型报错位置，再用【批量读取文件工具】查看相关组件、类型声明和 API 返回结构。");
        }

        if (containsAny(log, "EJSONPARSE", "Unexpected token", "Unexpected end of JSON input")) {
            findings.add("日志提示 JSON 解析失败，常见原因是 package.json 或某个配置文件语法损坏。");
            suggestions.add("优先读取 package.json、tsconfig、vite.config 等配置文件，检查逗号、引号和括号。");
        }

        if (containsAny(log, "ERESOLVE", "Conflicting peer dependency", "peer dep missing")) {
            findings.add("日志显示依赖版本冲突或 peer dependency 不满足。");
            suggestions.add("检查冲突包的版本区间，优先统一同类生态包版本，例如 vite、vue、vue-router、插件包。");
        }
    }

    private boolean isConfiguredDependency(PackageContext context, String packageName) {
        if (StrUtil.startWith(packageName, "@/")) {
            return false;
        }
        return context.dependencies().containsKey(packageName) || context.devDependencies().containsKey(packageName);
    }

    private boolean containsAny(String log, String... patterns) {
        for (String pattern : patterns) {
            if (log.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String extractFirst(String content, String... regexList) {
        for (String regex : regexList) {
            String value = ReUtil.get(regex, content, 1);
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    @Override
    public String getToolName() {
        return "analyzeDependencyIssue";
    }

    @Override
    public String getDisplayName() {
        return "依赖问题分析";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return String.format("[工具调用] %s", getDisplayName());
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments, String toolResult) {
        if (StrUtil.isBlank(toolResult)) {
            return generateToolExecutedResult(arguments);
        }
        return generateToolExecutedResult(arguments) + "\n" + StrUtil.sub(toolResult.replace("\r", " ").replace("\n", " ").trim(), 0, 320);
    }

    private record PackageContext(JSONObject dependencies, JSONObject devDependencies, JSONObject scripts) {

        int dependenciesCount() {
            return dependencies.size();
        }

        int devDependenciesCount() {
            return devDependencies.size();
        }

        int scriptsCount() {
            return scripts.size();
        }
    }
}
