package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 轻量编辑路由服务。
 * 判断用户请求走 heavy generation path 还是 lightweight edit path。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationEditRouteService {

    private final GenerationWorkspaceService generationWorkspaceService;

    /**
     * 重型生成触发关键词（用户明确要求重做或大改）
     */
    private static final Set<String> HEAVY_KEYWORDS = Set.of(
            "重做", "重新生成", "重新创建", "改技术栈", "升级全栈",
            "增加后端", "增加数据库", "安装依赖", "换框架", "重构项目",
            "重新来", "推倒重来", "全部重写", "从头开始"
    );

    /**
     * 轻量编辑触发关键词（小修改）
     */
    private static final Set<String> LIGHTWEIGHT_KEYWORDS = Set.of(
            "文案", "颜色", "样式", "按钮", "布局", "页面", "字段",
            "图片", "标题", "表格列", "表单项", "修复", "报错",
            "修改", "调整", "更改", "替换", "更新", "改成", "换成",
            "字体", "间距", "背景", "图标", "链接", "文字", "文本",
            "大小", "位置", "对齐", "边距", "圆角", "阴影", "透明度"
    );

    /**
     * 包路径模式，用于检测用户消息中是否包含明确的文件路径
     */
    private static final Pattern PATH_PATTERN = Pattern.compile(
            "(?:src/|public/|components/|views/|pages/|assets/|styles/|utils/|api/|router/|store/)\\S+\\.(?:vue|js|ts|jsx|tsx|css|scss|less|html|json)"
    );

    /**
     * 判断请求应该走哪条路径。
     *
     * @param app         应用实体
     * @param userMessage 用户消息
     * @return 路由判断结果
     */
    public GenerationEditRouteResult route(App app, String userMessage) {
        if (app == null || app.getId() == null) {
            return GenerationEditRouteResult.heavyGeneration("应用参数无效");
        }

        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenType == null) {
            return GenerationEditRouteResult.heavyGeneration("代码生成类型无效");
        }

        return route(app, userMessage, generationWorkspaceService.resolve(app, codeGenType));
    }

    /** Routes using the exact workspace selected for the current durable execution epoch. */
    public GenerationEditRouteResult route(App app,
                                           String userMessage,
                                           GenerationWorkspace workspace) {
        if (app == null || app.getId() == null) {
            return GenerationEditRouteResult.heavyGeneration("应用参数无效");
        }
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenType == null) {
            return GenerationEditRouteResult.heavyGeneration("代码生成类型无效");
        }
        if (workspace == null || !app.getId().equals(workspace.appId())
                || workspace.codeGenType() != codeGenType) {
            return GenerationEditRouteResult.heavyGeneration("执行工作区上下文不匹配");
        }

        // 1. 没有生成目录 → 重型生成
        if (!workspace.exists()) {
            return GenerationEditRouteResult.heavyGeneration("项目尚未生成，需要完整生成");
        }

        String normalizedMessage = StrUtil.blankToDefault(userMessage, "").toLowerCase();

        // 2. 包含重型关键词 → 重型生成
        for (String keyword : HEAVY_KEYWORDS) {
            if (normalizedMessage.contains(keyword)) {
                return GenerationEditRouteResult.heavyGeneration("用户请求包含重型生成关键词: " + keyword);
            }
        }

        // 3. 包含轻量编辑关键词 → 轻量编辑
        for (String keyword : LIGHTWEIGHT_KEYWORDS) {
            if (normalizedMessage.contains(keyword)) {
                boolean requiresBuild = detectBuildRequirement(normalizedMessage);
                return GenerationEditRouteResult.lightweightEdit(
                        "用户请求命中小改关键词: " + keyword,
                        0.85,
                        requiresBuild
                );
            }
        }

        // 4. 包含明确文件路径 → 轻量编辑
        if (PATH_PATTERN.matcher(normalizedMessage).find()) {
            return GenerationEditRouteResult.lightweightEdit(
                    "用户消息包含明确文件路径",
                    0.8,
                    false
            );
        }

        // 5. 项目已存在且消息较短 → 轻量编辑（默认）
        if (userMessage != null && userMessage.length() < 500) {
            return GenerationEditRouteResult.lightweightEdit(
                    "项目已存在且用户请求较短，默认走轻量编辑",
                    0.6,
                    false
            );
        }

        // 6. 默认走重型生成
        return GenerationEditRouteResult.heavyGeneration("未命中轻量编辑条件，走重型生成");
    }

    /**
     * 检测用户请求是否涉及需要构建的修改。
     */
    private boolean detectBuildRequirement(String message) {
        Set<String> buildKeywords = Set.of(
                "路由", "依赖", "配置", "api", "接口", "请求",
                "package.json", "vite.config", "tsconfig",
                "新增组件", "删除组件", "新页面"
        );
        return buildKeywords.stream().anyMatch(message::contains);
    }
}
