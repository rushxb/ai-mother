package com.yupi.yuaicodemother.langgraph4j.node;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.langgraph4j.ai.CodeQualityCheckService;
import com.yupi.yuaicodemother.langgraph4j.model.QualityResult;
import com.yupi.yuaicodemother.langgraph4j.state.WorkflowContext;
import com.yupi.yuaicodemother.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 代码质量检查节点
 */
@Slf4j
public class CodeQualityCheckNode {

    public static AsyncNodeAction<MessagesState<String>> create() {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 代码质量检查");
            String generatedCodeDir = context.getGeneratedCodeDir();
            QualityResult qualityResult;
            try {
                // 1. 读取并拼接代码文件内容
                String codeContent = readAndConcatenateCodeFiles(generatedCodeDir);
                if (StrUtil.isBlank(codeContent)) {
                    log.warn("未找到可检查的代码文件");
                    qualityResult = QualityResult.builder()
                            .isValid(false)
                            .errors(List.of("未找到可检查的代码文件"))
                            .suggestions(List.of("请确保代码生成成功"))
                            .build();
                } else {
                    QualityResult lightQualityResult = runLightQualityCheck(generatedCodeDir);
                    if (Boolean.FALSE.equals(lightQualityResult.getIsValid())) {
                        qualityResult = lightQualityResult;
                        log.warn("轻量代码质量检查未通过，跳过 AI 深度质检: {}", qualityResult.getErrors());
                    } else if (shouldSkipAiQualityCheck(codeContent)) {
                        qualityResult = lightQualityResult;
                        log.info("轻量代码质量检查通过，代码规模较小，跳过 AI 深度质检");
                    } else {
                        CodeQualityCheckService qualityCheckService = SpringContextUtil.getBean(CodeQualityCheckService.class);
                        qualityResult = qualityCheckService.checkCodeQuality(codeContent);
                        log.info("AI 深度代码质量检查完成 - 是否通过: {}", qualityResult.getIsValid());
                    }
                }
            } catch (Exception e) {
                log.error("代码质量检查异常: {}", e.getMessage(), e);
                qualityResult = QualityResult.builder()
                        .isValid(true) // 异常直接跳到下一个步骤
                        .build();
            }
            // 3. 更新状态
            context.setCurrentStep("代码质量检查");
            context.setQualityResult(qualityResult);
            return WorkflowContext.saveContext(context);
        });
    }

    /**
     * 需要检查的文件扩展名
     */
    private static final List<String> CODE_EXTENSIONS = Arrays.asList(
            ".html", ".htm", ".css", ".js", ".json", ".vue", ".ts", ".jsx", ".tsx"
    );
    private static final int AI_QUALITY_CHECK_MIN_CHARS = 20000;

    /**
     * 读取并拼接代码目录下的所有代码文件
     */
    private static String readAndConcatenateCodeFiles(String codeDir) {
        if (StrUtil.isBlank(codeDir)) {
            return "";
        }
        File directory = new File(codeDir);
        if (!directory.exists() || !directory.isDirectory()) {
            log.error("代码目录不存在或不是目录: {}", codeDir);
            return "";
        }
        StringBuilder codeContent = new StringBuilder();
        codeContent.append("# 项目文件结构和代码内容\n\n");
        // 使用 Hutool 的 walkFiles 方法遍历所有文件
        FileUtil.walkFiles(directory, file -> {
            // 过滤条件：跳过隐藏文件、特定目录下的文件、非代码文件
            if (shouldSkipFile(file, directory)) {
                return;
            }
            if (isCodeFile(file)) {
                String relativePath = FileUtil.subPath(directory.getAbsolutePath(), file.getAbsolutePath());
                codeContent.append("## 文件: ").append(relativePath).append("\n\n");
                String fileContent = FileUtil.readUtf8String(file);
                codeContent.append(fileContent).append("\n\n");
            }
        });
        return codeContent.toString();
    }

    private static boolean shouldSkipAiQualityCheck(String codeContent) {
        return codeContent.length() < AI_QUALITY_CHECK_MIN_CHARS;
    }

    private static QualityResult runLightQualityCheck(String codeDir) {
        File directory = new File(codeDir);
        List<String> errors = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        FileUtil.walkFiles(directory, file -> {
            if (shouldSkipFile(file, directory) || !isCodeFile(file)) {
                return;
            }
            String relativePath = FileUtil.subPath(directory.getAbsolutePath(), file.getAbsolutePath());
            String fileName = file.getName().toLowerCase(Locale.ROOT);
            String fileContent = FileUtil.readUtf8String(file);
            if (fileContent.contains("<<<<<<<") || fileContent.contains("=======") || fileContent.contains(">>>>>>>")) {
                errors.add(relativePath + " 存在未解决的合并冲突标记");
                suggestions.add("移除冲突标记并保留正确代码");
            }
            if ((fileName.endsWith(".vue") || fileName.endsWith(".html"))
                    && fileContent.contains("v-html")
                    && !fileContent.toLowerCase(Locale.ROOT).contains("sanitize")) {
                errors.add(relativePath + " 使用 v-html 但未发现 sanitize 处理");
                suggestions.add("对 v-html 内容做可信来源限制或 sanitize 处理");
            }
            if ((fileName.endsWith(".js") || fileName.endsWith(".ts") || fileName.endsWith(".jsx") || fileName.endsWith(".tsx") || fileName.endsWith(".vue"))
                    && fileContent.contains("innerHTML")
                    && !fileContent.toLowerCase(Locale.ROOT).contains("sanitize")) {
                errors.add(relativePath + " 使用 innerHTML 但未发现 sanitize 处理");
                suggestions.add("避免直接写入 innerHTML，必要时先 sanitize");
            }
            if (fileContent.toLowerCase(Locale.ROOT).contains("document.createelement('script')")
                    || fileContent.toLowerCase(Locale.ROOT).contains("document.createelement(\"script\")")) {
                errors.add(relativePath + " 动态创建 script 标签");
                suggestions.add("移除动态脚本注入或改为静态、安全的资源加载方式");
            }
        });
        return QualityResult.builder()
                .isValid(errors.isEmpty())
                .errors(errors)
                .suggestions(suggestions)
                .build();
    }

    /**
     * 判断是否应该跳过此文件
     */
    private static boolean shouldSkipFile(File file, File rootDir) {
        String relativePath = FileUtil.subPath(rootDir.getAbsolutePath(), file.getAbsolutePath());
        // 跳过隐藏文件
        if (file.getName().startsWith(".")) {
            return true;
        }
        // 跳过特定目录下的文件
        return relativePath.contains("node_modules" + File.separator) ||
                relativePath.contains("dist" + File.separator) ||
                relativePath.contains("target" + File.separator) ||
                relativePath.contains(".git" + File.separator);
    }

    /**
     * 判断是否是需要检查的代码文件
     */
    private static boolean isCodeFile(File file) {
        String fileName = file.getName().toLowerCase();
        return CODE_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }
}