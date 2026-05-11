package com.yupi.yuaicodemother.ai.tools;

import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具管理器
 * 统一管理所有工具，提供根据名称获取工具的功能
 */
@Slf4j
@Component
public class ToolManager {

    /**
     * 工具名称到工具实例的映射
     */
    private final Map<String, BaseTool> toolMap = new HashMap<>();

    /**
     * 自动注入所有工具
     */
    @Resource
    private BaseTool[] tools;

    /**
     * 初始化工具映射
     */
    @PostConstruct
    public void initTools() {
        for (BaseTool tool : tools) {
            toolMap.put(tool.getToolName(), tool);
            log.info("注册工具: {} -> {}", tool.getToolName(), tool.getDisplayName());
        }
        log.info("工具管理器初始化完成，共注册 {} 个工具", toolMap.size());
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
     * Vue 项目生成阶段使用的轻量工具集。
     * 生成过程中不暴露构建类工具，避免模型在中途重复触发耗时的 pnpm install / pnpm run build。
     */
    public BaseTool[] getToolsForCodeGen(CodeGenTypeEnum codeGenType) {
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT) {
            return tools;
        }
        return Arrays.stream(tools)
                .filter(tool -> !"buildVueProject".equals(tool.getToolName()))
                .filter(tool -> !"runProjectCheck".equals(tool.getToolName()))
                .filter(tool -> !"manageDevServer".equals(tool.getToolName()))
                .filter(tool -> !"diagnosePreviewRuntime".equals(tool.getToolName()))
                .toArray(BaseTool[]::new);
    }
}
