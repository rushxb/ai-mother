package com.yupi.yuaicodemother.langgraph4j.node;

import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.core.AiCodeGeneratorFacade;
import com.yupi.yuaicodemother.core.handler.GenerationStreamEvent;
import com.yupi.yuaicodemother.langgraph4j.model.QualityResult;
import com.yupi.yuaicodemother.langgraph4j.state.WorkflowContext;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import reactor.core.publisher.Flux;

import java.time.Duration;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 网站代码生成节点
 */
@Slf4j
public class CodeGeneratorNode {

    public static AsyncNodeAction<MessagesState<String>> create() {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 代码生成");
            // 构造用户消息（包含原始提示词和可能的错误修复信息）
            String userMessage = buildUserMessage(context);
            CodeGenTypeEnum generationType = context.getGenerationType();
            // 获取 AI 代码生成外观服务
            AiCodeGeneratorFacade codeGeneratorFacade = SpringContextUtil.getBean(AiCodeGeneratorFacade.class);
            log.info("开始生成代码，类型: {} ({})", generationType.getValue(), generationType.getText());
            // 先使用固定的 appId (后续再整合到业务中)
            Long appId = 0L;
            // 调用流式代码生成
            Flux<GenerationStreamEvent> codeStream = codeGeneratorFacade.generateAndSaveCodeStream(userMessage, generationType, appId);
            // 同步等待流式输出完成
            codeStream.blockLast(Duration.ofMinutes(10)); // 最多等待 10 分钟
            // 根据类型设置生成目录
            String generatedCodeDir = String.format("%s/%s_%s", AppConstant.CODE_OUTPUT_ROOT_DIR, generationType.getValue(), appId);
            log.info("AI 代码生成完成，生成目录: {}", generatedCodeDir);

            // 更新状态
            context.setCurrentStep("代码生成");
            context.setGeneratedCodeDir(generatedCodeDir);
            return WorkflowContext.saveContext(context);
        });
    }

    /**
     * 构造用户消息，如果存在质检失败结果则添加错误修复信息
     */
    private static String buildUserMessage(WorkflowContext context) {
        String userMessage = context.getEnhancedPrompt();
        QualityResult qualityResult = context.getQualityResult();
        if (isQualityCheckFailed(qualityResult)) {
            userMessage = buildErrorFixPrompt(context, qualityResult);
        }
        return userMessage;
    }

    /**
     * 判断质检是否失败
     */
    private static boolean isQualityCheckFailed(QualityResult qualityResult) {
        return qualityResult != null &&
                !qualityResult.getIsValid() &&
                qualityResult.getErrors() != null &&
                !qualityResult.getErrors().isEmpty();
    }

    /**
     * 构造错误修复提示词
     */
    private static String buildErrorFixPrompt(WorkflowContext context, QualityResult qualityResult) {
        StringBuilder errorInfo = new StringBuilder();
        errorInfo.append("请在现有已生成项目基础上做局部修复，不要重写整个项目，不要无关改动。\n");
        if (context.getEnhancedPrompt() != null) {
            errorInfo.append("\n## 原始生成要求\n").append(context.getEnhancedPrompt()).append("\n");
        }
        if (context.getGeneratedCodeDir() != null) {
            errorInfo.append("\n## 已生成项目目录\n").append(context.getGeneratedCodeDir()).append("\n");
        }
        errorInfo.append("\n## 质检发现的问题\n");
        qualityResult.getErrors().forEach(error ->
                errorInfo.append("- ").append(error).append("\n"));
        if (qualityResult.getSuggestions() != null && !qualityResult.getSuggestions().isEmpty()) {
            errorInfo.append("\n## 修复建议\n");
            qualityResult.getSuggestions().forEach(suggestion ->
                    errorInfo.append("- ").append(suggestion).append("\n"));
        }
        errorInfo.append("\n只修改与上述问题直接相关的文件，保留当前目录结构和已有功能，修复完成后停止。教程、解释、总结都不要输出到项目文件中。");
        return errorInfo.toString();
    }
}
