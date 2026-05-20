package com.rush.rushaicodemother.langgraph4j.node;

import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.langgraph4j.state.WorkflowContext;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import java.io.File;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 项目构建节点
 */
@Slf4j
public class ProjectBuilderNode {

    public static AsyncNodeAction<MessagesState<String>> create() {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 项目构建");

            String generatedCodeDir = context.getGeneratedCodeDir();
            CodeGenTypeEnum generationType = context.getGenerationType();
            String buildResultDir = generatedCodeDir;
            if (generationType == CodeGenTypeEnum.VUE_PROJECT) {
                VueProjectBuilder vueBuilder = SpringContextUtil.getBean(VueProjectBuilder.class);
                vueBuilder.buildProjectAsync(generatedCodeDir);
                buildResultDir = generatedCodeDir + File.separator + "dist";
                log.info("Vue 项目构建已异步提交，dist 目录将在后台生成: {}", buildResultDir);
            } else {
                log.info("当前生成类型无需进入异步构建流程: {}", generationType);
            }

            context.setCurrentStep("构建校验中");
            context.setBuildResultDir(buildResultDir);
            log.info("项目构建节点完成，返回目录: {}", buildResultDir);
            return WorkflowContext.saveContext(context);
        });
    }
}
