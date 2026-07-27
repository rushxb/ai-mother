package com.rush.rushaicodemother.ai.tools;

import dev.langchain4j.agent.tool.Tool;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ClassUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具管理器
 * 统一管理所有工具，提供根据名称获取工具的功能
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolManager {

    /**
     * 工具名称到工具实例的映射
     */
    private final Map<String, BaseTool> toolMap = new HashMap<>();

    /** Spring 注入的全部工具实现。 */
    private final BaseTool[] tools;

    /**
     * 初始化工具映射
     */
    @PostConstruct
    public void initTools() {
        for (BaseTool tool : tools) {
            if (tool == null || tool.getToolName() == null || tool.getToolName().isBlank()
                    || tool.getRiskLevel() == null) {
                throw new IllegalStateException("AI tool registration metadata is incomplete");
            }
            validateExposedToolName(tool);
            if (tool.getRiskLevel() == ToolRiskLevel.DESTRUCTIVE
                    && !(tool instanceof ApprovalGatedTool)) {
                throw new IllegalStateException(
                        "Destructive AI tool must implement central approval contract: " + tool.getToolName());
            }
            BaseTool duplicate = toolMap.putIfAbsent(tool.getToolName(), tool);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate AI tool name: " + tool.getToolName());
            }
            log.info("注册工具: {} -> {}", tool.getToolName(), tool.getDisplayName());
        }
        log.info("工具管理器初始化完成，共注册 {} 个工具", toolMap.size());
    }

    private void validateExposedToolName(BaseTool tool) {
        Class<?> toolClass = ClassUtils.getUserClass(tool);
        List<Method> exposedMethods = Arrays.stream(toolClass.getMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .toList();
        if (exposedMethods.size() != 1) {
            throw new IllegalStateException(
                    "AI 工具必须且只能暴露一个 @Tool 方法: " + toolClass.getSimpleName());
        }
        Method method = exposedMethods.getFirst();
        Tool annotation = method.getAnnotation(Tool.class);
        String exposedName = annotation.name().isBlank() ? method.getName() : annotation.name();
        if (!tool.getToolName().equals(exposedName)) {
            throw new IllegalStateException(
                    "AI 工具注册名与模型可见名称不一致: " + toolClass.getSimpleName());
        }
    }


    /**
     * 根据工具名称获取工具实例
     *
     * @param toolName 工具英文名称
     * @return 工具实例
     */
    public BaseTool getTool(String toolName) {
        return toolMap.get(toolName);
    }

    /**
     * 获取已注册的工具集合
     *
     * @return 工具实例集合
     */
    public BaseTool[] getAllTools() {
        return tools;
    }

    /**
     * 项目生成阶段使用的轻量工具集。
     * 生成过程中不暴露构建和运行类工具，避免模型在中途重复触发耗时或有副作用的命令。
     */
    public BaseTool[] getToolsForCodeGen(CodeGenTypeEnum codeGenType) {
        return Arrays.stream(tools)
                .filter(tool -> isToolAllowedForCodeGen(tool.getToolName(), codeGenType))
                .toArray(BaseTool[]::new);
    }

    /** 在构建人工智能服务和调用时都使用共享暴露决策。 */
    public boolean isToolAllowedForCodeGen(String toolName, CodeGenTypeEnum codeGenType) {
        BaseTool tool = toolMap.get(toolName);
        if (tool == null || tool.getRiskLevel() == ToolRiskLevel.EXTERNAL_SIDE_EFFECT) {
            return false;
        }
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT
                && codeGenType != CodeGenTypeEnum.BACKEND_PROJECT
                && codeGenType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return true;
        }
        if (Set.of(
                "buildVueProject",
                "runProjectCheck",
                "manageDevServer",
                "diagnosePreviewRuntime"
        ).contains(toolName)) {
            return false;
        }
        return codeGenType != CodeGenTypeEnum.BACKEND_PROJECT
                || (!"analyzeDependencyIssue".equals(toolName)
                && !"managePackageJson".equals(toolName));
    }
}
