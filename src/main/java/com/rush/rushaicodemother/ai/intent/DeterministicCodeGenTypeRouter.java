package com.rush.rushaicodemother.ai.intent;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 对高置信用户意图执行本地代码生成类型路由。 */
@Component
public class DeterministicCodeGenTypeRouter {

    private static final List<String> ENGINEERED_FRONTEND_SIGNALS = List.of(
            "vue", "react", "angular", "svelte", "组件", "路由", "状态管理",
            "多页面", "后台管理", "管理系统", "dashboard", "复杂交互", "依赖安装",
            "构建校验", "工程化", "持续迭代"
    );

    private static final List<String> MULTI_FILE_SIGNALS = List.of(
            "html/css/js", "html/css/javascript", "html、css、js", "html、css、javascript",
            "html css js", "html css javascript", "三件套", "多文件静态", "静态站点三文件",
            "css和js分离", "css与js分离"
    );

    private static final List<String> SINGLE_HTML_SIGNALS = List.of(
            "单个html", "单文件html", "一个html文件", "html单文件", "纯html",
            "内联css", "内联javascript", "内联js"
    );

    private static final List<String> SIMPLE_STATIC_SIGNALS = List.of(
            "简单展示页", "静态展示页", "个人介绍页面", "个人介绍页", "个人主页",
            "活动页", "落地页", "产品宣传页", "一次性展示页", "landing page"
    );

    public Optional<CodeGenTypeEnum> route(
            String userMessage,
            BackendIntentDetector.BackendIntentResult intent) {
        if (intent == null) {
            return Optional.empty();
        }
        Optional<CodeGenTypeEnum> explicitRoute = routeExplicit(userMessage, intent);
        if (explicitRoute.isPresent()) {
            return explicitRoute;
        }
        return intent.level() == BackendIntentDetector.BackendIntentResult.IntentLevel.NONE
                ? Optional.of(CodeGenTypeEnum.VUE_PROJECT)
                : Optional.empty();
    }

    /** 只返回用户消息中有明确证据支持的类型，不应用新应用默认值。 */
    public Optional<CodeGenTypeEnum> routeExplicit(
            String userMessage,
            BackendIntentDetector.BackendIntentResult intent) {
        if (intent == null) {
            return Optional.empty();
        }
        return switch (intent.level()) {
            case FULLSTACK -> Optional.of(CodeGenTypeEnum.FULL_STACK_PROJECT);
            case EXPLICIT_BACKEND -> Optional.of(CodeGenTypeEnum.BACKEND_PROJECT);
            case AMBIGUOUS -> Optional.empty();
            case NONE -> routeExplicitFrontend(userMessage);
        };
    }

    private Optional<CodeGenTypeEnum> routeExplicitFrontend(String userMessage) {
        String normalized = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT).trim();
        if (containsAny(normalized, ENGINEERED_FRONTEND_SIGNALS)) {
            return Optional.of(CodeGenTypeEnum.VUE_PROJECT);
        }
        if (containsAny(normalized, MULTI_FILE_SIGNALS)) {
            return Optional.of(CodeGenTypeEnum.MULTI_FILE);
        }
        if (containsAny(normalized, SINGLE_HTML_SIGNALS)
                || containsAny(normalized, SIMPLE_STATIC_SIGNALS)) {
            return Optional.of(CodeGenTypeEnum.HTML);
        }
        return Optional.empty();
    }

    private boolean containsAny(String text, List<String> signals) {
        return signals.stream().anyMatch(text::contains);
    }
}
