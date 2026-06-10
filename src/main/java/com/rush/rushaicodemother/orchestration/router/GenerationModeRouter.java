package com.rush.rushaicodemother.orchestration.router;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class GenerationModeRouter {

    private static final List<String> HEAVY_EXPERT_KEYWORDS = List.of(
            "完整重构", "彻底重构", "重构整个", "全部重写", "从头重写", "推倒重来",
            "重新生成整个项目", "专家模式", "深度重构", "更换技术栈", "换框架"
    );

    private static final List<String> CREATE_HEAVY_EXPERT_KEYWORDS = List.of(
            "微服务", "分布式", "kubernetes", "k8s", "多租户", "高并发",
            "支付系统", "区块链", "训练模型", "自研框架", "复杂工作流"
    );

    private static final List<String> AGENT_EDIT_KEYWORDS = List.of(
            "新增功能", "增加功能", "实现功能", "跨文件", "多个文件", "接口", "api",
            "数据库", "db", "sql", "后端", "前后端", "全栈", "路由", "store", "pinia",
            "依赖", "package.json", "构建失败", "编译失败", "运行时报错", "修 bug", "修bug",
            "bug", "报错", "crud", "字段同步", "权限", "登录", "注册"
    );

    private static final List<String> LIGHT_EDIT_KEYWORDS = List.of(
            "文案", "标题", "文字", "文本", "颜色", "样式", "字体", "字号", "间距",
            "背景", "圆角", "阴影", "透明度", "按钮文字", "占位符", "图标", "链接",
            "对齐", "边距", "宽度", "高度"
    );

    public GenerationModeDecision route(GenerationTaskRequest request,
                                        CodeGenTypeEnum codeGenType,
                                        GenerationWorkspace workspace) {
        ThrowUtils.throwIf(request == null || request.app() == null, ErrorCode.PARAMS_ERROR, "生成任务参数错误");
        ThrowUtils.throwIf(codeGenType == null, ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
        ThrowUtils.throwIf(workspace == null, ErrorCode.PARAMS_ERROR, "生成工作区参数错误");
        String message = normalize(request.message());

        if (!workspace.exists() && containsAny(message, CREATE_HEAVY_EXPERT_KEYWORDS)) {
            return GenerationModeDecision.of(
                    GenerationMode.HEAVY_EXPERT,
                    0.84,
                    "首次生成需求超出当前 CREATE 模板覆盖范围，进入专家模式",
                    FallbackPolicy.NONE,
                    ExpectedValidationLevel.EXPERT
            );
        }
        if (!workspace.exists()) {
            return GenerationModeDecision.of(
                    GenerationMode.CREATE,
                    0.95,
                    "工作区不存在，进入 CREATE 模式",
                    FallbackPolicy.NONE,
                    ExpectedValidationLevel.BUILD
            );
        }
        if (containsAny(message, HEAVY_EXPERT_KEYWORDS)) {
            return GenerationModeDecision.of(
                    GenerationMode.HEAVY_EXPERT,
                    0.9,
                    "用户明确要求完整重构或专家处理",
                    FallbackPolicy.NONE,
                    ExpectedValidationLevel.EXPERT
            );
        }
        if (containsAny(message, AGENT_EDIT_KEYWORDS)) {
            return GenerationModeDecision.of(
                    GenerationMode.AGENT_EDIT,
                    0.82,
                    "请求涉及新功能、跨文件、接口、数据库、依赖、构建错误或 bug 修复",
                    FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                    ExpectedValidationLevel.BUILD
            );
        }
        if (containsAny(message, LIGHT_EDIT_KEYWORDS) || looksLikeSmallSingleFileEdit(message)) {
            return GenerationModeDecision.of(
                    GenerationMode.LIGHT_EDIT,
                    0.86,
                    "请求属于文案、颜色、样式或单文件小范围修改",
                    FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                    ExpectedValidationLevel.FAST
            );
        }
        return GenerationModeDecision.of(
                GenerationMode.AGENT_EDIT,
                0.62,
                "工作区已存在但未命中轻量编辑条件，进入代码理解编辑模式",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD
        );
    }

    private boolean looksLikeSmallSingleFileEdit(String message) {
        if (StrUtil.isBlank(message) || message.length() > 160) {
            return false;
        }
        return message.contains("修改")
                || message.contains("调整")
                || message.contains("更改")
                || message.contains("替换")
                || message.contains("改成")
                || message.contains("换成");
    }

    private boolean containsAny(String message, List<String> keywords) {
        return keywords.stream().anyMatch(message::contains);
    }

    private String normalize(String message) {
        return StrUtil.blankToDefault(message, "").toLowerCase(Locale.ROOT);
    }
}
